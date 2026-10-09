package cn.wangwenzhu.ireader.upgradeconsole.api;

import cn.wangwenzhu.ireader.upgradeconsole.application.OperationFeedService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ConsoleErrorsTest {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new StreamFailureProbe(),
                    new OperationFeedController(mock(OperationFeedService.class)))
            .setControllerAdvice(new ConsoleErrors()).build();

    @Test
    void disconnectedAsyncResponseDoesNotAttemptToWriteJson() throws Exception {
        var result = mvc.perform(get("/test/disconnected"))
                .andExpect(content().string("")).andReturn();
        assertThat(result.getResolvedException()).isInstanceOf(AsyncRequestNotUsableException.class);
    }

    @Test
    void uncommittedStreamFailureReturnsStatusWithoutJsonConversion() throws Exception {
        mvc.perform(get("/test/storage-failure"))
                .andExpect(status().isInternalServerError()).andExpect(content().string(""));
    }

    @Test
    void committedStreamFailurePreservesExistingSseBody() throws Exception {
        mvc.perform(get("/test/committed-failure"))
                .andExpect(content().string(":keepalive\n\n"));
    }

    @Test
    void invalidSseCursorStillReturnsOrdinaryJsonValidationError() throws Exception {
        mvc.perform(get("/api/feed/stream").param("afterId", "-1"))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("日志游标无效"));
    }

    @RestController
    static class StreamFailureProbe {
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
    }
}
