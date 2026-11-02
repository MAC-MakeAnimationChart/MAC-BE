package com.macstudio.backend.auth;

import com.macstudio.backend.auth.entity.AuthChallenge;
import com.macstudio.backend.auth.entity.AuthChallengeStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AuthChallengeTests {
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    /** 코드 수명과 증명 수명을 분리하고, 소비한 증명의 재사용을 차단한다. */
    @Test
    void verifiedProofOutlivesCodeButCanOnlyBeConsumedOnce() {
        var challenge = pending();
        challenge.markVerified(new byte[32], NOW.plusSeconds(600), NOW, 3);
        challenge.consumeSignupProof("user@example.com", NOW.plusSeconds(301));
        assertThat(challenge.getStatus()).isEqualTo(AuthChallengeStatus.CONSUMED);
        assertThatIllegalStateException().isThrownBy(() ->
                challenge.consumeSignupProof("user@example.com", NOW.plusSeconds(302)));
        challenge.cancel();
        assertThat(challenge.getStatus()).isEqualTo(AuthChallengeStatus.CONSUMED);
    }

    /** 만료 시각과 정확히 같은 요청도 거절하고 이미 검증한 코드는 다시 검증하지 않는다. */
    @Test
    void rejectsExpiryBoundaryAndRepeatedVerification() {
        var challenge = pending();
        assertThatIllegalStateException().isThrownBy(() ->
                challenge.requireCodeVerifiable(NOW.plusSeconds(300), 3));
        challenge.markVerified(new byte[32], NOW.plusSeconds(600), NOW, 3);
        assertThatIllegalStateException().isThrownBy(() ->
                challenge.markVerified(new byte[32], NOW.plusSeconds(600), NOW, 3));
        assertThatIllegalStateException().isThrownBy(() ->
                challenge.consumeSignupProof("user@example.com", NOW.plusSeconds(600)));
    }

    /** 다른 이메일·미검증·취소 증명으로 가입할 수 없는지 확인한다. */
    @Test
    void rejectsWrongTargetPendingAndCancelledProof() {
        var challenge = pending();
        assertThatIllegalStateException().isThrownBy(() ->
                challenge.consumeSignupProof("user@example.com", NOW));
        challenge.markVerified(new byte[32], NOW.plusSeconds(600), NOW, 3);
        assertThatIllegalStateException().isThrownBy(() ->
                challenge.consumeSignupProof("other@example.com", NOW));
        challenge.cancel();
        assertThatIllegalStateException().isThrownBy(() ->
                challenge.consumeSignupProof("user@example.com", NOW));
    }

    /** 마지막 오입력 시 취소하며 이후 정상 코드가 와도 검증을 허용하지 않는다. */
    @Test
    void locksOutAtAttemptLimit() {
        var challenge = pending();
        challenge.recordFailedAttempt(NOW, 2);
        assertThat(challenge.getAttemptCount()).isEqualTo(1);
        challenge.recordFailedAttempt(NOW, 2);
        assertThat(challenge.getStatus()).isEqualTo(AuthChallengeStatus.CANCELLED);
        assertThatIllegalStateException().isThrownBy(() -> challenge.requireCodeVerifiable(NOW, 2));
        assertThatIllegalStateException().isThrownBy(() -> challenge.recordFailedAttempt(NOW, 2));
        assertThat(challenge.getAttemptCount()).isEqualTo(2);
    }

    /** 입력 배열과 조회 배열의 변경으로 저장된 해시가 바뀌지 않는지 검증한다. */
    @Test
    void protectsDigestArrays() {
        byte[] hash = new byte[32];
        var challenge = AuthChallenge.createSignupEmail("user@example.com", hash, NOW, NOW.plusSeconds(300));
        hash[0] = 1;
        challenge.getCodeDigest()[0] = 2;
        assertThat(challenge.getCodeDigest()[0]).isZero();
        challenge.markVerified(hash, NOW.plusSeconds(600), NOW, 3);
        hash[0] = 3;
        challenge.getProofTokenHash()[0] = 4;
        assertThat(challenge.getProofTokenHash()[0]).isEqualTo((byte) 1);
    }

    /** 잘못된 해시·정규화되지 않은 이메일·역전 시각·잘못된 한도를 거절한다. */
    @Test
    void rejectsInvalidInputsWithoutChangingState() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                AuthChallenge.createSignupEmail(" User@example.com ", new byte[32], NOW, NOW.plusSeconds(1)));
        assertThatIllegalArgumentException().isThrownBy(() ->
                AuthChallenge.createSignupEmail("user@example.com", new byte[31], NOW, NOW.plusSeconds(1)));
        assertThatIllegalArgumentException().isThrownBy(() ->
                AuthChallenge.createSignupEmail("user@example.com", new byte[32], NOW, NOW.plusNanos(1)));
        var challenge = pending();
        assertThatIllegalArgumentException().isThrownBy(() -> challenge.requireCodeVerifiable(NOW, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> challenge.requireCodeVerifiable(NOW, 65536));
        assertThatIllegalStateException().isThrownBy(() -> challenge.requireCodeVerifiable(NOW.minusSeconds(1), 3));
        assertThatIllegalArgumentException().isThrownBy(() -> challenge.markVerified(new byte[32], NOW, NOW, 3));
        assertThat(challenge.getStatus()).isEqualTo(AuthChallengeStatus.PENDING);
        assertThat(challenge.getProofTokenHash()).isNull();
    }

    /** 운영 기본값과 무관한 테스트용 대기 인증 기록을 만든다. */
    private AuthChallenge pending() {
        return AuthChallenge.createSignupEmail("user@example.com", new byte[32], NOW, NOW.plusSeconds(300));
    }
}
