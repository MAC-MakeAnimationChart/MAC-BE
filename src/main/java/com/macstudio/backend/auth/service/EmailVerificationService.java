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
            String code = crypto.code(policy.codeLength());
            byte[] digest = crypto.codeDigest(request.email(), code);
            Long id = transaction.execute(s -> {
                if (members.existsByEmail(request.email())) throw new ApiException(HttpStatus.CONFLICT,
                        "EMAIL_ALREADY_USED", "이미 사용 중인 이메일입니다.");
                var last = challenges.findFirstByTargetEmailAndPurposeOrderByCreatedAtDescIdDesc(request.email(), AuthPurpose.SIGNUP);
                if (last.isPresent() && last.get().getCreatedAt().toInstant(ZoneOffset.UTC)
                        .plusSeconds(policy.resendAfterSeconds()).isAfter(now)) throw limited();
                if (challenges.countByTargetEmailAndPurposeAndCreatedAtAfter(request.email(), AuthPurpose.SIGNUP,
                        LocalDateTime.ofInstant(now.minusSeconds(3600), ZoneOffset.UTC)) >= authPolicy.sendsPerHour()) throw limited();
                challenges.findUnconsumedByEmailForUpdate(request.email(), AuthPurpose.SIGNUP).forEach(AuthChallenge::cancel);
                return challenges.saveAndFlush(AuthChallenge.createSignupEmail(request.email(), digest, now,
                        now.plusSeconds(policy.codeTtlSeconds()))).getId();
            });
            try { mail.send(request.email(), code, policy.codeTtlSeconds()); }
            catch (RuntimeException failure) {
                transaction.executeWithoutResult(s -> challenges.findByIdForUpdate(id).ifPresent(AuthChallenge::cancel));
                throw failure;
            }
            return new EmailCodeSendResponse(id.toString(), Math.max(0, Duration.between(clock.instant(),
                    now.plusSeconds(policy.codeTtlSeconds())).toSeconds()), policy.resendAfterSeconds());
        });
    }

    /** 코드를 비교하고 실패 횟수까지 커밋한 뒤 성공 응답 또는 오류를 반환한다. */
    public EmailVerifyResponse verify(EmailVerifyRequest request) {
        long id;
        try { id = Long.parseLong(request.challengeId()); }
        catch (NumberFormatException e) { throw invalid(); }
        EmailVerifyResponse response = transaction.execute(s -> {
            var challenge = challenges.findByIdForUpdate(id).orElseThrow(this::invalid);
            Instant now = clock.instant();
            if (challenge.getPurpose() != AuthPurpose.SIGNUP) throw invalid();
            try { challenge.requireCodeVerifiable(now, policy.maxAttempts()); }
            catch (IllegalStateException e) { throw invalid(); }
            boolean matches = MessageDigest.isEqual(challenge.getCodeDigest(),
                    crypto.codeDigest(challenge.getTargetEmail(), request.code()));
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
    /** 발송 제한 원인을 안전한 공통 오류로 변환한다. */
    private ApiException limited() { return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "EMAIL_RATE_LIMITED", "잠시 후 인증코드를 다시 요청해 주세요."); }
    /** 코드·만료·상태 오류를 내부 정보 없이 전달한다. */
    private ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_VERIFICATION", "인증코드가 올바르지 않거나 만료되었습니다."); }
}
