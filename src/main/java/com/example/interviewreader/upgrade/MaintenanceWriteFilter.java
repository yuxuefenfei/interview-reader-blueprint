package com.example.interviewreader.upgrade;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class MaintenanceWriteFilter extends OncePerRequestFilter {
    private final MaintenanceGate gate;

    public MaintenanceWriteFilter(MaintenanceGate gate) {
        this.gate = gate;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/") || isSafe(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        gate.enterWrite();
        try {
            if (gate.closed()) {
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                response.setContentType("application/problem+json;charset=UTF-8");
                response.setHeader("Cache-Control", "no-store");
                response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Service Unavailable\",\"status\":503,\"detail\":\"系统升级中，写入已暂停\"}");
                return;
            }
            chain.doFilter(request, response);
        } finally {
            gate.leaveWrite();
        }
    }

    private boolean isSafe(String method) {
        return method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS") || method.equals("TRACE");
    }
}
