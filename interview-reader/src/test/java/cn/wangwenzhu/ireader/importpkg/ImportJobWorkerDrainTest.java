package cn.wangwenzhu.ireader.importpkg;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ImportJobWorkerDrainTest {
    @TempDir
    Path directory;

    @Test
    void cancelledTaskRemainsInDrainCountUntilItsCodeActuallyExits() throws Exception {
        var properties = new ImportProperties("v1", directory, new ImportProperties.Worker(true, 1));
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var worker = new ImportJobWorker(properties, new SimpleMeterRegistry());
        try {
            var id = UUID.randomUUID();
            worker.submit(id, () -> {
                started.countDown();
                while (release.getCount() > 0) {
                    try {
                        release.await();
                    } catch (InterruptedException ignored) { /* Simulate a converter that has not stopped yet. */ }
                }
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            worker.cancel(id);
            assertThat(worker.pendingCount()).isPositive();
            release.countDown();
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (worker.pendingCount() != 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(worker.pendingCount()).isZero();
        } finally {
            release.countDown();
            worker.close();
        }
    }
}
