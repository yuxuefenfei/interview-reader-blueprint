package com.example.interviewreader.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthFilter extends OncePerRequestFilter {
    private final AuthProperties properties;
    private final AuthSessionService sessionService;
    private final ObjectMapper objectMapper;

    public AuthFilter(AuthProperties properties, AuthSessionService sessionService, ObjectMapper objectMapper) {
        this.properties = properties;
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
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
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        objectMapper.writeValue(response.getWriter(), Map.of("error", "请先登录"));
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
                || path.startsWith("/assets/")
                || path.startsWith("/actuator/health")
                || (path.equals("/api/auth/session") && method.equals("GET"))
                || (path.equals("/api/auth/login") && method.equals("POST"));
    }
}
