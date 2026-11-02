package com.macstudio.backend.member.controller;

import com.macstudio.backend.member.dto.MemberResponse;
import com.macstudio.backend.member.service.MemberService;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class MemberController {

    private final MemberService memberService;

    /** 내 정보 조회를 담당할 회원 서비스를 주입받는다. */
    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    /** 검증된 JWT의 subject를 사용해 현재 로그인한 회원 정보를 반환한다. */
    @GetMapping("/me")
    public MemberResponse getMe(
            @AuthenticationPrincipal Jwt jwt
    ) {
        return memberService.getMe(jwt.getSubject());
    }
}