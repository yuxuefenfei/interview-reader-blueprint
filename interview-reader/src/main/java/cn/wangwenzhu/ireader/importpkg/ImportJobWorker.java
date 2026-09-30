package cn.wangwenzhu.ireader.importpkg;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ImportJobWorker {
    @Getter
    private final boolean enabled;
    private final Semaphore permits;
    private final ExecutorService executor;
    private final Map<UUID, Future<?>> futures = new ConcurrentHashMap<>();
    private final AtomicInteger running = new AtomicInteger();

    public ImportJobWorker(ImportProperties properties, MeterRegistry meterRegistry) {
        var worker = properties.importWorker();
        this.enabled = worker.enabled();
        this.permits = new Semaphore(worker.maxConcurrency());
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        Gauge.builder("interview.reader.import.jobs.submitted", futures, Map::size)
                .description("已提交且尚未结束的导入任务数")
                .register(meterRegistry);
        Gauge.builder("interview.reader.import.workers.available", permits, Semaphore::availablePermits)
                .description("当前可用的导入并发许可数")
                .register(meterRegistry);
    }

    public void submit(UUID jobId, Runnable task) {
        if (!enabled) {
            task.run();
            return;
        }
        var future = new FutureTask<Void>(() -> {
            running.incrementAndGet();
            var acquired = false;
            try {
                permits.acquire();
                acquired = true;
                if (Thread.currentThread().isInterrupted()) return null;
                task.run();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                if (acquired) permits.release();
                running.decrementAndGet();
            }
            return null;
        }) {
            @Override
            protected void done() {
                futures.remove(jobId, this);
            }
        };
        if (futures.putIfAbsent(jobId, future) == null) executor.execute(future);
    }

    public int pendingCount() {
        return Math.max(futures.size(), running.get());
    }

    public void cancel(UUID jobId) {
        var future = futures.remove(jobId);
        if (future != null) {
            future.cancel(true);
        }
    }

    @PreDestroy
    void close() {
        executor.close();
    }
}
