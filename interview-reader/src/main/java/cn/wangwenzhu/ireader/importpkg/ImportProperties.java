package cn.wangwenzhu.ireader.importpkg;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "interview-reader")
@Validated
public record ImportProperties(
        @NotBlank String converterVersion,
        @NotNull Path storageDir,
        @NotNull @Valid Worker importWorker) {
    public record Worker(boolean enabled, @Min(1) int maxConcurrency) {
    }
}