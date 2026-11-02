package com.macstudio.backend.member;

import com.macstudio.backend.member.entity.IdentityProvider;
import com.macstudio.backend.member.entity.Member;
import com.macstudio.backend.member.entity.MemberIdentity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class MemberIdentityTests {
    private final Member member = Member.createEmailMember("user@example.com", "test-hash");

    /** 제공사 식별자의 대소문자와 끝 공백이 변경되지 않는지 확인한다. */
    @Test
    void preservesOpaqueSubjectWithoutNormalization() {
        var identity = MemberIdentity.create(member, IdentityProvider.GOOGLE, "AbC ");
        assertThat(identity.getProviderSubject()).isEqualTo("AbC ");
    }

    /** 필수 연결 정보 누락과 빈 식별자·길이 초과를 거절하는지 확인한다. */
    @Test
    void rejectsMissingIdentityPartsAndInvalidSubject() {
        assertThatNullPointerException().isThrownBy(() ->
                MemberIdentity.create(null, IdentityProvider.GOOGLE, "subject"));
        assertThatNullPointerException().isThrownBy(() ->
                MemberIdentity.create(member, null, "subject"));
        assertThatNullPointerException().isThrownBy(() ->
                MemberIdentity.create(member, IdentityProvider.GOOGLE, null));
        for (String subject : new String[]{"", "  ", "a".repeat(256)}) {
            assertThatIllegalArgumentException().isThrownBy(() ->
                    MemberIdentity.create(member, IdentityProvider.GOOGLE, subject));
        }
    }

    /** 보조 평면 문자를 UTF-16 길이가 아닌 MySQL 문자 수 기준으로 제한하는지 확인한다. */
    @Test
    void countsDatabaseCharactersRatherThanUtf16CodeUnits() {
        String subject = "\uD83D\uDE00".repeat(255);
        assertThat(MemberIdentity.create(member, IdentityProvider.GOOGLE, subject)
                .getProviderSubject()).isEqualTo(subject);
        assertThatIllegalArgumentException().isThrownBy(() ->
                MemberIdentity.create(member, IdentityProvider.GOOGLE, subject + "a"));
    }
}
