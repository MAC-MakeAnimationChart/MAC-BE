package com.macstudio.backend.member.repository;

import com.macstudio.backend.member.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;
import org.springframework.data.repository.query.Param;

public interface MemberRepository extends JpaRepository<Member,Long> {
    /** 로그인 유지·폐기 동시 요청을 회원 단위로 직렬화한다. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select m from Member m where m.id = :id")
    Optional<Member> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);

    // 이메일 로그인 시 회원 조회
    Optional<Member> findByEmail(String email);

    /** 비밀번호 재설정과 이메일 기반 회원 상태 변경을 회원 단위로 직렬화한다. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select m from Member m where m.email = :email")
    Optional<Member> findByEmailForUpdate(@org.springframework.data.repository.query.Param("email") String email);

    // 인증코드 발송 가입 시 이메일 중복 사전 검사
    boolean existsByEmail(String email);

    /** DB 생성 컬럼의 파기 예정 시각을 기준으로 한 번에 처리할 회원 ID만 조회한다. */
    @org.springframework.data.jpa.repository.Query(value = "select member_id from member "
            + "where status = 'WITHDRAWN' and purge_due_at <= UTC_TIMESTAMP(3) "
            + "order by purge_due_at, member_id limit :batchSize", nativeQuery = true)
    List<Long> findPurgeDueIds(@Param("batchSize") int batchSize);

    /** 대상 조회 뒤 상태·기한을 DB UTC로 다시 검사하면서 회원 행을 잠근다. */
    @org.springframework.data.jpa.repository.Query(value = "select * from member "
            + "where member_id = :id and status = 'WITHDRAWN' "
            + "and purge_due_at <= UTC_TIMESTAMP(3) for update", nativeQuery = true)
    Optional<Member> findPurgeDueByIdForUpdate(@Param("id") Long id);
}
