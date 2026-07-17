package com.example.interviewreader;

import com.example.interviewreader.config.RuntimeProfileGuard;
import com.example.interviewreader.importpkg.ImportProperties;
import com.example.interviewreader.management.DocumentDeletionProperties;
import com.example.interviewreader.security.AuthProperties;
import jakarta.validation.Validation;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigurationContractTests {
    @Test
    void enabledSecurityRequiresExplicitNonBlankCredentials() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var invalid = new AuthProperties(true, " ", "", Duration.ofHours(12), true);
            var valid = new AuthProperties(true, "reader-admin", "strong-password", Duration.ofHours(12), true);

            assertThat(validator.validate(invalid)).extracting("message")
                    .contains("username and password must be non-blank when security is enabled");
            assertThat(validator.validate(valid)).isEmpty();
        }
    }

    @Test
    void importWorkerConcurrencyMustBePositive() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var properties = new ImportProperties("converter-1", Path.of("data/imports"), new ImportProperties.Worker(true, 0));
            assertThat(factory.getValidator().validate(properties)).isNotEmpty();
        }
    }

    @Test
    void deletionWorkerContractRequiresPositiveLimitsAndDurations() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var invalid = new DocumentDeletionProperties(true, 0, 0, null, null);
            assertThat(factory.getValidator().validate(invalid)).hasSize(4);
        }
    }
    @Test
    void legacyRecordWithOnlyRetiredVersionsMigratesToOffline() {
        var url = "jdbc:h2:mem:legacy-lifecycle-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        var dataSource = new DriverManagerDataSource(url, "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/h2").target("3").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        var documentId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO document(id, owner_id, code, title, status) VALUES (?, ?, ?, ?, 'DRAFT')",
                documentId, "00000000-0000-0000-0000-000000000001", "legacy-" + documentId, "Legacy document");
        jdbc.update("INSERT INTO document_version(id, document_id, version_no, source_type, status, language, metadata) " +
                        "VALUES (?, ?, 1, 'PDF', 'RETIRED', 'zh-CN', '{}')",
                UUID.randomUUID().toString(), documentId);

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/h2").load().migrate();

        assertThat(jdbc.queryForObject("SELECT status FROM document WHERE id = ?", String.class, documentId))
                .isEqualTo("OFFLINE");
        assertThat(jdbc.queryForObject("SELECT current_version_id FROM document WHERE id = ?", String.class, documentId))
                .isNull();
    }
    @Test
    void runtimeRequiresAnExplicitSupportedProfile() {
        assertThatThrownBy(() -> new RuntimeProfileGuard(new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("explicit Spring profile");

        var environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        new RuntimeProfileGuard(environment);
    }
}