package com.macstudio.backend.auth.service;

import com.macstudio.backend.auth.config.SocialAuthProperties;
import com.macstudio.backend.auth.dto.SocialAuthRequest;
import com.macstudio.backend.auth.dto.SocialSignupPendingResponse;
import com.macstudio.backend.auth.entity.AuthChallenge;
import com.macstudio.backend.auth.entity.OAuthTransaction;
import com.macstudio.backend.auth.repository.AuthChallengeRepository;
import com.macstudio.backend.auth.repository.OAuthTransactionRepository;
import com.macstudio.backend.global.exception.ApiException;
import com.macstudio.backend.member.repository.MemberIdentityRepository;
import com.macstudio.backend.member.repository.MemberRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SocialAuthService {
    /** 기존 회원 로그인 또는 신규 가입 대기 중 정확히 하나만 담는다. */
    public record ExchangeResult(TokenService.IssuedTokens tokens, SocialSignupPendingResponse pending,
                                 String signupToken, long signupMaxAge) {
        public boolean authenticated() { return tokens != null; }
        @Override public String toString() { return "ExchangeResult[REDACTED]"; }
    }
    /** 소셜 민감 작업 재인증 쿠키의 원문과 수명. */
    public record ReauthResult(String token, long maxAge) {
        @Override public String toString() { return "ReauthResult[REDACTED]"; }
    }

    private final SocialProviderClient providerClient;
    private final MemberRepository members;
    private final MemberIdentityRepository identities;
    private final OAuthTransactionRepository oauthTransactions;
    private final AuthChallengeRepository challenges;
    private final TokenService tokens;
    private final AuthCrypto crypto;
    private final SocialAuthProperties properties;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public SocialAuthService(SocialProviderClient providerClient, MemberRepository members,
            MemberIdentityRepository identities, OAuthTransactionRepository oauthTransactions,
            AuthChallengeRepository challenges, TokenService tokens, AuthCrypto crypto,
            SocialAuthProperties properties, Clock clock, PlatformTransactionManager manager) {
        this.providerClient = providerClient;
        this.members = members;
        this.identities = identities;
        this.oauthTransactions = oauthTransactions;
        this.challenges = challenges;
        this.tokens = tokens;
        this.crypto = crypto;
        this.properties = properties;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
    }

    /** 제공사 code를 서버에서 교환한 뒤 기존 신원은 로그인, 신규 신원은 가입 대기로 분기한다. */
    public ExchangeResult exchange(SocialAuthRequest request) {
        VerifiedSocialIdentity verified = providerClient.exchange(request);
        var linked = identities.findByProviderAndProviderSubject(verified.provider(), verified.subject());
        if (linked.isPresent()) {
            Long memberId = linked.get().getMember().getId();
            return transaction.execute(status -> {
                var member = members.findByIdForUpdate(memberId).orElseThrow(this::unauthorized);
                if (!member.isActive()) throw unauthorized();
                oauthTransactions.save(OAuthTransaction.login(verified.provider(), verified.subject(),
                        verified.email(), member, clock.instant()));
                return new ExchangeResult(tokens.start(member, clock.instant()), null, null, 0);
            });
        }

        if (verified.email() != null && members.existsByEmail(verified.email())) throw emailConflict();
        String rawToken = crypto.token();
        Instant now = clock.instant();
        Instant expiresAt = now.plusSeconds(properties.signupTtlSeconds());
        transaction.executeWithoutResult(status -> {
            if (identities.findByProviderAndProviderSubject(verified.provider(), verified.subject()).isPresent())
                throw new ApiException(HttpStatus.CONFLICT, "SOCIAL_IDENTITY_ALREADY_USED", "이미 가입된 소셜 계정입니다. 다시 로그인해 주세요.");
            if (verified.email() != null && members.existsByEmail(verified.email())) throw emailConflict();
            oauthTransactions.save(OAuthTransaction.pendingSignup(verified.provider(), verified.subject(),
                    verified.email(), crypto.hash(rawToken), now, expiresAt));
        });
        return new ExchangeResult(null,
                new SocialSignupPendingResponse(true, verified.provider().name(), verified.email()),
                rawToken, Math.max(0, Duration.between(now, expiresAt).toSeconds()));
    }

    /** 현재 JWT 회원과 동일한 연결 신원을 제공사에서 다시 확인하고 탈퇴용 일회성 증명을 발급한다. */
    public ReauthResult reauthenticate(String subject, SocialAuthRequest request) {
        Long memberId = parseMemberId(subject);
        VerifiedSocialIdentity verified = providerClient.exchange(request);
        String rawToken = crypto.token();
        Instant now = clock.instant();
        Instant expiresAt = now.plusSeconds(properties.reauthTtlSeconds());
        transaction.executeWithoutResult(status -> {
            var member = members.findByIdForUpdate(memberId).orElseThrow(this::unauthorized);
            if (!member.isActive() || member.hasPassword())
                throw new ApiException(HttpStatus.BAD_REQUEST, "SOCIAL_REAUTH_NOT_ALLOWED", "소셜 계정에서만 사용할 수 있습니다.");
            var linked = identities.findByProviderAndProviderSubject(verified.provider(), verified.subject())
                    .orElseThrow(this::reauthMismatch);
            if (!Objects.equals(linked.getMember().getId(), memberId)) throw reauthMismatch();
            var challenge = challenges.saveAndFlush(AuthChallenge.createSocialReauth(member, verified.provider(),
                    crypto.hash(rawToken), now, expiresAt));
            oauthTransactions.save(OAuthTransaction.reauth(verified.provider(), verified.subject(),
                    verified.email(), member, challenge, now));
        });
        return new ReauthResult(rawToken, Math.max(0, Duration.between(now, expiresAt).toSeconds()));
    }

    private Long parseMemberId(String subject) {
        if (subject == null || !subject.matches("[0-9]+")) throw unauthorized();
        try {
            long value = Long.parseLong(subject);
            if (value <= 0) throw unauthorized();
            return value;
        } catch (NumberFormatException e) { throw unauthorized(); }
    }
    private ApiException emailConflict() { return new ApiException(HttpStatus.CONFLICT, "EMAIL_CONFLICT", "같은 이메일의 기존 계정이 있어 자동으로 연결할 수 없습니다."); }
    private ApiException reauthMismatch() { return new ApiException(HttpStatus.UNAUTHORIZED, "SOCIAL_REAUTH_MISMATCH", "현재 회원과 같은 소셜 계정으로 다시 인증해 주세요."); }
    private ApiException unauthorized() { return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "현재 회원을 인증할 수 없습니다."); }
}
