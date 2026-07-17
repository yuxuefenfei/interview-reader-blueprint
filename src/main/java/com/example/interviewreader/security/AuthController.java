package com.example.interviewreader.security;

import com.example.interviewreader.common.ApiException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    static final String SESSION_COOKIE = "IR_SESSION";

    private final AuthProperties properties;
    private final AuthSessionService sessionService;


    @PostMapping("/login")
    ResponseEntity<AuthSessionResponse> login(@Valid @RequestBody LoginRequest request) {
        var token = sessionService.createSession(request.username(), request.password())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "用户名或密码错误"));
        var cookie = sessionCookie(token, properties.sessionTtl());
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
    ResponseEntity<AuthSessionResponse> logout(HttpServletRequest request) {
        sessionToken(request).ifPresent(sessionService::destroySession);
        var cookie = sessionCookie("", java.time.Duration.ZERO);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .cacheControl(CacheControl.noStore())
                .body(new AuthSessionResponse(false, null));
    }

    private ResponseCookie sessionCookie(String value, java.time.Duration maxAge) {
        return ResponseCookie.from(SESSION_COOKIE, value)
                .httpOnly(true)
                .secure(properties.secureCookie())
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
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