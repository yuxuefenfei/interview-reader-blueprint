package cn.wangwenzhu.ireader.management;

import cn.wangwenzhu.ireader.persistence.DocumentDeletionPersistence;
import cn.wangwenzhu.ireader.upgrade.MaintenanceGate;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class DocumentDeletionWorker {
    private final boolean enabled;
    private final Semaphore permits;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<UUID, Future<?>> futures = new ConcurrentHashMap<>();
    private final AtomicInteger running = new AtomicInteger();
    private final DocumentDeletionProcessor processor;
    private final DocumentDeletionPersistence deletionPersistence;
    private final MaintenanceGate maintenanceGate;

    public DocumentDeletionWorker(DocumentDeletionProperties properties, DocumentDeletionProcessor processor,
                                  DocumentDeletionPersistence deletionPersistence, MaintenanceGate maintenanceGate, MeterRegistry meterRegistry) {
        this.enabled = properties.workerEnabled();
        this.permits = new Semaphore(properties.maxConcurrency());
        this.processor = processor;
        this.deletionPersistence = deletionPersistence;
        this.maintenanceGate = maintenanceGate;
        Gauge.builder("interview.reader.deletion.jobs.submitted", futures, Map::size)
                .description("已提交且尚未结束的永久删除任务数")
                .register(meterRegistry);
        Gauge.builder("interview.reader.deletion.workers.available", permits, Semaphore::availablePermits)
                .description("当前可用的永久删除并发许可数")
                .register(meterRegistry);
    }

    @EventListener(ApplicationReadyEvent.class)
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 5000)
    public void resumeDurableJobs() {
        if (maintenanceGate.closed()) return;
        deletionPersistence.findRecoverableJobs().forEach(job -> submit(UUID.fromString(job.getId())));
    }

    public int pendingCount() {
        return Math.max(futures.size(), running.get());
    }

    public void submit(UUID jobId) {
        if (!enabled) {
            processor.process(jobId);
            return;
        }
        var future = new java.util.concurrent.FutureTask<Void>(() -> {
            running.incrementAndGet();
            var acquired = false;
            try {
                permits.acquire();
                acquired = true;
                processor.process(jobId);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                if (acquired) permits.release();
                running.decrementAndGet();
            }
            return null;
        }) {
            @Override protected void done() { futures.remove(jobId, this); }
        };
        if (futures.putIfAbsent(jobId, future) == null) executor.execute(future);
    }
    @PreDestroy
    void close() {
        executor.close();
    }
}
