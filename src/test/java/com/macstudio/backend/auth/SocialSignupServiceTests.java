package com.macstudio.backend.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.macstudio.backend.auth.config.AuthPolicy;
import com.macstudio.backend.auth.dto.AgreedTermsRequest;
import com.macstudio.backend.auth.dto.SignupRequest;
import com.macstudio.backend.auth.entity.OAuthTransaction;
import com.macstudio.backend.auth.repository.OAuthTransactionRepository;
import com.macstudio.backend.auth.service.*;
import com.macstudio.backend.member.entity.ConsentDocumentType;
import com.macstudio.backend.member.entity.IdentityProvider;
import com.macstudio.backend.member.repository.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class SocialSignupServiceTests {
    private static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");
    private static final String RAW_TOKEN = "social-signup-token-with-exact-shape-123456";

    /** 소셜 가입 쿠키를 소비해 회원·identity·필수 동의를 저장하고 자동 로그인한다. */
    @Test
    void completesSocialSignupAtomically() {
        OAuthTransactionRepository oauth = mock(OAuthTransactionRepository.class);
        MemberRepository members = mock(MemberRepository.class);
        MemberIdentityRepository identities = mock(MemberIdentityRepository.class);
        MemberConsentRepository consents = mock(MemberConsentRepository.class);
        TokenService tokens = mock(TokenService.class);
        AuthCrypto crypto = mock(AuthCrypto.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        byte[] hash = new byte[32];
        when(crypto.hash(RAW_TOKEN)).thenReturn(hash);
        OAuthTransaction pending = OAuthTransaction.pendingSignup(IdentityProvider.NAVER, "naver-subject",
                "social@example.com", hash, NOW, NOW.plusSeconds(600));
        when(oauth.findBySignupTokenHashForUpdate(hash)).thenReturn(Optional.of(pending));
        when(members.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        AuthPolicy policy = new AuthPolicy(600, 604800, 2592000, 8, "v1", "v1", 5, false);
        var service = new SocialSignupService(oauth, members, identities, consents, tokens, crypto,
                policy, Clock.fixed(NOW, ZoneOffset.UTC), manager);
        SignupRequest request = new SignupRequest("SOCIAL", null, null, null, List.of(
                new AgreedTermsRequest(ConsentDocumentType.TERMS, "v1"),
                new AgreedTermsRequest(ConsentDocumentType.PRIVACY, "v1")));

        service.signup(request, RAW_TOKEN);

        verify(members).saveAndFlush(argThat(member -> member.getEmail().equals("social@example.com")
                && !member.hasPassword()));
        verify(identities).saveAndFlush(argThat(identity -> identity.getProvider() == IdentityProvider.NAVER
                && identity.getProviderSubject().equals("naver-subject")));
        verify(consents, times(2)).save(any());
        verify(tokens).start(any(), eq(NOW));
        assertThat(pending.getSignupConsumedAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }
}
