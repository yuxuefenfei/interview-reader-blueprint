package com.example.interviewreader.upgrade;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MaintenanceGate {
    private final Path marker;
    private final AtomicInteger activeRequests = new AtomicInteger();

    public MaintenanceGate(@Value("${interview-reader.upgrade.maintenance-file:./tmp/upgrade.maintenance}") String marker) {
        this.marker = Path.of(marker).toAbsolutePath().normalize();
    }

    public Path markerPath() {
        return marker;
    }

    public boolean closed() {
        return Files.exists(marker);
    }

    public void enterWrite() {
        activeRequests.incrementAndGet();
    }

    public void leaveWrite() {
        activeRequests.decrementAndGet();
    }

    public int activeRequests() {
        return activeRequests.get();
    }
}
