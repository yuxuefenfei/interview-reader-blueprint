package cn.wangwenzhu.ireader.upgradeconsole.api;

import cn.wangwenzhu.ireader.upgradeconsole.application.OperationFeedService;
import cn.wangwenzhu.ireader.upgradeconsole.domain.FeedEntry;
import cn.wangwenzhu.ireader.upgradeconsole.domain.FeedKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OperationFeedControllerTest {
    private final OperationFeedService feed = mock(OperationFeedService.class);
    private final OperationFeedController controller = new OperationFeedController(feed);
    private final SseEmitter emitter = mock(SseEmitter.class);
    private final AtomicBoolean connected = new AtomicBoolean(true);

    static Stream<Exception> disconnectedWrites() {
        return Stream.of(new IOException("Broken pipe"), new IllegalStateException("ResponseBodyEmitter has already completed"));
    }

    @ParameterizedTest
    @MethodSource("disconnectedWrites")
    void failedWriteStopsWorkerWithoutCompletingInvalidResponse(Exception failure) throws Exception {
        when(feed.after(41)).thenReturn(List.of(new FeedEntry(42, "op", Instant.EPOCH, FeedKind.STAGE, "PRECHECKING", "检查")));
        doThrow(failure).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        assertThatCode(() -> controller.publish(emitter, connected, 41)).doesNotThrowAnyException();

        assertThat(connected).isFalse();
        verify(feed).after(41);
        verifyNoMoreInteractions(feed);
        verify(emitter, never()).complete();
        verify(emitter, never()).completeWithError(any());
    }

    @Test
    void storageFailureCompletesWithServerErrorInsteadOfLeavingStreamOpen() throws Exception {
        var failure = new IOException("H2 feed unavailable");
        when(feed.after(0)).thenThrow(failure);

        controller.publish(emitter, connected, 0);

        assertThat(connected).isFalse();
        verify(emitter).completeWithError(failure);
        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void concurrentDisconnectDuringStorageFailureDoesNotCompleteAgain() throws Exception {
        when(feed.after(0)).thenAnswer(invocation -> {
            connected.set(false);
            throw new IOException("H2 feed unavailable");
        });

        controller.publish(emitter, connected, 0);

        verifyNoInteractions(emitter);
    }

    @Test
    void disconnectDuringPageReadPreventsFurtherWrites() throws Exception {
        when(feed.after(41)).thenAnswer(invocation -> {
            connected.set(false);
            return List.of(new FeedEntry(42, "op", Instant.EPOCH, FeedKind.STAGE, "PRECHECKING", "检查"));
        });

        controller.publish(emitter, connected, 41);

        verifyNoInteractions(emitter);
    }

    @Test
    void containerCompletionRaceDuringApplicationErrorDoesNotEscapeWorker() throws Exception {
        when(feed.after(0)).thenThrow(new IOException("H2 feed unavailable"));
        doThrow(new IllegalStateException("AsyncContext invalidated")).when(emitter).completeWithError(any());

        assertThatCode(() -> controller.publish(emitter, connected, 0)).doesNotThrowAnyException();
        assertThat(connected).isFalse();
    }
}
