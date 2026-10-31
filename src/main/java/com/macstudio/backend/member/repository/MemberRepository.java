package com.macstudio.backend.member.repository;

import com.macstudio.backend.member.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member,Long> {

    // 이메일 로그인 시 회원 조회
    Optional<Member> findByEmail(String email);

    // 인증코드 발송 가입 시 이메일 중복 사전 검사
    boolean existsByEmail(String email);
}
