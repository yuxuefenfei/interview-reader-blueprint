package com.example.interviewreader.upgrade;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "management.endpoint.health.show-details=always",
        "interview-reader.upgrade.internal-token=01234567890123456789012345678901"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UpgradeHealthContractTest {
    @Autowired MockMvc mvc;

    @Test
    void readinessContainsDatabaseAndDiskAndInternalStatusRequiresToken() throws Exception {
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.diskSpace.status").value("UP"));
        mvc.perform(get("/internal/upgrade/status"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/internal/upgrade/status")
                        .header("X-Upgrade-Token", "01234567890123456789012345678901"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maintenance").isBoolean())
                .andExpect(jsonPath("$.storageDir").isString());
    }
}
