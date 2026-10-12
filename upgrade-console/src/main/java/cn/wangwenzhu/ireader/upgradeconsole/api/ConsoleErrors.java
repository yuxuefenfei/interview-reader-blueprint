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
import org.springframework.web.util.DisconnectedClientHelper;

import java.util.Map;

@Slf4j
@RestControllerAdvice
public class ConsoleErrors {

    private static final DisconnectedClientHelper DISCONNECTED_CLIENTS =
            new DisconnectedClientHelper(ConsoleErrors.class.getName());

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void disconnected(AsyncRequestNotUsableException exception, HttpServletResponse response) {
        // 声明 ServletResponse 参数可将返回 void 的处理器标记为已处理；对于容器已置为无效的响应，
        // 不得写入 JSON 或修改响应头。
        log.debug("Async response disconnected", exception);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception,
                                                          HttpServletResponse response) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage(), exception, response);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException exception,
                                                        HttpServletResponse response) {
        return error(HttpStatus.CONFLICT, exception.getMessage(), exception, response);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> responseStatus(ResponseStatusException exception,
                                                              HttpServletResponse response) {
        return error(exception.getStatusCode(), exception.getReason() == null ? "请求未通过" : exception.getReason(), exception, response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> serverError(Exception exception, HttpServletResponse response) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "操作失败；请查看控制台服务端日志", exception, response);
    }

    private ResponseEntity<Map<String, String>> error(HttpStatusCode status, String message, Exception exception,
                                                      HttpServletResponse response) {
        // 异步错误分派可能暴露原始或经过包装的套接字异常。客户端一旦断开，
        // 就应保持状态码、响应头和响应体不变，仅以 DEBUG/TRACE 级别记录日志。
        if (DISCONNECTED_CLIENTS.checkAndLogClientDisconnectedException(exception)) return null;
        if (status.is5xxServerError()) log.error("Upgrade console request failed", exception);
        // 已提交的事件流响应不能替换为 JSON 错误文档。
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
