package com.macstudio.backend.auth.repository;

import com.macstudio.backend.auth.entity.OAuthTransaction;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OAuthTransactionRepository extends JpaRepository<OAuthTransaction, Long> {
    /** 신규 가입 증명을 직렬화하여 중복 회원 생성을 막는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OAuthTransaction o where o.signupTokenHash = :hash")
    Optional<OAuthTransaction> findBySignupTokenHashForUpdate(@Param("hash") byte[] hash);
}
