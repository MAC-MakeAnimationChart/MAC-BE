package com.macstudio.backend.auth.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Clock;
import java.util.*;
import org.springframework.web.filter.OncePerRequestFilter;

/** 인증 POST에 정확한 Origin·사용자 지정 헤더를 요구하여 쿠키 CSRF를 차단한다. */
public class AuthRequestFilter extends OncePerRequestFilter {
    private final Set<String> origins;
    private final Clock clock;
    private final Map<String, Window> windows = new HashMap<>();
    private record Window(long start, int count) {}

    /** 정확한 허용 Origin과 요청 제한 시계를 받는다. */
    public AuthRequestFilter(List<String> origins, Clock clock) { this.origins = Set.copyOf(origins); this.clock = clock; }
    /** 인증 응답 캐시를 막고 Origin/헤더/요청 형식 및 인스턴스별 IP 제한을 검사한다. */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 기본 DispatcherServlet 매핑과 MockMvc 모두에서 컨텍스트 경로를 제외한 URL을 검사한다.
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/api/v1/auth/")) response.setHeader("Cache-Control", "no-store");
        if (!"POST".equals(request.getMethod()) || !path.startsWith("/api/v1/auth/")) { chain.doFilter(request, response); return; }
        String origin = request.getHeader("Origin");
        if (origin == null || !origins.contains(origin) || !"MAC".equals(request.getHeader("X-Requested-With"))) {
            reject(response, 403, "INVALID_REQUEST_ORIGIN"); return;
        }
        if (!path.endsWith("/refresh") && !path.endsWith("/logout")
                && (request.getContentType() == null || !request.getContentType().split(";", 2)[0].strip().equalsIgnoreCase("application/json"))) {
            reject(response, 415, "JSON_REQUIRED"); return;
        }
        int limit = path.endsWith("/send-code") ? 5 : 30;
        // 위조 가능한 X-Forwarded-For 대신 실제 연결 주소를 사용한다.
        if (!allow(request.getRemoteAddr() + "|" + path, limit)) { reject(response, 429, "AUTH_RATE_LIMITED"); return; }
        chain.doFilter(request, response);
    }
    /** 만료 항목을 정리하고 메모리 크기를 제한하는 1분 고정 구간 카운터를 적용한다. */
    private synchronized boolean allow(String key, int limit) {
        long now = clock.millis();
        windows.entrySet().removeIf(e -> now - e.getValue().start() >= 60_000);
        Window current = windows.get(key);
        if (current == null) {
            if (windows.size() >= 10_000) return false;
            windows.put(key, new Window(now, 1)); return true;
        }
        if (current.count() >= limit) return false;
        windows.put(key, new Window(current.start(), current.count() + 1)); return true;
    }
    /** 필터 단계 오류를 입력값 없는 고정 JSON으로 반환한다. */
    private void reject(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status); response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"요청 조건을 확인하거나 잠시 후 다시 시도해 주세요.\"}");
    }
}
