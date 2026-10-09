package cn.wangwenzhu.ireader.upgradeconsole.api;

import cn.wangwenzhu.ireader.upgradeconsole.api.dto.ConsoleDtos;
import cn.wangwenzhu.ireader.upgradeconsole.application.OperationFeedService;
import cn.wangwenzhu.ireader.upgradeconsole.domain.FeedEntry;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 *
 * Same-origin Basic Auth protects both historical log pages and the live stream.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class OperationFeedController {

    private static final long HEARTBEAT_INTERVAL_NANOS = Duration.ofSeconds(15).toNanos();
    private static final long POLL_INTERVAL_MILLIS = 1_000;
    private final OperationFeedService feed;

    @GetMapping("/api/operations/{id}/feed")
    public ConsoleDtos.FeedPageResponse history(@PathVariable String id,
                                                @RequestParam(defaultValue = "0") long beforeId) throws IOException {
        if (beforeId < 0) throw new IllegalArgumentException("日志游标无效");
        return ConsoleDtos.from(feed.latest(id, beforeId));
    }

    /**
     * Replays committed H2 rows after the browser cursor before waiting for new rows.
     */
    @GetMapping(value = "/api/feed/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(defaultValue = "0") long afterId,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                             HttpServletResponse response) {
        long cursor;
        try {
            cursor = lastEventId == null || lastEventId.isBlank() ? afterId : Long.parseLong(lastEventId);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("日志游标无效", invalid);
        }
        if (cursor < 0) throw new IllegalArgumentException("日志游标无效");
        response.setHeader("X-Accel-Buffering", "no");
        var emitter = new SseEmitter(0L);
        var connected = new AtomicBoolean(true);
        emitter.onCompletion(() -> connected.set(false));
        emitter.onTimeout(() -> connected.set(false));
        emitter.onError(error -> connected.set(false));
        Thread.ofVirtual().name("upgrade-feed-sse").start(() -> publish(emitter, connected, cursor));
        return emitter;
    }

    void publish(SseEmitter emitter, AtomicBoolean connected, long cursor) {
        long position = cursor;
        long lastHeartbeat = System.nanoTime();
        try {
            while (connected.get()) {
                List<FeedEntry> entries;
                try {
                    entries = feed.after(position);
                } catch (IOException | RuntimeException storageFailure) {
                    // A persistence failure is a server error, not a client disconnect.
                    log.error("Failed to read persisted SSE feed after cursor {}", position, storageFailure);
                    finish(emitter, connected, storageFailure);
                    return;
                }
                for (var entry : entries) {
                    if (!connected.get()) return;
                    emitter.send(SseEmitter.event().id(Long.toString(entry.id())).name("entry").data(ConsoleDtos.from(entry)));
                    position = entry.id();
                }
                if (!connected.get()) return;
                if (System.nanoTime() - lastHeartbeat > HEARTBEAT_INTERVAL_NANOS) {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                    lastHeartbeat = System.nanoTime();
                }
                if (entries.size() < 100) Thread.sleep(POLL_INTERVAL_MILLIS);
            }
        } catch (IOException disconnected) {
            // Tomcat/Spring own error completion after a failed write. Completing again
            // races AsyncListener.onError and attempts to reuse an invalid AsyncContext.
            log.debug("SSE client disconnected after cursor {}", position, disconnected);
        } catch (IllegalStateException completed) {
            // Completion can win the race between the connected check and send().
            log.debug("SSE response is no longer writable after cursor {}", position, completed);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            finish(emitter, connected, null);
        } catch (RuntimeException failure) {
            log.error("SSE feed failed after cursor {}", position, failure);
            finish(emitter, connected, failure);
        } finally {
            connected.set(false);
        }
    }

    /**
     * Completes only application-initiated termination; null means normal cancellation.
     */
    private void finish(SseEmitter emitter, AtomicBoolean connected, Throwable failure) {
        if (!connected.getAndSet(false)) return;
        try {
            if (failure == null) emitter.complete();
            else emitter.completeWithError(failure);
        } catch (IllegalStateException disconnected) {
            // A container error may complete the request immediately after our check.
            log.debug("SSE request already completed", disconnected);
        }
    }
}
