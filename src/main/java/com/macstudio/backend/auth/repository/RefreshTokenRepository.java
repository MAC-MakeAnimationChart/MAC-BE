package com.macstudio.backend.auth.repository;

import com.macstudio.backend.auth.entity.RefreshToken;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    /** 먼저 회원 ID만 읽어 모든 회전·폐기가 회원 잠금을 먼저 획득하게 한다. */
    @Query("select t.member.id from RefreshToken t where t.tokenHash = :hash")
    Optional<Long> findMemberIdByHash(@Param("hash") byte[] hash);
    /** 회원 잠금 획득 후 최신 토큰 상태를 잠금 조회한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :hash")
    Optional<RefreshToken> findByHashForUpdate(@Param("hash") byte[] hash);
    /** 회원 잠금 뒤 현재 읽기로 family를 조회하여 이전 스냅샷의 회전 토큰 누락을 방지한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.member.id = :memberId and t.familyId = :family and t.revokedAt is null")
    List<RefreshToken> findActiveFamily(@Param("memberId") Long memberId, @Param("family") byte[] family);

    /** 탈퇴 시 회원의 모든 로그인 family를 한 번에 잠금 조회한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.member.id = :memberId and t.revokedAt is null")
    List<RefreshToken> findAllActiveByMemberId(@Param("memberId") Long memberId);
}
