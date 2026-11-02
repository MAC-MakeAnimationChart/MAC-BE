package com.macstudio.backend.auth.controller;

import com.macstudio.backend.auth.dto.*;
import com.macstudio.backend.auth.service.EmailVerificationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth/email")
public class EmailVerificationController {
    private final EmailVerificationService service;
    /** 이메일 인증의 HTTP 요청을 서비스로 연결한다. */
    public EmailVerificationController(EmailVerificationService service) { this.service = service; }
    /** 가입 인증코드 발송을 요청하고 원문 없는 결과를 반환한다. */
    @PostMapping("/send-code")
    public EmailCodeSendResponse send(@Valid @RequestBody EmailCodeSendRequest request) { return service.sendCode(request); }
    /** 인증코드를 확인하고 가입 전용 증명을 반환한다. */
    @PostMapping("/verify")
    public EmailVerifyResponse verify(@Valid @RequestBody EmailVerifyRequest request) { return service.verify(request); }
}
