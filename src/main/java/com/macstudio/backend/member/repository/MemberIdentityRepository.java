package com.macstudio.backend.member.repository;

import java.util.List;
import java.util.Optional;

import com.macstudio.backend.member.entity.IdentityProvider;
import com.macstudio.backend.member.entity.MemberIdentity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberIdentityRepository extends JpaRepository<MemberIdentity, Long> {
    /** 제공사와 검증된 고유 식별자로 기존 연결 계정을 조회한다. */
    Optional<MemberIdentity> findByProviderAndProviderSubject(
            IdentityProvider provider, String providerSubject);

    /** 회원에게 연결된 제공사 계정 목록을 조회한다. */
    List<MemberIdentity> findAllByMember_Id(Long memberId);

    /** 해당 회원이 이미 같은 제공사 계정을 연결했는지 확인한다. */
    boolean existsByMember_IdAndProvider(Long memberId, IdentityProvider provider);
}
