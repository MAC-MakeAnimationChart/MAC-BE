package com.macstudio.backend.auth.service;

import com.macstudio.backend.auth.config.AuthPolicy;
import com.macstudio.backend.auth.dto.AgreedTermsRequest;
import com.macstudio.backend.auth.dto.SignupRequest;
import com.macstudio.backend.auth.repository.OAuthTransactionRepository;
import com.macstudio.backend.global.exception.ApiException;
import com.macstudio.backend.member.entity.ConsentDocumentType;
import com.macstudio.backend.member.entity.Member;
import com.macstudio.backend.member.entity.MemberConsent;
import com.macstudio.backend.member.entity.MemberIdentity;
import com.macstudio.backend.member.repository.MemberConsentRepository;
import com.macstudio.backend.member.repository.MemberIdentityRepository;
import com.macstudio.backend.member.repository.MemberRepository;
import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SocialSignupService {
    private final OAuthTransactionRepository oauthTransactions;
    private final MemberRepository members;
    private final MemberIdentityRepository identities;
    private final MemberConsentRepository consents;
    private final TokenService tokens;
    private final AuthCrypto crypto;
    private final AuthPolicy policy;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public SocialSignupService(OAuthTransactionRepository oauthTransactions, MemberRepository members,
            MemberIdentityRepository identities, MemberConsentRepository consents, TokenService tokens,
            AuthCrypto crypto, AuthPolicy policy, Clock clock, PlatformTransactionManager manager) {
        this.oauthTransactions = oauthTransactions;
        this.members = members;
        this.identities = identities;
        this.consents = consents;
        this.tokens = tokens;
        this.crypto = crypto;
        this.policy = policy;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
    }

    /** HttpOnly 가입 증명을 소비하면서 회원·소셜 연결·동의·Refresh를 한 트랜잭션으로 생성한다. */
    public TokenService.IssuedTokens signup(SignupRequest request, String rawSignupToken) {
        if (!"SOCIAL".equals(request.signupType())) throw bad("UNSUPPORTED_SIGNUP", "소셜 가입 요청 형식을 확인해 주세요.");
        if (hasText(request.email()) || hasText(request.password()) || hasText(request.verificationToken()))
            throw bad("INVALID_REQUEST", "소셜 가입에는 이메일·비밀번호·인증 토큰을 직접 보내지 않습니다.");
        validateTerms(request.agreedTerms());
        if (rawSignupToken == null || !rawSignupToken.matches("[A-Za-z0-9_-]{43}")) throw invalidProof();
        byte[] hash = crypto.hash(rawSignupToken);
        try {
            return transaction.execute(status -> {
                var pending = oauthTransactions.findBySignupTokenHashForUpdate(hash).orElseThrow(this::invalidProof);
                try { pending.consumeSignup(clock.instant()); }
                catch (IllegalStateException e) { throw invalidProof(); }
                if (identities.findByProviderAndProviderSubject(pending.getProvider(), pending.getProviderSubject()).isPresent())
                    throw identityConflict();
                if (pending.getVerifiedEmail() != null && members.existsByEmail(pending.getVerifiedEmail()))
                    throw emailConflict();
                Member member = members.saveAndFlush(Member.createSocialMember(pending.getVerifiedEmail()));
                identities.saveAndFlush(MemberIdentity.create(member, pending.getProvider(), pending.getProviderSubject()));
                for (var term : request.agreedTerms())
                    consents.save(MemberConsent.agree(member, term.type(), term.version()));
                return tokens.start(member, clock.instant());
            });
        } catch (DataIntegrityViolationException conflict) {
            throw new ApiException(HttpStatus.CONFLICT, "SOCIAL_SIGNUP_CONFLICT", "이미 가입된 계정 정보가 있습니다. 다시 로그인해 주세요.");
        }
    }

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
    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
    private ApiException invalidProof() { return bad("INVALID_SOCIAL_SIGNUP_PROOF", "소셜 가입 인증이 만료되었거나 유효하지 않습니다."); }
    private ApiException identityConflict() { return new ApiException(HttpStatus.CONFLICT, "SOCIAL_IDENTITY_ALREADY_USED", "이미 가입된 소셜 계정입니다. 다시 로그인해 주세요."); }
    private ApiException emailConflict() { return new ApiException(HttpStatus.CONFLICT, "EMAIL_CONFLICT", "같은 이메일의 기존 계정이 있어 자동으로 연결할 수 없습니다."); }
    private ApiException bad(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }
}
