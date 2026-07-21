package com.example.interviewreader.management;

import com.example.interviewreader.persistence.DocumentDeletionPersistence;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.event.ApplicationReadyEvent;

@Component
public class DocumentDeletionWorker {
    private final boolean enabled;
    private final Semaphore permits;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<UUID, Future<?>> futures = new ConcurrentHashMap<>();
    private final DocumentDeletionProcessor processor;
    private final DocumentDeletionPersistence deletionPersistence;

    public DocumentDeletionWorker(DocumentDeletionProperties properties, DocumentDeletionProcessor processor,
                                  DocumentDeletionPersistence deletionPersistence, MeterRegistry meterRegistry) {
        this.enabled = properties.workerEnabled();
        this.permits = new Semaphore(properties.maxConcurrency());
        this.processor = processor;
        this.deletionPersistence = deletionPersistence;
        Gauge.builder("interview.reader.deletion.jobs.submitted", futures, Map::size)
                .description("已提交且尚未结束的永久删除任务数")
                .register(meterRegistry);
        Gauge.builder("interview.reader.deletion.workers.available", permits, Semaphore::availablePermits)
                .description("当前可用的永久删除并发许可数")
                .register(meterRegistry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resumeDurableJobs() {
        deletionPersistence.findRecoverableJobs().forEach(job -> submit(UUID.fromString(job.getId())));
    }

    public void submit(UUID jobId) {
        if (!enabled) {
            processor.process(jobId);
            return;
        }
        futures.computeIfAbsent(jobId, id -> executor.submit(() -> {
            var acquired = false;
            try {
                permits.acquire();
                acquired = true;
                processor.process(id);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                if (acquired) permits.release();
                futures.remove(id);
            }
        }));
    }

    @PreDestroy
    void close() {
        executor.close();
    }
}
