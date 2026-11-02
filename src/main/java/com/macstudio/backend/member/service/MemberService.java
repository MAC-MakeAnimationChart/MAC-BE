package com.macstudio.backend.member.service;

import com.macstudio.backend.member.dto.MemberResponse;
import com.macstudio.backend.member.entity.Member;
import com.macstudio.backend.member.repository.MemberIdentityRepository;
import com.macstudio.backend.member.repository.MemberRepository;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class MemberService {

    private final MemberRepository memberRepository;
    private final MemberIdentityRepository memberIdentityRepository;

    /** 회원 조회와 소셜 연결 조회에 필요한 저장소를 주입받는다. */
    public MemberService(
            MemberRepository memberRepository,
            MemberIdentityRepository memberIdentityRepository
    ) {
        this.memberRepository = memberRepository;
        this.memberIdentityRepository = memberIdentityRepository;
    }

    /** 인증된 JWT의 회원 식별자로 현재 회원 정보를 조회한다. */
    public MemberResponse getMe(String subject) {
        Long memberId = parseMemberId(subject);

        Member member = memberRepository.findById(memberId)
                .orElseThrow(this::unauthorized);

        if (!member.isActive()) {
            throw unauthorized();
        }

        List<String> providers =
                memberIdentityRepository.findAllByMember_Id(memberId)
                        .stream()
                        .map(identity -> identity.getProvider().name())
                        .sorted()
                        .toList();

        return new MemberResponse(
                member.getId().toString(),
                member.getEmail(),
                member.hasPassword(),
                providers
        );
    }

    /** JWT subject를 현재 JPA 식별자 타입인 양의 Long 값으로 변환한다. */
    private Long parseMemberId(String subject) {
        if (subject == null || !subject.matches("[0-9]+")) {
            throw unauthorized();
        }

        try {
            long memberId = Long.parseLong(subject);

            if (memberId <= 0) {
                throw unauthorized();
            }

            return memberId;
        } catch (NumberFormatException exception) {
            throw unauthorized();
        }
    }

    /** 회원 없음·이용 불가·잘못된 식별자를 동일한 인증 실패로 처리한다. */
    private ResponseStatusException unauthorized() {
        return new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "현재 회원을 인증할 수 없습니다."
        );
    }
}