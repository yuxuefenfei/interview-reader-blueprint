package com.example.interviewreader;

import com.example.interviewreader.config.RuntimeProfileGuard;
import com.example.interviewreader.importpkg.ImportProperties;
import com.example.interviewreader.security.AuthProperties;
import jakarta.validation.Validation;
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
    void runtimeRequiresAnExplicitSupportedProfile() {
        assertThatThrownBy(() -> new RuntimeProfileGuard(new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("explicit Spring profile");

        var environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        new RuntimeProfileGuard(environment);
    }
}