package cn.wangwenzhu.ireader.upgradeconsole.api;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Slf4j
@RestControllerAdvice
public class ConsoleErrors {

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void disconnected(AsyncRequestNotUsableException exception, HttpServletResponse response) {
        // Declaring ServletResponse marks the void handler as resolved. Never write
        // JSON or change headers on a response the servlet container has invalidated.
        log.debug("Async response disconnected", exception);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception,
                                                          HttpServletResponse response) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage(), response);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException exception,
                                                        HttpServletResponse response) {
        return error(HttpStatus.CONFLICT, exception.getMessage(), response);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> responseStatus(ResponseStatusException exception,
                                                              HttpServletResponse response) {
        return error(exception.getStatusCode(), exception.getReason() == null ? "请求未通过" : exception.getReason(), response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> serverError(Exception exception, HttpServletResponse response) {
        log.error("Upgrade console request failed", exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "操作失败；请查看控制台服务端日志", response);
    }

    private ResponseEntity<Map<String, String>> error(HttpStatusCode status, String message,
                                                      HttpServletResponse response) {
        // A committed stream cannot be replaced with a JSON error document.
        if (response.isCommitted()) return null;
        var contentType = response.getContentType();
        if (contentType != null && contentType.startsWith(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            response.setStatus(status.value());
            return null;
        }
        return ResponseEntity
                .status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", message));
    }
}
