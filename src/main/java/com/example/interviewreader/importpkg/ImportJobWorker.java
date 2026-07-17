package com.example.interviewreader.importpkg;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class ImportJobWorker {
    private final boolean enabled;
    private final Semaphore permits;
    private final ExecutorService executor;
    private final Map<UUID, Future<?>> futures = new ConcurrentHashMap<>();

    public ImportJobWorker(ImportProperties properties) {
        var worker = properties.importWorker();
        this.enabled = worker.enabled();
        this.permits = new Semaphore(worker.maxConcurrency());
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void submit(UUID jobId, Runnable task) {
        if (!enabled) {
            task.run();
            return;
        }
        futures.put(jobId, executor.submit(() -> {
            var acquired = false;
            try {
                permits.acquire();
                acquired = true;
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                task.run();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                if (acquired) {
                    permits.release();
                }
                futures.remove(jobId);
            }
        }));
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
