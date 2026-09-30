package cn.wangwenzhu.ireader.upgradeconsole.api;

import cn.wangwenzhu.ireader.upgradeconsole.api.dto.ConsoleDtos;
import cn.wangwenzhu.ireader.upgradeconsole.application.OperationFeedService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Same-origin Basic Auth protects both historical log pages and the live stream.
 */
@RestController
@RequiredArgsConstructor
public class OperationFeedController {
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
        Thread.ofVirtual().name("upgrade-feed-sse").start(() -> {
            long position = cursor;
            long lastHeartbeat = System.nanoTime();
            try {
                while (connected.get()) {
                    var entries = feed.after(position);
                    for (var entry : entries) {
                        emitter.send(SseEmitter.event().id(Long.toString(entry.id())).name("entry").data(ConsoleDtos.from(entry)));
                        position = entry.id();
                    }
                    if (System.nanoTime() - lastHeartbeat > 15_000_000_000L) {
                        emitter.send(SseEmitter.event().comment("keepalive"));
                        lastHeartbeat = System.nanoTime();
                    }
                    if (entries.size() < 100) Thread.sleep(1_000);
                }
            } catch (IOException | InterruptedException stopped) {
                connected.set(false);
                emitter.complete();
                if (stopped instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        });
        return emitter;
    }
}