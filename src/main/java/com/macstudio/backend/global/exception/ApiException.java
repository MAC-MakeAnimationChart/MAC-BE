package com.macstudio.backend.global.exception;

import org.springframework.http.HttpStatus;

/** 응답 가능한 오류 코드만 보관하며 비밀값·원인 예외를 외부에 전달하지 않는다. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    /** HTTP 상태·안정적인 코드·사용자용 메시지로 업무 오류를 생성한다. */
    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    /** 응답 상태를 반환한다. */
    public HttpStatus status() { return status; }
    /** 클라이언트가 분기할 오류 코드를 반환한다. */
    public String code() { return code; }
}
