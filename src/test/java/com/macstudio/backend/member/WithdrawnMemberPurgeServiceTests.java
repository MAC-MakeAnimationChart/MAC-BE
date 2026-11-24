package com.macstudio.backend.member;

import com.macstudio.backend.auth.repository.AuthChallengeRepository;
import com.macstudio.backend.auth.repository.OAuthTransactionRepository;
import com.macstudio.backend.member.entity.Member;
import com.macstudio.backend.member.repository.MemberRepository;
import com.macstudio.backend.member.service.WithdrawnMemberPurgeService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WithdrawnMemberPurgeServiceTests {
    private static final Instant NOW = Instant.parse("2026-11-11T03:00:00Z");

    /** 보관 기한이 지난 회원과 회원 FK가 없는 동일 이메일 임시정보를 함께 파기한다. */
    @Test
    void purgesDueMemberAndUnboundEmailRecords() {
        Fixture fixture = fixture();
        Member member = withdrawnMember(1L, "member@example.com", NOW);
        when(fixture.members.findPurgeDueIds(100)).thenReturn(List.of(1L));
        when(fixture.members.findPurgeDueByIdForUpdate(1L)).thenReturn(Optional.of(member));

        var result = fixture.service.purgeDueMembers(100);

        assertThat(result).isEqualTo(new WithdrawnMemberPurgeService.PurgeResult(1, 1, 0, 0));
        verify(fixture.oauth).deleteUnboundByMemberIdentity(1L);
        verify(fixture.challenges).deleteUnboundByTargetEmail("member@example.com");
        verify(fixture.oauth).deleteUnboundByVerifiedEmail("member@example.com");
        verify(fixture.members).delete(member);
        verify(fixture.members).flush();
    }

    /** 조회 뒤 상태나 시각이 바뀐 회원은 잠금 재검사에서 건너뛴다. */
    @Test
    void skipsMemberThatIsNoLongerDueWhenLocked() {
        Fixture fixture = fixture();
        Member member = withdrawnMember(1L, "member@example.com", NOW.plusSeconds(1));
        when(fixture.members.findPurgeDueIds(10)).thenReturn(List.of(1L));
        when(fixture.members.findPurgeDueByIdForUpdate(1L)).thenReturn(Optional.empty());

        var result = fixture.service.purgeDueMembers(10);

        assertThat(result).isEqualTo(new WithdrawnMemberPurgeService.PurgeResult(1, 0, 1, 0));
        verify(fixture.members, never()).delete(any());
        verifyNoInteractions(fixture.challenges, fixture.oauth);
    }

    /** 한 회원의 DB 오류가 뒤 회원 파기를 막지 않고 실패 건수로 격리된다. */
    @Test
    void continuesAfterOneMemberFails() {
        Fixture fixture = fixture();
        Member second = withdrawnMember(2L, null, NOW.minusSeconds(1));
        when(fixture.members.findPurgeDueIds(10)).thenReturn(List.of(1L, 2L));
        when(fixture.members.findPurgeDueByIdForUpdate(1L)).thenThrow(new IllegalStateException("db failure"));
        when(fixture.members.findPurgeDueByIdForUpdate(2L)).thenReturn(Optional.of(second));

        var result = fixture.service.purgeDueMembers(10);

        assertThat(result).isEqualTo(new WithdrawnMemberPurgeService.PurgeResult(2, 1, 0, 1));
        verify(fixture.oauth).deleteUnboundByMemberIdentity(2L);
        verify(fixture.members).delete(second);
        verifyNoInteractions(fixture.challenges);
    }

    @Test
    void rejectsUnsafeBatchSizes() {
        Fixture fixture = fixture();
        assertThatThrownBy(() -> fixture.service.purgeDueMembers(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.service.purgeDueMembers(1001))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Member withdrawnMember(Long id, String email, Instant purgeDueAt) {
        Member member = email == null
                ? Member.createSocialMember(null)
                : Member.createEmailMember(email, "encoded-password-hash");
        ReflectionTestUtils.setField(member, "id", id);
        member.beginDeletion();
        member.completeWithdrawal(NOW.minusSeconds(1));
        ReflectionTestUtils.setField(member, "purgeDueAt",
                LocalDateTime.ofInstant(purgeDueAt, ZoneOffset.UTC));
        return member;
    }

    private static Fixture fixture() {
        MemberRepository members = mock(MemberRepository.class);
        AuthChallengeRepository challenges = mock(AuthChallengeRepository.class);
        OAuthTransactionRepository oauth = mock(OAuthTransactionRepository.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        var service = new WithdrawnMemberPurgeService(members, challenges, oauth, manager);
        return new Fixture(service, members, challenges, oauth);
    }

    private record Fixture(WithdrawnMemberPurgeService service, MemberRepository members,
                           AuthChallengeRepository challenges, OAuthTransactionRepository oauth) {}
}
