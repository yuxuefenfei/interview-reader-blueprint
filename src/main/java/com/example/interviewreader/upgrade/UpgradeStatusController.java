package com.example.interviewreader.upgrade;

import com.example.interviewreader.common.ApiException;
import com.example.interviewreader.importpkg.ImportJobWorker;
import com.example.interviewreader.management.DocumentDeletionWorker;
import jakarta.servlet.http.HttpServletRequest;
import javax.sql.DataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.io.IOException;
import org.springframework.core.io.ClassPathResource;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UpgradeStatusController {
    private final MaintenanceGate gate;
    private final ImportJobWorker imports;
    private final DocumentDeletionWorker deletions;
    private final byte[] token;
    private final MeterRegistry meters;
    private final String storageDir;
    private final String releaseCommit;
    private final DataSource dataSource;

    public UpgradeStatusController(MaintenanceGate gate, ImportJobWorker imports, DocumentDeletionWorker deletions,
                                   @Value("${interview-reader.upgrade.internal-token:}") String token, MeterRegistry meters,
                                   @Value("${interview-reader.storage-dir}") String storageDir, DataSource dataSource) {
        this.gate = gate;
        this.imports = imports;
        this.deletions = deletions;
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.meters = meters;
        this.storageDir = Path.of(storageDir).toAbsolutePath().normalize().toString();
        this.releaseCommit = releaseCommit();
        this.dataSource = dataSource;
    }

    private String releaseCommit() {
        try {
            var resource = new ClassPathResource("release-commit.txt");
            if (!resource.exists()) return "unknown";
            try (var input = resource.getInputStream()) {
                var value = new String(input.readAllBytes(), StandardCharsets.US_ASCII).trim();
                return value.matches("[a-f0-9]{40}") ? value : "unknown";
            }
        } catch (IOException exception) {
            return "unknown";
        }
    }
    private record DatabaseIdentity(String name, String serverId) {}

    private DatabaseIdentity databaseIdentity() {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT DATABASE(), @@server_uuid")) {
            if (rows.next()) return new DatabaseIdentity(rows.getString(1), rows.getString(2));
        } catch (Exception ignored) {
            // Readiness reports the database failure; the console refuses an unknown backup target.
        }
        return new DatabaseIdentity("unknown", "unknown");
    }
    @GetMapping("/internal/upgrade/status")
    public Map<String, Object> status(HttpServletRequest request,
                                      @RequestHeader(value = "X-Upgrade-Token", required = false) String suppliedToken) {
        var remote = request.getRemoteAddr();
        var provided = suppliedToken == null ? new byte[0] : suppliedToken.getBytes(StandardCharsets.UTF_8);
        if (!("127.0.0.1".equals(remote) || "::1".equals(remote)) || token.length < 32
                || !MessageDigest.isEqual(token, provided)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "请求的资源不存在。");
        }
        var requestTimers = meters.find("http.server.requests").timers();
        var requestCount = requestTimers.stream().mapToLong(t -> t.count()).sum();
        var meanMs = requestCount == 0 ? 0.0 : requestTimers.stream()
                .mapToDouble(t -> t.totalTime(TimeUnit.MILLISECONDS)).sum() / requestCount;
        var identity = databaseIdentity();
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("maintenance", gate.closed());
        result.put("maintenanceFile", gate.markerPath().toString());
        result.put("storageDir", storageDir);
        result.put("releaseCommit", releaseCommit);
        result.put("databaseName", identity.name());
        result.put("databaseServerId", identity.serverId());
        result.put("activeWrites", gate.activeRequests());
        result.put("importJobs", imports.pendingCount());
        result.put("deletionJobs", deletions.pendingCount());
        result.put("jvmUsedBytes", meters.find("jvm.memory.used").gauges().stream().mapToDouble(g -> g.value()).sum());
        result.put("dbConnectionsActive", meters.find("hikaricp.connections.active").gauges().stream().mapToDouble(g -> g.value()).sum());
        result.put("httpRequests", requestCount);
        result.put("httpMeanMs", meanMs);
        return result;
    }
}
