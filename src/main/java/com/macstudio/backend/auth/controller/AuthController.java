package com.macstudio.backend.auth.controller;

import com.macstudio.backend.auth.config.AuthPolicy;
import com.macstudio.backend.auth.dto.*;
import com.macstudio.backend.auth.service.*;
import com.macstudio.backend.global.exception.ApiException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    public static final String REFRESH_COOKIE = "mac_refresh";
    public static final String SOCIAL_SIGNUP_COOKIE = "mac_social_signup";
    public static final String SOCIAL_REAUTH_COOKIE = "mac_social_reauth";
    private final AccountAuthService service;
    private final SocialAuthService social;
    private final SocialSignupService socialSignup;
    private final AuthPolicy policy;
    /** HTTP 쿠키 처리와 업무 서비스를 분리하여 연결한다. */
    public AuthController(AccountAuthService service, SocialAuthService social, SocialSignupService socialSignup,
                          AuthPolicy policy) {
        this.service = service; this.social = social; this.socialSignup = socialSignup; this.policy = policy;
    }
    /** 가입 방식에 맞는 일회성 증명을 소비하고 자동 로그인 응답을 만든다. */
    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(@Valid @RequestBody SignupRequest request,
            @CookieValue(name = SOCIAL_SIGNUP_COOKIE, required = false) String socialToken) {
        TokenService.IssuedTokens issued = "SOCIAL".equals(request.signupType())
                ? socialSignup.signup(request, socialToken) : service.signup(request);
        ResponseEntity<AuthResponse> response = authenticated(issued, HttpStatus.CREATED);
        if (!"SOCIAL".equals(request.signupType())) return response;
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders())
                .header(HttpHeaders.SET_COOKIE, cookie(SOCIAL_SIGNUP_COOKIE, "", 0))
                .body(response.getBody());
    }
    /** 제공사 code를 교환하여 기존 회원은 로그인하고 신규 신원은 약관 동의 단계로 보낸다. */
    @PostMapping("/social")
    public ResponseEntity<?> social(@Valid @RequestBody SocialAuthRequest request) {
        SocialAuthService.ExchangeResult result = social.exchange(request);
        if (result.authenticated()) return authenticated(result.tokens(), HttpStatus.OK);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.SET_COOKIE, cookie(SOCIAL_SIGNUP_COOKIE, result.signupToken(), result.signupMaxAge()))
                .cacheControl(CacheControl.noStore()).body(result.pending());
    }
    /** JWT 회원과 동일한 제공사 신원을 다시 확인하고 짧은 탈퇴 재인증 쿠키를 발급한다. */
    @PostMapping("/social/reauth")
    public ResponseEntity<Void> socialReauth(@AuthenticationPrincipal Jwt jwt,
                                              @Valid @RequestBody SocialAuthRequest request) {
        SocialAuthService.ReauthResult result = social.reauthenticate(jwt.getSubject(), request);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie(SOCIAL_REAUTH_COOKIE, result.token(), result.maxAge()))
                .cacheControl(CacheControl.noStore()).build();
    }
    /** 이메일·비밀번호 로그인 결과를 반환한다. */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return authenticated(service.login(request), HttpStatus.OK);
    }
    /** 이메일 인증 증명으로 비밀번호를 교체한다. 성공 후 사용자는 새 비밀번호로 다시 로그인한다. */
    @PostMapping("/password/reset")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody PasswordResetRequest request) {
        service.resetPassword(request);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    /** Refresh 쿠키를 회전하고 거절된 쿠키는 삭제한다. */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String refresh,
                                               HttpServletResponse response) {
        try { return authenticated(service.refresh(refresh), HttpStatus.OK); }
        catch (ApiException e) { response.addHeader(HttpHeaders.SET_COOKIE, cookie("", 0)); throw e; }
    }
    /** 현재 로그인 family를 폐기하고 동일한 경로·속성으로 쿠키를 삭제한다. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refresh) {
        service.logout(refresh);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", 0)).build();
    }
    /** 현재 비밀번호와 경고 확인을 검증하여 회원을 soft delete하고 쿠키를 삭제한다. */
    @PostMapping("/withdraw")
    public ResponseEntity<Void> withdraw(@AuthenticationPrincipal Jwt jwt,
                                         @Valid @RequestBody WithdrawalRequest request,
                                         @CookieValue(name = SOCIAL_REAUTH_COOKIE, required = false) String reauth) {
        service.withdraw(jwt.getSubject(), request, reauth);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie(REFRESH_COOKIE, "", 0))
                .header(HttpHeaders.SET_COOKIE, cookie(SOCIAL_REAUTH_COOKIE, "", 0)).build();
    }
    /** Refresh는 쿠키로, Access와 회원 정보만 JSON으로 전달한다. */
    private ResponseEntity<AuthResponse> authenticated(TokenService.IssuedTokens result, HttpStatus status) {
        return ResponseEntity.status(status).header(HttpHeaders.SET_COOKIE, cookie(REFRESH_COOKIE, result.refreshToken(), result.maxAge()))
                .cacheControl(CacheControl.noStore()).body(result.response());
    }
    /** 로컬 HTTP/운영 HTTPS에 맞는 HttpOnly·SameSite 쿠키를 만든다. Domain은 지정하지 않는다. */
    private String cookie(String value, long maxAge) {
        return cookie(REFRESH_COOKIE, value, maxAge);
    }
    /** 인증 목적별 쿠키 이름을 분리하되 동일한 보안 속성과 범위를 사용한다. */
    private String cookie(String name, String value, long maxAge) {
        return ResponseCookie.from(name, value).httpOnly(true).secure(policy.secureCookie())
                .sameSite("Lax").path("/api/v1/auth").maxAge(maxAge).build().toString();
    }
}
