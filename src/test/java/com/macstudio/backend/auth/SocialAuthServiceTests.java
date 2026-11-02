package com.macstudio.backend.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.macstudio.backend.auth.config.SocialAuthProperties;
import com.macstudio.backend.auth.dto.SocialAuthRequest;
import com.macstudio.backend.auth.entity.OAuthTransaction;
import com.macstudio.backend.auth.repository.AuthChallengeRepository;
import com.macstudio.backend.auth.repository.OAuthTransactionRepository;
import com.macstudio.backend.auth.service.*;
import com.macstudio.backend.global.exception.ApiException;
import com.macstudio.backend.member.entity.IdentityProvider;
import com.macstudio.backend.member.entity.Member;
import com.macstudio.backend.member.entity.MemberIdentity;
import com.macstudio.backend.member.repository.MemberIdentityRepository;
import com.macstudio.backend.member.repository.MemberRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class SocialAuthServiceTests {
    private static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");
    private static final String RAW_TOKEN = "social-signup-token-with-exact-shape-123456";
    private static final SocialAuthRequest REQUEST = new SocialAuthRequest(
            "google", "authorization-code", "a".repeat(43),
            "http://localhost:3000/auth/callback/google", null);

    /** 처음 보는 신원은 회원/JWT를 만들지 않고 해시된 가입 증명과 202용 응답만 만든다. */
    @Test
    void newIdentityCreatesPendingSignupWithoutMemberOrJwt() {
        Fixture fixture = fixture(new VerifiedSocialIdentity(IdentityProvider.GOOGLE, "provider-subject", "user@example.com"));
        when(fixture.identities.findByProviderAndProviderSubject(IdentityProvider.GOOGLE, "provider-subject"))
                .thenReturn(Optional.empty());
        when(fixture.crypto.token()).thenReturn(RAW_TOKEN);
        byte[] hash = new byte[32];
        when(fixture.crypto.hash(RAW_TOKEN)).thenReturn(hash);

        SocialAuthService.ExchangeResult result = fixture.service.exchange(REQUEST);

        assertThat(result.authenticated()).isFalse();
        assertThat(result.pending().signupRequired()).isTrue();
        assertThat(result.pending().email()).isEqualTo("user@example.com");
        assertThat(result.signupToken()).isEqualTo(RAW_TOKEN);
        verify(fixture.members, never()).save(any());
        verify(fixture.tokens, never()).start(any(), any());
        ArgumentCaptor<OAuthTransaction> saved = ArgumentCaptor.forClass(OAuthTransaction.class);
        verify(fixture.oauth).save(saved.capture());
        assertThat(saved.getValue().getSignupTokenHash()).containsExactly(hash);
    }

    /** provider+subject가 이미 연결되어 있으면 ACTIVE 회원에 기존 JWT/Refresh 로그인을 발급한다. */
    @Test
    void existingIdentityLogsInWithoutCreatingSignupProof() {
        Fixture fixture = fixture(new VerifiedSocialIdentity(IdentityProvider.GOOGLE, "provider-subject", "user@example.com"));
        Member member = Member.createSocialMember("user@example.com");
        ReflectionTestUtils.setField(member, "id", 7L);
        MemberIdentity identity = MemberIdentity.create(member, IdentityProvider.GOOGLE, "provider-subject");
        when(fixture.identities.findByProviderAndProviderSubject(IdentityProvider.GOOGLE, "provider-subject"))
                .thenReturn(Optional.of(identity));
        when(fixture.members.findByIdForUpdate(7L)).thenReturn(Optional.of(member));
        TokenService.IssuedTokens issued = mock(TokenService.IssuedTokens.class);
        when(fixture.tokens.start(member, NOW)).thenReturn(issued);

        SocialAuthService.ExchangeResult result = fixture.service.exchange(REQUEST);

        assertThat(result.authenticated()).isTrue();
        assertThat(result.tokens()).isSameAs(issued);
        assertThat(result.signupToken()).isNull();
        verify(fixture.oauth).save(argThat(value -> value.getMember() == member
                && value.getSignupTokenHash() == null));
    }

    /** 처음 보는 신원의 검증 이메일이 기존 회원 이메일과 같아도 자동 연결하지 않는다. */
    @Test
    void verifiedEmailCollisionIsRejectedInsteadOfAutoMerging() {
        Fixture fixture = fixture(new VerifiedSocialIdentity(IdentityProvider.KAKAO, "subject", "used@example.com"));
        when(fixture.identities.findByProviderAndProviderSubject(IdentityProvider.KAKAO, "subject"))
                .thenReturn(Optional.empty());
        when(fixture.members.existsByEmail("used@example.com")).thenReturn(true);

        assertThatThrownBy(() -> fixture.service.exchange(REQUEST))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("EMAIL_CONFLICT"));
        verify(fixture.oauth, never()).save(any());
    }

    private Fixture fixture(VerifiedSocialIdentity verified) {
        SocialProviderClient provider = mock(SocialProviderClient.class);
        MemberRepository members = mock(MemberRepository.class);
        MemberIdentityRepository identities = mock(MemberIdentityRepository.class);
        OAuthTransactionRepository oauth = mock(OAuthTransactionRepository.class);
        AuthChallengeRepository challenges = mock(AuthChallengeRepository.class);
        TokenService tokens = mock(TokenService.class);
        AuthCrypto crypto = mock(AuthCrypto.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(provider.exchange(REQUEST)).thenReturn(verified);
        var blank = new SocialAuthProperties.Provider("id", "secret", "https://token.example",
                "https://profile.example", List.of(REQUEST.redirectUri()));
        var properties = new SocialAuthProperties(600, 300, blank, blank, blank);
        var service = new SocialAuthService(provider, members, identities, oauth, challenges, tokens,
                crypto, properties, Clock.fixed(NOW, ZoneOffset.UTC), manager);
        return new Fixture(service, members, identities, oauth, tokens, crypto);
    }

    private record Fixture(SocialAuthService service, MemberRepository members,
                           MemberIdentityRepository identities, OAuthTransactionRepository oauth,
                           TokenService tokens, AuthCrypto crypto) {}
}
