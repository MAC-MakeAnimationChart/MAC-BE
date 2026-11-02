package com.macstudio.backend.member;

import com.macstudio.backend.member.entity.IdentityProvider;
import com.macstudio.backend.member.entity.Member;
import com.macstudio.backend.member.entity.MemberIdentity;
import com.macstudio.backend.member.repository.MemberIdentityRepository;
import com.macstudio.backend.member.repository.MemberRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class MemberIdentityMySqlTests extends com.macstudio.backend.support.LocalMySqlTestSupport {
    private final String prefix = "it-" + java.util.UUID.randomUUID() + "-";
    @Autowired MemberRepository members;
    @Autowired MemberIdentityRepository identities;
    @Autowired EntityManager entityManager;

    /** 식별자의 대소문자·끝 공백과 회원별 연결 조회가 MySQL에서 보존되는지 검증한다. */
    @Test
    void roundTripsAndDistinguishesCaseAndTrailingSpaces() {
        var first = save("one@example.com", prefix + "Subject");
        var second = save("two@example.com", prefix + "subject");
        var third = save("three@example.com", prefix + "Subject ");
        Long memberId = first.getMember().getId();
        entityManager.clear();

        assertThat(identities.findByProviderAndProviderSubject(IdentityProvider.GOOGLE, prefix + "Subject"))
                .get().extracting(MemberIdentity::getId).isEqualTo(first.getId());
        assertThat(identities.findByProviderAndProviderSubject(IdentityProvider.GOOGLE, prefix + "subject"))
                .get().extracting(MemberIdentity::getId).isEqualTo(second.getId());
        assertThat(identities.findByProviderAndProviderSubject(IdentityProvider.GOOGLE, prefix + "Subject "))
                .get().extracting(MemberIdentity::getId).isEqualTo(third.getId());
        assertThat(identities.findByProviderAndProviderSubject(IdentityProvider.KAKAO, prefix + "Subject"))
                .isEmpty();
        assertThat(identities.findAllByMember_Id(memberId)).hasSize(1);
        assertThat(identities.existsByMember_IdAndProvider(memberId, IdentityProvider.GOOGLE)).isTrue();
        assertThat(identities.existsByMember_IdAndProvider(memberId, IdentityProvider.NAVER)).isFalse();
        assertThat(first.getCreatedAt()).isNotNull();
    }

    /** 하나의 제공사 계정이 다른 회원에게 중복 연결되는 것을 DB가 차단하는지 확인한다. */
    @Test
    void rejectsSameProviderIdentityOnAnotherMember() {
        save("one@example.com", prefix + "same-subject");
        assertThatThrownBy(() -> save("two@example.com", prefix + "same-subject"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** 회원당 동일 제공사 연결을 하나만 허용하는 UNIQUE 제약을 확인한다. */
    @Test
    void rejectsSecondAccountFromSameProviderForMember() {
        var first = save("one@example.com", prefix + "first");
        assertThatThrownBy(() -> identities.saveAndFlush(MemberIdentity.create(
                first.getMember(), IdentityProvider.GOOGLE, prefix + "second")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** 연결 삭제는 회원을 보존하고 회원 삭제는 하위 연결을 정리하는지 확인한다. */
    @Test
    void deletingIdentityKeepsMemberAndDeletingMemberCascadesInDatabase() {
        var first = save("one@example.com", prefix + "first");
        var member = first.getMember();
        identities.delete(first);
        identities.flush();
        assertThat(members.existsById(member.getId())).isTrue();
        var replacement = identities.saveAndFlush(
                MemberIdentity.create(member, IdentityProvider.NAVER, prefix + "replacement"));
        // DB의 FK CASCADE를 검증하도록 영속성 컨텍스트의 하위 객체 참조를 먼저 비운다.
        entityManager.clear();
        members.deleteById(member.getId());
        members.flush();
        entityManager.clear();
        assertThat(identities.findById(replacement.getId())).isEmpty();
    }

    /** 롤백되는 테스트 트랜잭션에 회원과 소셜 연결을 저장한다. */
    private MemberIdentity save(String email, String subject) {
        var member = members.saveAndFlush(Member.createEmailMember(prefix + email, "test-hash"));
        return identities.saveAndFlush(MemberIdentity.create(member, IdentityProvider.GOOGLE, subject));
    }
}


