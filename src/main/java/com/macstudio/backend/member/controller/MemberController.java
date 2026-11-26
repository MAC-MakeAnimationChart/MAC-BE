package com.macstudio.backend.member.controller;

import com.macstudio.backend.member.dto.MemberResponse;
import com.macstudio.backend.member.dto.MyPageResponse;
import com.macstudio.backend.member.service.MemberService;
import com.macstudio.backend.member.service.MyPageQueryService;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class MemberController {

    private final MemberService memberService;
    private final MyPageQueryService myPageQueryService;

    /** 내 정보 조회를 담당할 회원 서비스를 주입받는다. */
    public MemberController(MemberService memberService, MyPageQueryService myPageQueryService) {
        this.memberService = memberService;
        this.myPageQueryService = myPageQueryService;
    }

    /** 검증된 JWT의 subject를 사용해 현재 로그인한 회원 정보를 반환한다. */
    @GetMapping("/me")
    public MemberResponse getMe(
            @AuthenticationPrincipal Jwt jwt
    ) {
        return memberService.getMe(jwt.getSubject());
    }

    /** 마이페이지 요약과 검색된 데이터셋 폴더 한 페이지만 반환한다. */
    @GetMapping("/me/datasets")
    public MyPageResponse getMyPageDatasets(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "") String query) {
        return myPageQueryService.get(jwt.getSubject(), page, size, query);
    }
}
