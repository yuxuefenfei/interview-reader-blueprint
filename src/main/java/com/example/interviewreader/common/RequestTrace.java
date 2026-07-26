package com.example.interviewreader.common;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.slf4j.MDC;

/** Maintains the trace identifier shared by an HTTP response and its server logs. */
public final class RequestTrace {
    public static final String HEADER = "X-Trace-Id";
    private static final String ATTRIBUTE = RequestTrace.class.getName() + ".traceId";
    private static final String MDC_KEY = "traceId";

    private RequestTrace() {
    }

    public static String initialize(HttpServletRequest request) {
        var traceId = UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, traceId);
        MDC.put(MDC_KEY, traceId);
        return traceId;
    }

    public static String currentId() {
        var traceId = MDC.get(MDC_KEY);
        return traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
