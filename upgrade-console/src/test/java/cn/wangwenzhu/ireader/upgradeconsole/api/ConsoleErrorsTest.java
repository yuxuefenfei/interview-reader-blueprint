package cn.wangwenzhu.ireader.upgradeconsole.api;

import cn.wangwenzhu.ireader.upgradeconsole.application.OperationFeedService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.server.ResponseStatusException;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(OutputCaptureExtension.class)
class ConsoleErrorsTest {
    private final StreamFailureProbe probe = new StreamFailureProbe();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(probe,
                    new OperationFeedController(mock(OperationFeedService.class)))
            .setControllerAdvice(new ConsoleErrors()).build();

    static Stream<Exception> disconnectedResponses() {
        return Stream.of(
                new IOException("Broken pipe"),
                new IOException("Connection reset by peer"),
                new SocketException("Connection reset"),
                new EOFException(),
                new ClientAbortException(new IOException("Broken pipe")),
                new ServletException("Async dispatch failed", new IOException("Broken pipe")),
                new IllegalStateException("Async dispatch failed", new IOException("Broken pipe")),
                new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Async dispatch failed",
                        new IOException("Broken pipe")));
    }

    @ParameterizedTest
    @MethodSource("disconnectedResponses")
    void disconnectedResponsePreservesHeadersAndStatusWithoutErrorLog(Exception failure, CapturedOutput output) throws Exception {
        probe.failure = failure;

        var result = mvc.perform(get("/test/transport-failure"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("X-SSE-Probe", "unchanged"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string("")).andReturn();

        assertThat(result.getResolvedException()).isSameAs(failure);
        assertThat(output).doesNotContain("Upgrade console request failed", "Failure in @ExceptionHandler");
    }

    @ParameterizedTest
    @MethodSource("disconnectedResponses")
    void committedDisconnectPreservesExistingSseBodyWithoutErrorLog(Exception failure, CapturedOutput output) throws Exception {
        probe.failure = failure;

        mvc.perform(get("/test/transport-failure").param("committed", "true"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("X-SSE-Probe", "unchanged"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(":keepalive\n\n"));

        assertThat(output).doesNotContain("Upgrade console request failed", "Failure in @ExceptionHandler");
    }

    @Test
    void disconnectedAsyncResponseDoesNotAttemptToWriteJson() throws Exception {
        var result = mvc.perform(get("/test/disconnected"))
                .andExpect(content().string("")).andReturn();
        assertThat(result.getResolvedException()).isInstanceOf(AsyncRequestNotUsableException.class);
    }

    @Test
    void uncommittedStreamFailureReturnsStatusWithoutJsonConversion(CapturedOutput output) throws Exception {
        mvc.perform(get("/test/storage-failure"))
                .andExpect(status().isInternalServerError()).andExpect(content().string(""));
        assertThat(output).contains("Upgrade console request failed", "H2 feed unavailable");
    }

    @Test
    void committedStreamFailurePreservesExistingSseBody(CapturedOutput output) throws Exception {
        mvc.perform(get("/test/committed-failure"))
                .andExpect(content().string(":keepalive\n\n"));
        assertThat(output).contains("Upgrade console request failed", "H2 feed unavailable");
    }

    @Test
    void ordinaryIoFailureStillReturnsJsonServerError(CapturedOutput output) throws Exception {
        mvc.perform(get("/test/file-failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("操作失败；请查看控制台服务端日志"));
        assertThat(output).contains("Upgrade console request failed", "Log file unavailable");
    }

    @Test
    void invalidSseCursorStillReturnsOrdinaryJsonValidationError() throws Exception {
        mvc.perform(get("/api/feed/stream").param("afterId", "-1"))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("日志游标无效"));
    }

    @RestController
    static class StreamFailureProbe {
        private Exception failure;

        @GetMapping("/test/transport-failure")
        void transportFailure(@RequestParam(defaultValue = "false") boolean committed,
                              HttpServletResponse response) throws Exception {
            response.setStatus(HttpStatus.ACCEPTED.value());
            response.setHeader("X-SSE-Probe", "unchanged");
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            if (committed) {
                response.getWriter().write(":keepalive\n\n");
                response.flushBuffer();
            }
            throw failure;
        }

        @GetMapping("/test/disconnected")
        void disconnected(HttpServletResponse response) throws IOException {
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            throw new AsyncRequestNotUsableException("Response not usable after response errors");
        }

        @GetMapping("/test/storage-failure")
        void storageFailure(HttpServletResponse response) throws IOException {
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            throw new IOException("H2 feed unavailable");
        }

        @GetMapping("/test/committed-failure")
        void committedFailure(HttpServletResponse response) throws IOException {
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            response.getWriter().write(":keepalive\n\n");
            response.flushBuffer();
            throw new IOException("H2 feed unavailable");
        }

        @GetMapping("/test/file-failure")
        void fileFailure() throws IOException {
            throw new IOException("Log file unavailable");
        }
    }
}
