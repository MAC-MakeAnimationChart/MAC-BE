package com.macstudio.backend.auth.service;

import com.macstudio.backend.auth.config.AuthPolicy;
import com.macstudio.backend.auth.dto.*;
import com.macstudio.backend.auth.entity.AuthChallenge;
import com.macstudio.backend.auth.entity.AuthPurpose;
import com.macstudio.backend.auth.repository.*;
import com.macstudio.backend.member.entity.*;
import com.macstudio.backend.member.repository.*;
import com.macstudio.backend.global.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AccountAuthService {
    private final MemberRepository members;
    private final MemberConsentRepository consents;
    private final AuthChallengeRepository challenges;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwords;
    private final AuthCrypto crypto;
    private final TokenService tokens;
    private final AuthPolicy policy;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final String dummyHash;

    /** 인증·회원 저장소와 토큰 발급기를 하나의 업무 트랜잭션으로 연결한다. */
    public AccountAuthService(MemberRepository members, MemberConsentRepository consents,
            AuthChallengeRepository challenges, RefreshTokenRepository refreshTokens, PasswordEncoder passwords,
            AuthCrypto crypto, TokenService tokens, AuthPolicy policy, Clock clock, PlatformTransactionManager manager) {
        this.members = members; this.consents = consents; this.challenges = challenges; this.refreshTokens = refreshTokens;
        this.passwords = passwords; this.crypto = crypto; this.tokens = tokens; this.policy = policy; this.clock = clock;
        this.transaction = new TransactionTemplate(manager); this.dummyHash = passwords.encode(crypto.token());
    }

    /** 필수 동의와 가입 증명을 확인하여 회원·동의·Refresh 저장과 증명 소비를 원자적으로 처리한다. */
    public TokenService.IssuedTokens signup(SignupRequest request) {
        if (!"EMAIL".equals(request.signupType())) throw bad("UNSUPPORTED_SIGNUP", "이메일 가입만 지원합니다.");
        validateTerms(request.agreedTerms());
        if (!validEmail(request.email()) || request.password() == null || request.password().isBlank()
                || request.verificationToken() == null || request.verificationToken().isBlank())
            throw bad("INVALID_REQUEST", "이메일 가입 요청 형식을 확인해 주세요.");
        if (request.password().codePointCount(0, request.password().length()) < policy.passwordMinLength()
                || request.password().getBytes(StandardCharsets.UTF_8).length > 72)
            throw bad("INVALID_PASSWORD", "비밀번호 길이를 확인해 주세요.");
        String encoded = passwords.encode(request.password()); // 느린 해싱은 행 잠금 전에 수행한다.
        try {
            return transaction.execute(s -> {
                var proof = challenges.findByProofTokenHashForUpdate(crypto.hash(request.verificationToken()))
                        .orElseThrow(() -> bad("INVALID_SIGNUP_PROOF", "가입 인증이 만료되었거나 유효하지 않습니다."));
                try { proof.consumeSignupProof(request.email(), clock.instant()); }
                catch (IllegalStateException e) { throw bad("INVALID_SIGNUP_PROOF", "가입 인증이 만료되었거나 유효하지 않습니다."); }
                if (members.existsByEmail(request.email())) throw duplicateEmail();
                var member = members.saveAndFlush(Member.createEmailMember(request.email(), encoded));
                for (var term : request.agreedTerms()) consents.save(MemberConsent.agree(member, term.type(), term.version()));
                return tokens.start(member, clock.instant());
            });
        } catch (DataIntegrityViolationException conflict) {
            // 실패 트랜잭션 밖에서 이메일 충돌을 확인한다. 증명 소비는 이미 롤백된 상태다.
            if (members.existsByEmail(request.email())) throw duplicateEmail();
            throw conflict;
        }
    }

    /** 없는 계정·소셜 전용 계정에도 해시 비교를 수행하고 실패 응답을 동일하게 반환한다. */
    public TokenService.IssuedTokens login(LoginRequest request) {
        var found = members.findByEmail(request.email());
        String encoded = found.filter(Member::hasPassword).map(Member::getPasswordHash).orElse(dummyHash);
        boolean lengthValid = request.password().getBytes(StandardCharsets.UTF_8).length <= 72;
        boolean matches = passwords.matches(lengthValid ? request.password() : "invalid-password-input", encoded);
        if (!lengthValid || !matches || found.isEmpty() || !found.get().hasPassword() || !found.get().isActive()) throw credentials();
        return transaction.execute(s -> {
            var member = members.findByIdForUpdate(found.get().getId()).orElseThrow(this::credentials);
            if (!member.isActive() || !Objects.equals(member.getPasswordHash(), encoded)) throw credentials();
            return tokens.start(member, clock.instant());
        });
    }

    /** 이메일 인증 증명을 소비해 비밀번호를 교체하고 기존 로그인과 다른 재설정 증명을 폐기한다. */
    public void resetPassword(PasswordResetRequest request) {
        if (!validEmail(request.email()) || request.verificationToken() == null
                || request.verificationToken().isBlank()) throw resetProofError();
        if (request.newPassword().codePointCount(0, request.newPassword().length()) < policy.passwordMinLength()
                || request.newPassword().getBytes(StandardCharsets.UTF_8).length > 72)
            throw bad("INVALID_PASSWORD", "비밀번호 길이를 확인해 주세요.");
        String encoded = passwords.encode(request.newPassword()); // 느린 해싱은 DB 잠금 전에 수행한다.
        transaction.executeWithoutResult(s -> {
            var member = members.findByEmailForUpdate(request.email()).orElseThrow(this::resetProofError);
            if (!member.isActive() || !member.hasPassword()) throw resetProofError();
            var proof = challenges.findByProofTokenHashForUpdate(crypto.hash(request.verificationToken()))
                    .orElseThrow(this::resetProofError);
            try { proof.consumePasswordResetProof(member, request.email(), clock.instant()); }
            catch (IllegalStateException e) { throw resetProofError(); }

            Instant now = clock.instant();
            refreshTokens.findAllActiveByMemberId(member.getId()).forEach(token -> token.revoke(now));
            member.resetPassword(encoded);
            challenges.findUnconsumedByEmailForUpdate(request.email(), AuthPurpose.PASSWORD_RESET)
                    .forEach(AuthChallenge::cancel);
        });
    }

    /** 회원 잠금 아래 Refresh를 회전한다. 재사용 시 family 폐기를 커밋한 후 401을 반환한다. */
    public TokenService.IssuedTokens refresh(String rawToken) {
        if (!validTokenShape(rawToken)) throw refreshError();
        var result = transaction.execute(s -> {
            byte[] hash = crypto.hash(rawToken);
            var memberId = refreshTokens.findMemberIdByHash(hash);
            if (memberId.isEmpty()) return null;
            var member = members.findByIdForUpdate(memberId.get());
            if (member.isEmpty()) return null;
            var stored = refreshTokens.findByHashForUpdate(hash).orElseThrow();
            Instant now = clock.instant();
            if (!member.get().isActive() || !stored.isUsable(now)) {
                refreshTokens.findActiveFamily(memberId.get(), stored.getFamilyId()).forEach(t -> t.revoke(now));
                return null;
            }
            stored.revoke(now);
            return tokens.rotate(member.get(), stored, now);
        });
        if (result == null) throw refreshError();
        return result;
    }

    /** 현재 토큰 family만 폐기한다. 없는 토큰과 반복 로그아웃은 성공으로 처리한다. */
    public void logout(String rawToken) {
        if (!validTokenShape(rawToken)) return;
        transaction.executeWithoutResult(s -> {
            byte[] hash = crypto.hash(rawToken);
            var memberId = refreshTokens.findMemberIdByHash(hash);
            if (memberId.isEmpty() || members.findByIdForUpdate(memberId.get()).isEmpty()) return;
            refreshTokens.findByHashForUpdate(hash).ifPresent(stored ->
                    refreshTokens.findActiveFamily(memberId.get(), stored.getFamilyId()).forEach(t -> t.revoke(clock.instant())));
        });
    }

    /** 현재 비밀번호를 재확인하고 모든 로그인을 폐기한 뒤 회원을 soft delete한다. */
    public void withdraw(String subject, WithdrawalRequest request) {
        withdraw(subject, request, null);
    }

    /** 로그인 방식에 따라 비밀번호 또는 짧은 소셜 재인증 증명을 소비한다. */
    public void withdraw(String subject, WithdrawalRequest request, String socialReauthToken) {
        if (!request.acknowledgeEmailReuseBlocked()) {
            throw bad("WITHDRAWAL_ACK_REQUIRED", "동일 이메일 재가입 제한을 확인해 주세요.");
        }
        Long memberId = parseMemberId(subject);
        transaction.executeWithoutResult(s -> {
            var member = members.findByIdForUpdate(memberId).orElseThrow(this::unauthorized);
            if (!member.isActive()) throw unauthorized();
            if (member.hasPassword()) {
                boolean lengthValid = request.password() != null
                        && request.password().getBytes(StandardCharsets.UTF_8).length <= 72;
                boolean matches = passwords.matches(
                        lengthValid ? request.password() : "invalid-password-input",
                        member.getPasswordHash());
                if (!lengthValid || !matches) throw credentials();
            } else {
                if (!validTokenShape(socialReauthToken))
                    throw bad("SOCIAL_REAUTH_REQUIRED", "소셜 계정은 제공사 재인증이 필요합니다.");
                var proof = challenges.findByProofTokenHashForUpdate(crypto.hash(socialReauthToken))
                        .orElseThrow(() -> bad("SOCIAL_REAUTH_REQUIRED", "소셜 계정은 제공사 재인증이 필요합니다."));
                try { proof.consumeSocialWithdrawalProof(memberId, clock.instant()); }
                catch (IllegalStateException e) {
                    throw bad("SOCIAL_REAUTH_REQUIRED", "소셜 계정은 제공사 재인증이 필요합니다.");
                }
            }

            Instant now = clock.instant();
            refreshTokens.findAllActiveByMemberId(memberId).forEach(token -> token.revoke(now));
            member.beginDeletion();
            member.completeWithdrawal(now);
        });
    }

    /** 필수 두 문서의 중복·누락·버전을 검증한다. 예시 v1을 하드코딩하지 않는다. */
    private void validateTerms(List<AgreedTermsRequest> terms) {
        var versions = new EnumMap<ConsentDocumentType, String>(ConsentDocumentType.class);
        if (terms == null || terms.size() != 2) throw bad("INVALID_TERMS", "필수 약관 동의를 확인해 주세요.");
        for (var term : terms) {
            if (term == null || term.type() == null || versions.put(term.type(), term.version()) != null)
                throw bad("INVALID_TERMS", "필수 약관 동의를 확인해 주세요.");
        }
        if (!policy.termsVersion().equals(versions.get(ConsentDocumentType.TERMS))
                || !policy.privacyVersion().equals(versions.get(ConsentDocumentType.PRIVACY)))
            throw bad("INVALID_TERMS", "최신 필수 약관에 동의해 주세요.");
    }
    /** 쿠키 값의 최대 크기와 발급 형식을 제한한다. */
    private boolean validTokenShape(String token) { return token != null && token.matches("[A-Za-z0-9_-]{43}"); }
    /** DTO의 조건부 검증 대신 EMAIL 분기에서 정규화된 최소 이메일 형식을 확인한다. */
    private boolean validEmail(String email) {
        return email != null && !email.isBlank() && email.length() <= 254
                && email.equals(email.strip()) && email.indexOf('@') > 0 && !email.endsWith("@");
    }
    /** JWT subject를 현재 회원 PK 형식으로 제한한다. */
    private Long parseMemberId(String subject) {
        if (subject == null || !subject.matches("[0-9]+")) throw unauthorized();
        try {
            long id = Long.parseLong(subject);
            if (id <= 0) throw unauthorized();
            return id;
        } catch (NumberFormatException e) {
            throw unauthorized();
        }
    }
    /** 회원 중복 오류를 통일한다. */
    private ApiException duplicateEmail() { return new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_USED", "이미 사용 중인 이메일입니다."); }
    /** 로그인 실패 사유를 외부에서 구분하지 못하도록 한다. */
    private ApiException credentials() { return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "이메일 또는 비밀번호를 확인해 주세요."); }
    /** 재발급 거절을 공통 인증 실패로 전달한다. */
    private ApiException refreshError() { return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "다시 로그인해 주세요."); }
    /** 회원 존재·상태·증명 실패를 구분하지 않는 비밀번호 재설정 오류를 반환한다. */
    private ApiException resetProofError() { return bad("INVALID_PASSWORD_RESET_PROOF", "비밀번호 재설정 인증이 만료되었거나 유효하지 않습니다."); }
    /** 없거나 이미 탈퇴 처리된 회원을 동일한 인증 실패로 반환한다. */
    private ApiException unauthorized() { return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "현재 회원을 인증할 수 없습니다."); }
    /** 입력·증명 오류를 생성한다. */
    private ApiException bad(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }
}
