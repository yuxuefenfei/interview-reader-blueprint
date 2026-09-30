package cn.wangwenzhu.ireader.upgrade;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MaintenanceWriteFilterTest {
    @TempDir Path directory;

    @Test
    void blocksUnsafeRequestsWhileMarkerExistsAndCountsOnlyInFlightWrites() throws ServletException, IOException {
        var marker = directory.resolve("maintenance");
        var gate = new MaintenanceGate(marker.toString());
        var filter = new MaintenanceWriteFilter(gate);
        var request = new MockHttpServletRequest("POST", "/api/import-jobs");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        Files.writeString(marker, "upgrade");
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(chain.getRequest()).isNull();
        assertThat(gate.activeRequests()).isZero();

        Files.delete(marker);
        var allowed = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), allowed);
        assertThat(allowed.getRequest()).isSameAs(request);
        assertThat(gate.activeRequests()).isZero();
    }
}
