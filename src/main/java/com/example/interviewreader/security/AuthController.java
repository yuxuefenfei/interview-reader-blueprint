package com.example.interviewreader.security;

import com.example.interviewreader.common.ApiException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    static final String SESSION_COOKIE = "IR_SESSION";

    private final AuthProperties properties;
    private final AuthSessionService sessionService;

    public AuthController(AuthProperties properties, AuthSessionService sessionService) {
        this.properties = properties;
        this.sessionService = sessionService;
    }

    @PostMapping("/login")
    ResponseEntity<AuthSessionResponse> login(@Valid @RequestBody LoginRequest request) {
        var token = sessionService.createSession(request.username(), request.password())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "用户名或密码错误"));
        var cookie = ResponseCookie.from(SESSION_COOKIE, token)
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(properties.sessionTtl())
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .cacheControl(CacheControl.noStore())
                .body(new AuthSessionResponse(true, properties.username()));
    }

    @GetMapping("/session")
    ResponseEntity<AuthSessionResponse> session(HttpServletRequest request) {
        var session = sessionToken(request)
                .flatMap(sessionService::usernameForToken)
                .map(username -> new AuthSessionResponse(true, username))
                .orElseGet(() -> new AuthSessionResponse(false, null));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(session);
    }

    @PostMapping("/logout")
    ResponseEntity<Map<String, Boolean>> logout(HttpServletRequest request) {
        sessionToken(request).ifPresent(sessionService::destroySession);
        var cookie = ResponseCookie.from(SESSION_COOKIE, "")
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .cacheControl(CacheControl.noStore())
                .body(Map.of("authenticated", false));
    }

    static java.util.Optional<String> sessionToken(HttpServletRequest request) {
        var cookies = request.getCookies();
        if (cookies == null) {
            return java.util.Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (SESSION_COOKIE.equals(cookie.getName())) {
                return java.util.Optional.ofNullable(cookie.getValue());
            }
        }
        return java.util.Optional.empty();
    }

    record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record AuthSessionResponse(boolean authenticated, String username) {
    }
}
