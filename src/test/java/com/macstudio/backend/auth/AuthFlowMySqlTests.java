package com.macstudio.backend.auth;

import com.macstudio.backend.auth.service.*;
import com.macstudio.backend.global.exception.ApiException;
import com.macstudio.backend.member.repository.MemberRepository;
import com.macstudio.backend.support.LocalMySqlTestSupport;
import jakarta.servlet.http.Cookie;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** 메일만 대체하고 HTTP → 서비스 → JWT/로컬 MySQL 전체 흐름을 검증한다. */
@TestPropertySource(properties = {
        "app.auth.hmac-secret-base64=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
        "app.auth.policy.secure-cookie=false"
})
class AuthFlowMySqlTests extends LocalMySqlTestSupport {
    @Autowired WebApplicationContext context;
    @Autowired MemberRepository members;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired AccountAuthService accounts;
    @MockitoBean VerificationMailSender mail;
    private MockMvc mvc;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Set<String> ownedEmails = new HashSet<>();
    private final Map<String, String> codes = new ConcurrentHashMap<>();
    private String remote;
    private static final String PASSWORD = "Test-password-493!";

    /** 네트워크 SMTP 없이 실제 서비스가 생성한 코드만 테스트 메모리에 받는다. */
    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        remote = "test-" + UUID.randomUUID();
        doAnswer(call -> { codes.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(mail).send(anyString(), anyString(), anyLong());
    }

    /** 테스트가 등록한 UUID 이메일의 인증·회원만 정리한다. 기존 데이터/스키마는 변경하지 않는다. */
    @AfterEach
    void cleanup() {
        var tx = new TransactionTemplate(manager);
        for (String email : ownedEmails) {
            tx.executeWithoutResult(s -> {
                jdbc.update("DELETE FROM auth_challenge WHERE target_email = ?", email);
                members.findByEmail(email).ifPresent(member -> members.deleteById(member.getId()));
            });
        }
    }

    /** 코드 오입력 커밋·가입·BCrypt 저장·JWT /me·재로그인·로그아웃까지 확인한다. */
    @Test
    void emailSignupLoginMeAndLogout() throws Exception {
        String email = email();
        var sent = postJson("/email/send-code", Map.of("email", email.toUpperCase(Locale.ROOT), "purpose", "signup"));
        assertThat(sent.getResponse().getStatus()).isEqualTo(200);
        String id = body(sent).get("challengeId").asText();
        var wrong = postJson("/email/verify", Map.of("challengeId", id, "code", "wrong-code"));
        assertThat(wrong.getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM auth_challenge WHERE challenge_id = ?", Integer.class, id)).isEqualTo(1);
        var verified = postJson("/email/verify", Map.of("challengeId", id, "code", codes.get(email)));
        assertThat(verified.getResponse().getStatus()).isEqualTo(200);
        String proof = body(verified).get("verificationToken").asText();
        var signup = postJson("/signup", signupRequest(email, proof));
        assertThat(signup.getResponse().getStatus()).isEqualTo(201);
        String access = body(signup).get("accessToken").asText();
        assertThat(body(signup).has("refreshToken")).isFalse();
        assertThat(body(signup).get("member").has("passwordHash")).isFalse();
        var member = members.findByEmail(email).orElseThrow();
        assertThat(member.getPasswordHash().startsWith("$2")).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_consent WHERE member_id = ?", Integer.class, member.getId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM auth_challenge WHERE challenge_id = ?", String.class, id)).isEqualTo("CONSUMED");
        var me = mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + access)).andReturn();
        assertThat(me.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(me).get("id").asText()).isEqualTo(member.getId().toString());
        assertThat(postJson("/signup", signupRequest(email, proof)).getResponse().getStatus()).isEqualTo(400);
        var login = postJson("/login", Map.of("email", email, "password", PASSWORD));
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        Cookie refresh = cookie(login);
        assertThat(postCookie("/logout", refresh).getResponse().getStatus()).isEqualTo(204);
        assertThat(postCookie("/logout", refresh).getResponse().getStatus()).isEqualTo(204);
        assertThat(postCookie("/refresh", refresh).getResponse().getStatus()).isEqualTo(401);
    }

    /** 필수 약관 오류는 증명을 소비하지 않으며 유효한 요청으로 다시 가입할 수 있다. */
    @Test
    void invalidTermsDoNotConsumeProof() throws Exception {
        String email = email(); String proof = verify(email);
        var request = new HashMap<String, Object>(signupRequest(email, proof));
        request.put("agreedTerms", List.of(Map.of("type", "TERMS", "version", "v1")));
        assertThat(postJson("/signup", request).getResponse().getStatus()).isEqualTo(400);
        assertThat(members.existsByEmail(email)).isFalse();
        assertThat(postJson("/signup", signupRequest(email, proof)).getResponse().getStatus()).isEqualTo(201);
    }

    /** 발송 실패 시 해당 인증은 취소되고 재발송 제한도 유지된다. */
    @Test
    void failedMailCancelsChallengeAndCooldownRemains() throws Exception {
        String email = email();
        doThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MAIL_SEND_FAILED", "발송 실패"))
                .when(mail).send(eq(email), anyString(), anyLong());
        assertThat(postJson("/email/send-code", Map.of("email", email, "purpose", "signup")).getResponse().getStatus()).isEqualTo(503);
        assertThat(jdbc.queryForObject("SELECT status FROM auth_challenge WHERE target_email = ?", String.class, email)).isEqualTo("CANCELLED");
        assertThat(postJson("/email/send-code", Map.of("email", email, "purpose", "signup")).getResponse().getStatus()).isEqualTo(429);
    }

    /** 로그인 실패 사유를 통일하고 삭제 중 회원의 JWT 조회도 거절한다. */
    @Test
    void hidesLoginFailureAndRejectsDeletingMember() throws Exception {
        String email = email();
        var signup = postJson("/signup", signupRequest(email, verify(email)));
        assertThat(signup.getResponse().getStatus()).isEqualTo(201);
        for (String target : List.of(email, email())) {
            var failed = postJson("/login", Map.of("email", target, "password", "wrong-password"));
            assertThat(failed.getResponse().getStatus()).isEqualTo(401);
            assertThat(body(failed).get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        }
        new TransactionTemplate(manager).executeWithoutResult(s -> members.findByEmail(email).orElseThrow().beginDeletion());
        assertThat(postJson("/login", Map.of("email", email, "password", PASSWORD)).getResponse().getStatus()).isEqualTo(401);
        var me = mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + body(signup).get("accessToken").asText())).andReturn();
        assertThat(me.getResponse().getStatus()).isEqualTo(401);
    }

    /** 폐기된 Refresh 재사용은 해당 family만 폐기하고 다른 로그인은 유지한다. */
    @Test
    void rotationReplayRevokesOnlyItsFamily() throws Exception {
        String email = email();
        var signup = postJson("/signup", signupRequest(email, verify(email)));
        assertThat(signup.getResponse().getStatus()).isEqualTo(201);
        Cookie initial = cookie(signup);
        var login = postJson("/login", Map.of("email", email, "password", PASSWORD));
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        var rotated = postCookie("/refresh", initial);
        assertThat(rotated.getResponse().getStatus()).isEqualTo(200);
        assertThat(postCookie("/refresh", initial).getResponse().getStatus()).isEqualTo(401);
        assertThat(postCookie("/refresh", cookie(rotated)).getResponse().getStatus()).isEqualTo(401);
        assertThat(postCookie("/refresh", cookie(login)).getResponse().getStatus()).isEqualTo(200);
    }

    /** 같은 Refresh를 동시에 쓰면 한 요청만 성공하고 재사용 탐지 후 새 토큰도 폐기된다. */
    @Test
    void concurrentRefreshCannotLeaveUsableRotatedToken() throws Exception {
        String email = email();
        var signup = postJson("/signup", signupRequest(email, verify(email)));
        assertThat(signup.getResponse().getStatus()).isEqualTo(201);
        String raw = cookie(signup).getValue();
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        Callable<TokenService.IssuedTokens> action = () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("시작 대기 초과");
            try { return accounts.refresh(raw); }
            catch (ApiException e) { assertThat(e.code()).isEqualTo("INVALID_REFRESH_TOKEN"); return null; }
        };
        try {
            var first = pool.submit(action); var second = pool.submit(action);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            var a = first.get(20, TimeUnit.SECONDS); var b = second.get(20, TimeUnit.SECONDS);
            assertThat((a == null) ^ (b == null)).isTrue();
            String winner = a != null ? a.refreshToken() : b.refreshToken();
            assertThatThrownBy(() -> accounts.refresh(winner)).isInstanceOf(ApiException.class);
        } finally { start.countDown(); pool.shutdownNow(); pool.awaitTermination(20, TimeUnit.SECONDS); }
    }

    /** 탈퇴는 이메일을 보존한 soft delete이며 모든 로그인과 동일 이메일 재가입을 차단한다. */
    @Test
    void withdrawalSoftDeletesMemberAndBlocksEmailReuse() throws Exception {
        String email = email();
        var signup = postJson("/signup", signupRequest(email, verify(email)));
        assertThat(signup.getResponse().getStatus()).isEqualTo(201);
        String access = body(signup).get("accessToken").asText();
        Cookie firstLogin = cookie(signup);
        Cookie secondLogin = cookie(postJson("/login", Map.of("email", email, "password", PASSWORD)));

        var withdrawn = postAuthorizedJson("/withdraw", access, Map.of(
                "password", PASSWORD,
                "acknowledgeEmailReuseBlocked", true));
        assertThat(withdrawn.getResponse().getStatus()).isEqualTo(204);
        assertThat(withdrawn.getResponse().getHeader("Set-Cookie")).contains("Max-Age=0");
        var member = members.findByEmail(email).orElseThrow();
        assertThat(member.getStatus().name()).isEqualTo("WITHDRAWN");
        assertThat(member.getWithdrawnAt()).isNotNull();
        assertThat(member.getEmail()).isEqualTo(email);

        assertThat(postCookie("/refresh", firstLogin).getResponse().getStatus()).isEqualTo(401);
        assertThat(postCookie("/refresh", secondLogin).getResponse().getStatus()).isEqualTo(401);
        assertThat(postJson("/login", Map.of("email", email, "password", PASSWORD)).getResponse().getStatus()).isEqualTo(401);
        assertThat(postJson("/email/send-code", Map.of("email", email, "purpose", "signup"))
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + access))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    /** 실제 MVC/보안 체인에서 Origin 누락·잘못된 메서드·형식을 올바른 상태로 반환한다. */
    @Test
    void rejectsInvalidHttpRequestsWithoutServerError() throws Exception {
        assertThat(mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{}")).andReturn().getResponse().getStatus()).isEqualTo(403);
        var response = postJson("/login", Map.of("email", "invalid", "password", "secret"));
        assertThat(response.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(response).get("code").asText()).isEqualTo("INVALID_REQUEST");
        String email = email(); var signup = postJson("/signup", signupRequest(email, verify(email)));
        assertThat(signup.getResponse().getStatus()).isEqualTo(201);
        String access = body(signup).get("accessToken").asText();
        assertThat(mvc.perform(get("/api/v1/auth/login").header("Authorization", "Bearer " + access)).andReturn().getResponse().getStatus()).isEqualTo(405);
        assertThat(mvc.perform(get("/api/v1/no-such-route").header("Authorization", "Bearer " + access)).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    /** 테스트 소유임이 명확한 임의 이메일을 등록한다. */
    private String email() { String email = "it-" + UUID.randomUUID() + "@example.invalid"; ownedEmails.add(email); return email; }
    /** 실제 인증 서비스가 만든 코드를 대체 메일에서 읽어 가입 증명을 얻는다. */
    private String verify(String email) throws Exception {
        var sent = postJson("/email/send-code", Map.of("email", email, "purpose", "signup"));
        assertThat(sent.getResponse().getStatus()).isEqualTo(200);
        var verified = postJson("/email/verify", Map.of("challengeId", body(sent).get("challengeId").asText(), "code", codes.get(email)));
        assertThat(verified.getResponse().getStatus()).isEqualTo(200);
        return body(verified).get("verificationToken").asText();
    }
    /** 현재 개발용 약관 버전과 함께 가입 요청을 만든다. */
    private Map<String, Object> signupRequest(String email, String proof) {
        return Map.of("signupType", "EMAIL", "email", email, "password", PASSWORD, "verificationToken", proof,
                "agreedTerms", List.of(Map.of("type", "TERMS", "version", "v1"), Map.of("type", "PRIVACY", "version", "v1")));
    }
    /** Origin·사용자 지정 헤더를 포함한 JSON 요청을 전송한다. */
    private MvcResult postJson(String path, Object value) throws Exception {
        return mvc.perform(post("/api/v1/auth" + path).with(r -> { r.setRemoteAddr(remote); return r; })
                .header("Origin", "http://localhost:3000").header("X-Requested-With", "MAC")
                .contentType("application/json").content(json.writeValueAsString(value))).andReturn();
    }
    /** Refresh 원문을 JSON에 넣지 않고 쿠키로만 전달한다. */
    private MvcResult postCookie(String path, Cookie cookie) throws Exception {
        return mvc.perform(post("/api/v1/auth" + path).cookie(cookie).with(r -> { r.setRemoteAddr(remote); return r; })
                .header("Origin", "http://localhost:3000").header("X-Requested-With", "MAC")).andReturn();
    }
    /** Bearer JWT와 인증 POST 방어 헤더를 함께 전송한다. */
    private MvcResult postAuthorizedJson(String path, String access, Object value) throws Exception {
        return mvc.perform(post("/api/v1/auth" + path).with(r -> { r.setRemoteAddr(remote); return r; })
                .header("Authorization", "Bearer " + access)
                .header("Origin", "http://localhost:3000").header("X-Requested-With", "MAC")
                .contentType("application/json").content(json.writeValueAsString(value))).andReturn();
    }
    /** 응답을 메모리에서만 분석하며 원문을 출력하지 않는다. */
    private JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()); }
    /** 쿠키 보안 속성을 검증하고 다음 요청용 쿠키를 추출한다. */
    private Cookie cookie(MvcResult result) {
        String header = result.getResponse().getHeader("Set-Cookie");
        assertThat(header != null && header.contains("HttpOnly") && header.contains("SameSite=Lax") && header.contains("Path=/api/v1/auth")).isTrue();
        return new Cookie("mac_refresh", header.substring("mac_refresh=".length(), header.indexOf(';')));
    }
}
