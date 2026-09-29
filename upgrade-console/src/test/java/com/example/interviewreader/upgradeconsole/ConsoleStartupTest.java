package com.example.interviewreader.upgradeconsole;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ConsoleStartupTest {
    private static final Path ROOT;
    static {
        try {
            ROOT = Files.createTempDirectory("upgrade-console-test-");
            Files.writeString(ROOT.resolve("mysql.cnf"), "[client]\n");
        } catch (java.io.IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
    @Autowired MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("upgrade.app-dir", () -> ROOT.toString());
        registry.add("upgrade.data-dir", () -> ROOT.resolve("data").toString());
        registry.add("upgrade.state-dir", () -> ROOT.resolve("state").toString());
        registry.add("upgrade.db-defaults-file", () -> ROOT.resolve("mysql.cnf").toString());
        registry.add("upgrade.db-name", () -> "interview_reader");
        registry.add("upgrade.github-token", () -> "test-token");
        registry.add("upgrade.admin-password", () -> "test-password");
        registry.add("upgrade.public-origin", () -> "https://upgrade.example.com");
        registry.add("upgrade.internal-token", () -> "01234567890123456789012345678901");
        registry.add("upgrade.main-port", () -> 29999);
    }

    @Test
    void pageAndPersistedOverviewWorkWithoutMainApplicationOrDatabase() throws Exception {
        mvc.perform(get("/api/overview")).andExpect(status().isUnauthorized());
        mvc.perform(get("/").header("Authorization", basic()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/overview").header("Authorization", basic()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.operations").isArray())
                .andExpect(jsonPath("$.health.eligible").value(false));
    }

    private String basic() {
        var source = "upgrade-admin:test-password".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return "Basic " + java.util.Base64.getEncoder().encodeToString(source);
    }
}
