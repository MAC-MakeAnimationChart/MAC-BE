package com.macstudio.backend.member.repository;

import com.macstudio.backend.member.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member,Long> {
    /** 로그인 유지·폐기 동시 요청을 회원 단위로 직렬화한다. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select m from Member m where m.id = :id")
    Optional<Member> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);

    // 이메일 로그인 시 회원 조회
    Optional<Member> findByEmail(String email);

    // 인증코드 발송 가입 시 이메일 중복 사전 검사
    boolean existsByEmail(String email);
}
