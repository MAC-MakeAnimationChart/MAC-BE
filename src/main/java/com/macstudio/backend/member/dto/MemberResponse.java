package com.macstudio.backend.member.dto;

import java.util.List;

/*
* 현재 로그인한 회원의 응답 정보,
* 비밀번호 해시와 내부 인증 정보는 응답에 포함하지 않는다.*/
public record MemberResponse(
        String id,
        String email,
        boolean hasPassword,
        List<String> providers
) {

    /*제공사 목록을 복사하여 생성 후 외부에서 변경되지 않도록 한다*/
    public MemberResponse{
        providers = List.copyOf(providers);
    }

}
