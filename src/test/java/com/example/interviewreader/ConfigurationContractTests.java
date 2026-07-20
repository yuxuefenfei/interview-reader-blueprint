package com.example.interviewreader;

import com.example.interviewreader.config.RuntimeProfileGuard;
import com.example.interviewreader.config.UploadProperties;
import com.example.interviewreader.importpkg.ImportProperties;
import com.example.interviewreader.management.DocumentDeletionProperties;
import com.example.interviewreader.persistence.entity.*;
import com.example.interviewreader.security.AuthProperties;
import jakarta.validation.Validation;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.unit.DataSize;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigurationContractTests {
    @Test
    void enabledSecurityRequiresExplicitNonBlankCredentials() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var rateLimit = new AuthProperties.LoginRateLimit(5, Duration.ofMinutes(1), Duration.ofMinutes(5), 1000);
            var invalid = new AuthProperties(true, " ", "", Duration.ofHours(12), Duration.ofMinutes(10), true, List.of("http://localhost"), false, rateLimit);
            var valid = new AuthProperties(true, "reader-admin", "strong-password", Duration.ofHours(12), Duration.ofMinutes(10), true, List.of("http://localhost"), false, rateLimit);

            assertThat(validator.validate(invalid)).extracting("message")
                    .contains("username and password must be non-blank when security is enabled");
            assertThat(validator.validate(valid)).isEmpty();
        }
    }

    @Test
    void enabledSecurityRequiresAnAllowedBrowserOrigin() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var rateLimit = new AuthProperties.LoginRateLimit(5, Duration.ofMinutes(1), Duration.ofMinutes(5), 1000);
            var properties = new AuthProperties(true, "reader-admin", "strong-password", Duration.ofHours(12), Duration.ofMinutes(10), true, List.of(), false, rateLimit);

            assertThat(factory.getValidator().validate(properties)).extracting("message")
                    .contains("at least one allowed origin must be configured when security is enabled");
        }
    }
    @Test
    void uploadLimitMustBePositiveAndProvidesConfigurationDrivenMessage() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(new UploadProperties(DataSize.ofBytes(0)))).isNotEmpty();
            assertThat(new UploadProperties(DataSize.ofMegabytes(12)).displayMaxSize()).isEqualTo("12MB");
            assertThat(new UploadProperties(DataSize.ofKilobytes(1536)).displayMaxSize()).isEqualTo("1536KB");
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
    void persistenceEntitiesKeepFieldsPrivateAndExposeBeanAccessors() throws NoSuchMethodException {
        // 实体统一采用 JavaBean 访问器，既保护封装边界，也兼容 MyBatis-Flex 的属性映射。
        var entityTypes = List.<Class<?>>of(
                AppUserEntity.class,
                AssetEntity.class,
                BookmarkEntity.class,
                ContentBlockEntity.class,
                ContentNodeEntity.class,
                DocumentDeletionJobEntity.class,
                DocumentEntity.class,
                DocumentTagEntity.class,
                DocumentVersionEntity.class,
                ImportIssueEntity.class,
                ImportJobEntity.class,
                NoteEntity.class,
                ReadingProgressEntity.class,
                ReviewStateEntity.class,
                TagEntity.class);

        for (var entityType : entityTypes) {
            for (var field : entityType.getDeclaredFields()) {
                if (field.isSynthetic() || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                assertThat(java.lang.reflect.Modifier.isPrivate(field.getModifiers()))
                        .as("%s.%s 应为私有字段", entityType.getSimpleName(), field.getName())
                        .isTrue();
                var capitalized = Character.toUpperCase(field.getName().charAt(0)) + field.getName().substring(1);
                var getterName = field.getType() == boolean.class ? "is" + capitalized : "get" + capitalized;
                assertThat(entityType.getMethod(getterName).getReturnType()).isEqualTo(field.getType());
                assertThat(entityType.getMethod("set" + capitalized, field.getType()).getReturnType())
                        .isEqualTo(void.class);
            }
        }
    }
    @Test
    void synchronousImportIsAllowedOnlyInTheTestProfile() {
        var development = new MockEnvironment();
        development.setActiveProfiles("dev");
        development.setProperty("interview-reader.import-worker.enabled", "false");
        assertThatThrownBy(() -> new RuntimeProfileGuard(development))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Import worker must be enabled");

        var test = new MockEnvironment();
        test.setActiveProfiles("test");
        test.setProperty("interview-reader.import-worker.enabled", "false");
        new RuntimeProfileGuard(test);
    }
    @Test
    void runtimeRequiresAnExplicitSupportedProfile() {
        assertThatThrownBy(() -> new RuntimeProfileGuard(new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Exactly one supported Spring profile");

        var environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        new RuntimeProfileGuard(environment);

        var mixed = new MockEnvironment();
        mixed.setActiveProfiles("test", "prod");
        assertThatThrownBy(() -> new RuntimeProfileGuard(mixed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Exactly one supported Spring profile");

        var unknown = new MockEnvironment();
        unknown.setActiveProfiles("staging");
        assertThatThrownBy(() -> new RuntimeProfileGuard(unknown))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Exactly one supported Spring profile");
    }
}