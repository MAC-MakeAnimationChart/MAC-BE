package com.macstudio.backend.auth;

import com.macstudio.backend.auth.dto.WithdrawalRequest;
import com.macstudio.backend.auth.entity.AuthChallenge;
import com.macstudio.backend.auth.entity.RefreshToken;
import com.macstudio.backend.auth.repository.AuthChallengeRepository;
import com.macstudio.backend.auth.repository.RefreshTokenRepository;
import com.macstudio.backend.auth.service.*;
import com.macstudio.backend.auth.config.AuthPolicy;
import com.macstudio.backend.global.exception.ApiException;
import com.macstudio.backend.member.entity.Member;
import com.macstudio.backend.member.entity.MemberStatus;
import com.macstudio.backend.member.repository.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AccountWithdrawalTests {
    private static final String PASSWORD = "Current-password-493!";
    private static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");

    /** 현재 비밀번호 확인 후 모든 Refresh를 폐기하고 이메일을 유지한 WITHDRAWN 상태로 전환한다. */
    @Test
    void withdrawsEmailMemberAndRevokesEveryLogin() {
        Fixture fixture = fixture(Member.createEmailMember("member@example.com", fixturePassword()));
        RefreshToken first = mock(RefreshToken.class);
        RefreshToken second = mock(RefreshToken.class);
        when(fixture.refreshTokens.findAllActiveByMemberId(1L)).thenReturn(List.of(first, second));

        fixture.service.withdraw("1", new WithdrawalRequest(PASSWORD, true));

        assertThat(fixture.member.getStatus()).isEqualTo(MemberStatus.WITHDRAWN);
        assertThat(fixture.member.getWithdrawnAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThat(fixture.member.getEmail()).isEqualTo("member@example.com");
        assertThat(fixture.member.getAuthVersion()).isEqualTo(1L);
        verify(first).revoke(NOW);
        verify(second).revoke(NOW);
    }

    /** 비밀번호가 다르면 회원 상태와 로그인 토큰을 변경하지 않는다. */
    @Test
    void rejectsWrongPasswordWithoutChangingMember() {
        Fixture fixture = fixture(Member.createEmailMember("member@example.com", fixturePassword()));

        assertThatThrownBy(() -> fixture.service.withdraw("1", new WithdrawalRequest("wrong-password", true)))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("INVALID_CREDENTIALS"));

        assertThat(fixture.member.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(fixture.member.getWithdrawnAt()).isNull();
        verify(fixture.refreshTokens, never()).findAllActiveByMemberId(any());
    }

    /** 서비스 비밀번호가 없는 소셜 회원은 유효한 제공사 재인증 쿠키가 없으면 탈퇴를 거절한다. */
    @Test
    void requiresProviderReauthenticationForSocialMember() {
        Fixture fixture = fixture(Member.createSocialMember("social@example.com"));

        assertThatThrownBy(() -> fixture.service.withdraw("1", new WithdrawalRequest("unused-password", true)))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("SOCIAL_REAUTH_REQUIRED"));
        assertThat(fixture.member.getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }

    /** 현재 회원에 묶인 소셜 재인증 증명을 소비하면 비밀번호 없이 탈퇴할 수 있다. */
    @Test
    void withdrawsSocialMemberAfterProviderReauthentication() {
        Member member = Member.createSocialMember("social@example.com");
        ReflectionTestUtils.setField(member, "id", 1L);
        Fixture fixture = fixture(member);
        byte[] hash = new byte[32];
        AuthChallenge proof = AuthChallenge.createSocialReauth(member,
                com.macstudio.backend.member.entity.IdentityProvider.GOOGLE, hash, NOW, NOW.plusSeconds(300));
        when(fixture.crypto.hash("reauth-token-value-with-exact-shape-1234567")).thenReturn(hash);
        when(fixture.challenges.findByProofTokenHashForUpdate(hash)).thenReturn(Optional.of(proof));

        fixture.service.withdraw("1", new WithdrawalRequest(null, true),
                "reauth-token-value-with-exact-shape-1234567");

        assertThat(member.getStatus()).isEqualTo(MemberStatus.WITHDRAWN);
        assertThat(proof.getStatus()).isEqualTo(com.macstudio.backend.auth.entity.AuthChallengeStatus.CONSUMED);
    }

    /** UI 외 호출에서도 동일 이메일 재가입 불가 확인값을 서비스가 다시 검사한다. */
    @Test
    void requiresEmailReuseAcknowledgement() {
        Fixture fixture = fixture(Member.createEmailMember("member@example.com", fixturePassword()));

        assertThatThrownBy(() -> fixture.service.withdraw("1", new WithdrawalRequest(PASSWORD, false)))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("WITHDRAWAL_ACK_REQUIRED"));
        assertThat(fixture.member.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        verify(fixture.members, never()).findByIdForUpdate(any());
    }

    private static String fixturePassword() {
        return new BCryptPasswordEncoder().encode(PASSWORD);
    }

    private Fixture fixture(Member member) {
        MemberRepository members = mock(MemberRepository.class);
        MemberConsentRepository consents = mock(MemberConsentRepository.class);
        AuthChallengeRepository challenges = mock(AuthChallengeRepository.class);
        RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
        AuthCrypto crypto = mock(AuthCrypto.class);
        TokenService tokens = mock(TokenService.class);
        AuthPolicy policy = mock(AuthPolicy.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(crypto.token()).thenReturn("dummy-password-hash-input");
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(members.findByIdForUpdate(1L)).thenReturn(Optional.of(member));
        var service = new AccountAuthService(members, consents, challenges, refreshTokens,
                new BCryptPasswordEncoder(), crypto, tokens, policy, Clock.fixed(NOW, ZoneOffset.UTC), manager);
        return new Fixture(service, member, members, challenges, refreshTokens, crypto);
    }

    private record Fixture(AccountAuthService service, Member member, MemberRepository members,
                           AuthChallengeRepository challenges, RefreshTokenRepository refreshTokens,
                           AuthCrypto crypto) {}
}
