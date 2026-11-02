package com.macstudio.backend.auth.repository;

import com.macstudio.backend.auth.entity.AuthChallenge;
import com.macstudio.backend.auth.entity.AuthPurpose;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 서비스의 트랜잭션 안에서 인증 기록을 잠그고 조회한다. 엔티티를 API로 반환하지 않는다. */
public interface AuthChallengeRepository extends JpaRepository<AuthChallenge, Long> {
    /** 취소된 발송도 포함하여 재발송 대기시간의 기준 기록을 조회한다. */
    Optional<AuthChallenge> findFirstByTargetEmailAndPurposeOrderByCreatedAtDescIdDesc(String email, AuthPurpose purpose);
    /** 실패·취소 여부와 무관하게 시간당 발송 시도 횟수를 센다. */
    long countByTargetEmailAndPurposeAndCreatedAtAfter(String email, AuthPurpose purpose, java.time.LocalDateTime after);
    /** 코드 확인 요청을 직렬화하여 오입력 횟수 유실과 중복 증명 발급을 방지한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AuthChallenge c where c.id = :id")
    Optional<AuthChallenge> findByIdForUpdate(@Param("id") Long id);

    /** 해시로 가입 증명을 잠금 조회한다. 상태·이메일·만료는 엔티티에서 다시 검사한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AuthChallenge c where c.proofTokenHash = :hash")
    Optional<AuthChallenge> findByProofTokenHashForUpdate(@Param("hash") byte[] hash);

    /** 재발송 시 취소할 기록을 잠금 조회한다. 신규 발송 간 경쟁 제어는 발송 서비스의 별도 책임이다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AuthChallenge c where c.targetEmail = :email and c.purpose = :purpose "
            + "and c.status in (com.macstudio.backend.auth.entity.AuthChallengeStatus.PENDING, "
            + "com.macstudio.backend.auth.entity.AuthChallengeStatus.VERIFIED) order by c.id")
    List<AuthChallenge> findUnconsumedByEmailForUpdate(@Param("email") String normalizedEmail,
                                                      @Param("purpose") AuthPurpose purpose);
}
