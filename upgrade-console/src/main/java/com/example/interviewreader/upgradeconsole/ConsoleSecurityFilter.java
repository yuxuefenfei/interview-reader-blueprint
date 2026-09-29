package com.example.interviewreader.upgradeconsole;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Base64;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ConsoleSecurityFilter extends OncePerRequestFilter {
    private final UpgradeSettings settings;

    public ConsoleSecurityFilter(UpgradeSettings settings) { this.settings = settings; }

    @PostConstruct
    void validate() {
        if (blank(settings.adminPassword()) || blank(settings.githubToken()) || blank(settings.publicOrigin())
                || blank(settings.internalToken()) || settings.internalToken().length() < 32
                || blank(settings.dbName()) || settings.dbDefaultsFile() == null
                || !settings.dbName().matches("[a-zA-Z0-9_]+")
                || !settings.publicOrigin().startsWith("https://")
                || !Files.isRegularFile(settings.dbDefaultsFile())) {
            throw new IllegalStateException("升级控制台配置不完整：检查管理员、GitHub、数据库备份凭据、Origin 与内部令牌");
        }
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Content-Security-Policy", "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; form-action 'self'");
        if (request.getRequestURI().equals("/actuator/health") && request.getRemoteAddr().equals("127.0.0.1")) {
            chain.doFilter(request, response);
            return;
        }
        if (!authenticated(request)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate", "Basic realm=\"Interview Reader Upgrade\"");
            return;
        }
        if (!request.getMethod().equals("GET") && !request.getMethod().equals("HEAD")
                && !request.getMethod().equals("OPTIONS")
                && !settings.publicOrigin().equals(request.getHeader("Origin"))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Origin mismatch");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean authenticated(HttpServletRequest request) {
        var header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Basic ")) return false;
        try {
            var decoded = new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8);
            var colon = decoded.indexOf(':');
            if (colon < 0) return false;
            return equal(decoded.substring(0, colon), settings.adminUser())
                    && equal(decoded.substring(colon + 1), settings.adminPassword());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean equal(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
}
