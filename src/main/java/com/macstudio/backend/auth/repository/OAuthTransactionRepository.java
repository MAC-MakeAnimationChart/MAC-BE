package com.macstudio.backend.auth.repository;

import com.macstudio.backend.auth.entity.OAuthTransaction;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

public interface OAuthTransactionRepository extends JpaRepository<OAuthTransaction, Long> {
    /** 신규 가입 증명을 직렬화하여 중복 회원 생성을 막는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OAuthTransaction o where o.signupTokenHash = :hash")
    Optional<OAuthTransaction> findBySignupTokenHashForUpdate(@Param("hash") byte[] hash);

    /** 가입 전 임시 OAuth 기록에서 파기 대상 이메일을 제거한다. */
    @Modifying
    @Query("delete from OAuthTransaction o where o.member is null and o.verifiedEmail = :email")
    int deleteUnboundByVerifiedEmail(@Param("email") String normalizedEmail);

    /** 이메일이 없는 소셜 회원도 연결 provider/subject와 같은 가입 전 임시기록을 파기한다. */
    @Modifying
    @Query(value = "delete o from oauth_transaction o "
            + "join member_identity i on i.provider = o.provider "
            + "and i.provider_subject = o.provider_subject "
            + "where o.member_id is null and i.member_id = :memberId", nativeQuery = true)
    int deleteUnboundByMemberIdentity(@Param("memberId") Long memberId);
}
