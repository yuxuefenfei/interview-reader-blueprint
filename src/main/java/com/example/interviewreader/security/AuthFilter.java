package com.example.interviewreader.security;

import com.example.interviewreader.common.ApiProblemFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class AuthFilter extends OncePerRequestFilter {
    private static final Set<String> PUBLIC_HEALTH_ENDPOINTS = Set.of(
            "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness");
    private static final String CONTENT_SECURITY_POLICY = "default-src 'self'; "
            + "base-uri 'self'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; "
            + "script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; "
            + "font-src 'self' data:; connect-src 'self'";

    private final AuthProperties properties;
    private final AuthSessionService sessionService;
    private final ObjectMapper objectMapper;
    private final ApiProblemFactory problemFactory;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        applySecurityHeaders(response);
        if (properties.enabled() && requiresOriginCheck(request) && !hasAllowedOrigin(request)) {
            writeProblem(response, HttpStatus.FORBIDDEN, "ORIGIN_NOT_ALLOWED", "请求来源不受信任");
            return;
        }
        if (!properties.enabled() || isPublicRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        var authenticated = AuthController.sessionToken(request)
                .flatMap(sessionService::usernameForToken)
                .isPresent();
        if (authenticated) {
            filterChain.doFilter(request, response);
            return;
        }
        writeProblem(response, HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "请先登录");
    }

    private void applySecurityHeaders(HttpServletResponse response) {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
    }

    private boolean requiresOriginCheck(HttpServletRequest request) {
        var method = request.getMethod();
        return request.getRequestURI().startsWith("/api/")
                && !method.equals("GET")
                && !method.equals("HEAD")
                && !method.equals("OPTIONS")
                && !method.equals("TRACE");
    }

    private boolean hasAllowedOrigin(HttpServletRequest request) {
        var sourceOrigin = request.getHeader(HttpHeaders.ORIGIN);
        if (sourceOrigin == null || sourceOrigin.isBlank()) {
            sourceOrigin = originFromReferer(request.getHeader(HttpHeaders.REFERER));
        }
        var normalizedSource = normalizeOrigin(sourceOrigin);
        return normalizedSource != null && properties.allowedOrigins().stream()
                .map(this::normalizeOrigin)
                .anyMatch(normalizedSource::equals);
    }

    private String originFromReferer(String referer) {
        if (referer == null || referer.isBlank()) return null;
        try {
            var uri = URI.create(referer);
            return uri.getScheme() + "://" + uri.getAuthority();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String normalizeOrigin(String value) {
        if (value == null || value.isBlank() || "null".equals(value)) return null;
        try {
            var uri = URI.create(value.trim());
            if (uri.getScheme() == null || uri.getAuthority() == null || uri.getUserInfo() != null) return null;
            return uri.getScheme().toLowerCase(java.util.Locale.ROOT) + "://" + uri.getAuthority().toLowerCase(java.util.Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private void writeProblem(HttpServletResponse response, HttpStatus status, String code, String detail) throws IOException {
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        objectMapper.writeValue(response.getWriter(), problemFactory.create(status, code, detail));
    }

    private boolean isPublicRequest(HttpServletRequest request) {
        var path = request.getRequestURI();
        var method = request.getMethod();
        return path.equals("/")
                || path.equals("/index.html")
                || path.equals("/favicon.ico")
                || path.equals("/icon.svg")
                || path.equals("/manifest.webmanifest")
                || path.equals("/sw.js")
                || isSpaRoute(path)
                || path.startsWith("/assets/")
                || PUBLIC_HEALTH_ENDPOINTS.contains(path)
                || (path.equals("/api/auth/session") && method.equals("GET"))
                || (path.equals("/api/auth/login") && method.equals("POST"));
    }

    private boolean isSpaRoute(String path) {
        return path.equals("/reader")
                || path.startsWith("/reader/")
                || path.equals("/admin")
                || path.startsWith("/admin/");
    }
}
