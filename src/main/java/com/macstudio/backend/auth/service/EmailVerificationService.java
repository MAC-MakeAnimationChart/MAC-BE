package com.macstudio.backend.auth.service;

import com.macstudio.backend.auth.config.*;
import com.macstudio.backend.auth.dto.*;
import com.macstudio.backend.auth.entity.*;
import com.macstudio.backend.auth.repository.AuthChallengeRepository;
import com.macstudio.backend.member.repository.MemberRepository;
import com.macstudio.backend.global.exception.ApiException;
import java.security.MessageDigest;
import java.time.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EmailVerificationService {
    private record SendPlan(Long storedId, String responseId, AuthPurpose purpose, boolean deliver) {}
    private final AuthChallengeRepository challenges;
    private final MemberRepository members;
    private final AuthCrypto crypto;
    private final VerificationMailSender mail;
    private final EmailRequestLock locks;
    private final EmailVerificationProperties policy;
    private final AuthPolicy authPolicy;
    private final Clock clock;
    private final TransactionTemplate transaction;

    /** 인증 저장소·암호화·메일·정책과 명시적인 트랜잭션 실행기를 연결한다. */
    public EmailVerificationService(AuthChallengeRepository challenges, MemberRepository members, AuthCrypto crypto,
            VerificationMailSender mail, EmailRequestLock locks, EmailVerificationProperties policy,
            AuthPolicy authPolicy, Clock clock, PlatformTransactionManager manager) {
        this.challenges = challenges; this.members = members; this.crypto = crypto; this.mail = mail;
        this.locks = locks; this.policy = policy; this.authPolicy = authPolicy; this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
    }

    /** 동일 이메일 발송을 직렬화하고 DB 커밋 후 메일을 발송한다. 실패한 새 코드는 취소한다. */
    public EmailCodeSendResponse sendCode(EmailCodeSendRequest request) {
        return locks.execute(request.email(), () -> {
            Instant now = clock.instant();
            AuthPurpose purpose = purpose(request.purpose());
            String code = crypto.code(policy.codeLength());
            byte[] digest = crypto.codeDigest(purpose, request.email(), code);
            SendPlan plan = transaction.execute(s -> prepare(request.email(), purpose, digest, now));
            try {
                if (plan.deliver()) mail.send(request.email(), code, policy.codeTtlSeconds(), purpose);
            }
            catch (RuntimeException failure) {
                if (plan.storedId() != null) transaction.executeWithoutResult(s ->
                        challenges.findByIdForUpdate(plan.storedId()).ifPresent(AuthChallenge::cancel));
                throw failure;
            }
            return new EmailCodeSendResponse(plan.responseId(), Math.max(0, Duration.between(clock.instant(),
                    now.plusSeconds(policy.codeTtlSeconds())).toSeconds()), policy.resendAfterSeconds());
        });
    }

    /** 가입은 중복을 공개하고, 재설정은 회원 존재·로그인 방식을 숨긴 채 대상 회원만 기록한다. */
    private SendPlan prepare(String email, AuthPurpose purpose, byte[] digest, Instant now) {
        if (purpose == AuthPurpose.SIGNUP) {
            if (members.existsByEmail(email)) throw new ApiException(HttpStatus.CONFLICT,
                    "EMAIL_ALREADY_USED", "이미 사용 중인 이메일입니다.");
            enforceLimits(email, purpose, now);
            challenges.findUnconsumedByEmailForUpdate(email, purpose).forEach(AuthChallenge::cancel);
            Long id = challenges.saveAndFlush(AuthChallenge.createSignupEmail(email, digest, now,
                    now.plusSeconds(policy.codeTtlSeconds()))).getId();
            return new SendPlan(id, id.toString(), purpose, true);
        }

        var member = members.findByEmail(email);
        if (member.isEmpty() || !member.get().isActive() || !member.get().hasPassword()) {
            return new SendPlan(null, crypto.decoyChallengeId(), purpose, false);
        }
        enforceLimits(email, purpose, now);
        challenges.findUnconsumedByEmailForUpdate(email, purpose).forEach(AuthChallenge::cancel);
        Long id = challenges.saveAndFlush(AuthChallenge.createPasswordResetEmail(member.get(), digest, now,
                now.plusSeconds(policy.codeTtlSeconds()))).getId();
        return new SendPlan(id, id.toString(), purpose, true);
    }

    /** 목적별 재발송 대기와 시간당 발송 수를 동일한 기준으로 검사한다. */
    private void enforceLimits(String email, AuthPurpose purpose, Instant now) {
        var last = challenges.findFirstByTargetEmailAndPurposeOrderByCreatedAtDescIdDesc(email, purpose);
        if (last.isPresent() && last.get().getCreatedAt().toInstant(ZoneOffset.UTC)
                .plusSeconds(policy.resendAfterSeconds()).isAfter(now)) throw limited();
        if (challenges.countByTargetEmailAndPurposeAndCreatedAtAfter(email, purpose,
                LocalDateTime.ofInstant(now.minusSeconds(3600), ZoneOffset.UTC)) >= authPolicy.sendsPerHour()) throw limited();
    }

    /** 코드를 비교하고 실패 횟수까지 커밋한 뒤 성공 응답 또는 오류를 반환한다. */
    public EmailVerifyResponse verify(EmailVerifyRequest request) {
        long id;
        try { id = Long.parseLong(request.challengeId()); }
        catch (NumberFormatException e) { throw invalid(); }
        EmailVerifyResponse response = transaction.execute(s -> {
            var challenge = challenges.findByIdForUpdate(id).orElseThrow(this::invalid);
            Instant now = clock.instant();
            if (challenge.getPurpose() != AuthPurpose.SIGNUP
                    && challenge.getPurpose() != AuthPurpose.PASSWORD_RESET) throw invalid();
            try { challenge.requireCodeVerifiable(now, policy.maxAttempts()); }
            catch (IllegalStateException e) { throw invalid(); }
            boolean matches = MessageDigest.isEqual(challenge.getCodeDigest(),
                    crypto.codeDigest(challenge.getPurpose(), challenge.getTargetEmail(), request.code()));
            if (!matches) {
                challenge.recordFailedAttempt(now, policy.maxAttempts());
                return null; // 예외를 트랜잭션 밖에서 던져 실패 횟수 롤백을 막는다.
            }
            String proof = crypto.token();
            challenge.markVerified(crypto.hash(proof), now.plusSeconds(policy.proofTtlSeconds()), now, policy.maxAttempts());
            return new EmailVerifyResponse(proof, policy.proofTtlSeconds());
        });
        if (response == null) throw invalid();
        return response;
    }
    /** 외부 소문자 purpose를 내부 인증 목적에 안전하게 매핑한다. */
    private AuthPurpose purpose(String value) {
        return switch (value) {
            case "signup" -> AuthPurpose.SIGNUP;
            case "password_reset" -> AuthPurpose.PASSWORD_RESET;
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "이메일 인증 목적을 확인해 주세요.");
        };
    }
    /** 발송 제한 원인을 안전한 공통 오류로 변환한다. */
    private ApiException limited() { return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "EMAIL_RATE_LIMITED", "잠시 후 인증코드를 다시 요청해 주세요."); }
    /** 코드·만료·상태 오류를 내부 정보 없이 전달한다. */
    private ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_VERIFICATION", "인증코드가 올바르지 않거나 만료되었습니다."); }
}
