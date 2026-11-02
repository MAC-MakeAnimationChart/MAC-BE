package com.macstudio.backend.auth;

import com.macstudio.backend.auth.entity.*;
import com.macstudio.backend.auth.repository.AuthChallengeRepository;
import com.macstudio.backend.member.entity.Member;
import com.macstudio.backend.member.repository.MemberRepository;
import com.macstudio.backend.support.LocalMySqlTestSupport;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class AuthChallengeMySqlTests extends LocalMySqlTestSupport {
    @Autowired AuthChallengeRepository challenges;
    @Autowired MemberRepository members;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;

    /** 상태·해시·실패 횟수를 MySQL에 저장하고 다시 조회할 수 있는지 확인한다. */
    @Test
    @Transactional
    void roundTripsFailuresAndCancelsPreviousProofs() {
        String testEmail = "it-" + UUID.randomUUID() + "@example.com";
        Instant now = Instant.now();
        var challenge = challenges.saveAndFlush(pending(testEmail, now));
        Long id = challenge.getId();
        challenges.findByIdForUpdate(id).orElseThrow().recordFailedAttempt(now, 3);
        challenges.flush();
        entityManager.clear();
        var loaded = challenges.findByIdForUpdate(id).orElseThrow();
        assertThat(loaded.getAttemptCount()).isEqualTo(1);
        byte[] proof = randomHash();
        loaded.markVerified(proof, now.plusSeconds(600), now, 3);
        challenges.flush();
        entityManager.clear();
        assertThat(challenges.findByProofTokenHashForUpdate(proof)).isPresent();
        var previous = challenges.findUnconsumedByEmailForUpdate(testEmail, AuthPurpose.SIGNUP);
        assertThat(previous).hasSize(1);
        previous.forEach(AuthChallenge::cancel);
        challenges.flush();
        entityManager.clear();
        assertThat(challenges.findById(id).orElseThrow().getStatus()).isEqualTo(AuthChallengeStatus.CANCELLED);
        assertThat(challenges.findUnconsumedByEmailForUpdate(testEmail, AuthPurpose.SIGNUP)).isEmpty();
    }

    /** 동일 증명 해시를 두 인증 기록에 저장할 수 없음을 실제 UNIQUE 제약으로 확인한다. */
    @Test
    @Transactional
    void rejectsDuplicateProofHash() {
        Instant now = Instant.now();
        byte[] hash = randomHash();
        var first = pending("first@example.com", now);
        first.markVerified(hash, now.plusSeconds(600), now, 3);
        challenges.saveAndFlush(first);
        var second = pending("second@example.com", now);
        second.markVerified(hash, now.plusSeconds(600), now, 3);
        assertThatThrownBy(() -> challenges.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** 오입력 결과를 정상 커밋하면 다음 요청에서도 실패 횟수가 유지됨을 검증한다. */
    @Test
    void failureCountSurvivesCommittedRequest() {
        var tx = transaction();
        Instant now = Instant.now();
        Long id = tx.execute(s -> challenges.saveAndFlush(pending("it-" + UUID.randomUUID() + "@example.com", now)).getId());
        try {
            tx.executeWithoutResult(s -> challenges.findByIdForUpdate(id).orElseThrow().recordFailedAttempt(now, 3));
            Integer attemptCount = tx.execute(s -> challenges.findById(id).orElseThrow().getAttemptCount());
            assertThat(attemptCount).isEqualTo(1);
        } finally {
            deleteOwnedChallenge(id);
        }
    }

    /** 가입 트랜잭션이 실패하면 회원 INSERT와 증명 소비가 함께 롤백되는지 확인한다. */
    @Test
    void signupFailureRollsBackMemberAndConsumptionTogether() {
        var tx = transaction();
        Instant now = Instant.now();
        String email = UUID.randomUUID() + "@example.com";
        byte[] hash = randomHash();
        Long id = createVerified(email, hash, now);
        try {
            assertThatIllegalStateException().isThrownBy(() -> tx.executeWithoutResult(s -> {
                challenges.findByProofTokenHashForUpdate(hash).orElseThrow().consumeSignupProof(email, now);
                members.saveAndFlush(Member.createEmailMember(email, "test-hash"));
                throw new IllegalStateException("가입 실패를 재현하는 테스트 예외");
            }));
            Boolean memberExists = tx.execute(s -> members.existsByEmail(email));
            AuthChallengeStatus status = tx.execute(s -> challenges.findById(id).orElseThrow().getStatus());
            assertThat(memberExists).isFalse();
            assertThat(status).isEqualTo(AuthChallengeStatus.VERIFIED);
        } finally {
            deleteOwnedChallenge(id);
        }
    }

    /** 서로 다른 트랜잭션의 동시 소비 요청 중 한 요청만 성공하는지 확인한다. */
    @Test
    void concurrentRequestsConsumeOnlyOnce() throws Exception {
        Instant now = Instant.now();
        byte[] hash = randomHash();
        String email = UUID.randomUUID() + "@example.com";
        Long id = createVerified(email, hash, now);
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        Callable<Boolean> consume = () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("시작 대기 시간 초과");
            return transaction().execute(s -> {
                var challenge = challenges.findByProofTokenHashForUpdate(hash).orElseThrow();
                try {
                    challenge.consumeSignupProof(email, now);
                    return true;
                } catch (IllegalStateException alreadyConsumed) {
                    assertThat(challenge.getStatus()).isEqualTo(AuthChallengeStatus.CONSUMED);
                    return false;
                }
            });
        };
        try {
            var first = executor.submit(consume);
            var second = executor.submit(consume);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(java.util.List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(15, TimeUnit.SECONDS);
            deleteOwnedChallenge(id);
        }
    }

    /** 테스트가 생성한 인증 기록만 ID로 삭제하며 기존 데이터는 건드리지 않는다. */
    private void deleteOwnedChallenge(Long id) {
        transaction().executeWithoutResult(s -> challenges.deleteById(id));
    }

    /** 동시성·롤백 검증에 사용할 검증 완료 기록을 독립 트랜잭션으로 저장한다. */
    private Long createVerified(String email, byte[] hash, Instant now) {
        return transaction().execute(s -> {
            var challenge = pending(email, now);
            challenge.markVerified(hash, now.plusSeconds(600), now, 3);
            return challenges.saveAndFlush(challenge).getId();
        });
    }

    /** 실제 요청 간 커밋 경계를 재현하기 위한 트랜잭션 실행기를 만든다. */
    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    /** 운영 정책과 무관한 테스트용 가입 인증을 생성한다. */
    private AuthChallenge pending(String email, Instant now) {
        return AuthChallenge.createSignupEmail(email, new byte[32], now, now.plusSeconds(300));
    }

    /** 기존 테스트 실행 데이터와 충돌하지 않는 테스트용 32바이트 값을 만든다. */
    private byte[] randomHash() {
        byte[] hash = new byte[32];
        new java.security.SecureRandom().nextBytes(hash);
        return hash;
    }
}

