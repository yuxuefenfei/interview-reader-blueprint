package com.example.interviewreader.upgradeconsole;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleSecurityFilterTest {
    @TempDir Path directory;

    @Test
    void requiresIndependentCredentialsAndTrustedOriginForWrites() throws Exception {
        var settings = new UpgradeSettings(directory, directory, directory, directory.resolve("mysql.cnf"),
                "db", directory, directory, "owner", "repo", "token", "operator", "secret",
                "https://upgrade.example.com", "01234567890123456789012345678901", 28080);
        var filter = new ConsoleSecurityFilter(settings);
        var request = new MockHttpServletRequest("POST", "/api/releases");
        var denied = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(request, denied, chain);
        assertThat(denied.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();

        request.addHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                "operator:secret".getBytes(StandardCharsets.UTF_8)));
        request.addHeader("Origin", "https://other.example.com");
        var badOrigin = new MockHttpServletResponse();
        filter.doFilter(request, badOrigin, new MockFilterChain());
        assertThat(badOrigin.getStatus()).isEqualTo(403);

        var allowed = new MockHttpServletRequest("POST", "/api/releases");
        allowed.addHeader("Authorization", request.getHeader("Authorization"));
        allowed.addHeader("Origin", settings.publicOrigin());
        var accepted = new MockFilterChain();
        filter.doFilter(allowed, new MockHttpServletResponse(), accepted);
        assertThat(accepted.getRequest()).isSameAs(allowed);
    }
}
