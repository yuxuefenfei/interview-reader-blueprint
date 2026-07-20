package com.example.interviewreader.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/** 上传边界配置；Spring multipart 限制与接口错误提示共同使用该值。 */
@ConfigurationProperties(prefix = "interview-reader.upload")
@Validated
public record UploadProperties(@NotNull DataSize maxSize) {
    private static final long KIB = 1024L;
    private static final long MIB = KIB * KIB;

    @AssertTrue(message = "upload max size must be positive")
    public boolean hasPositiveMaxSize() {
        return maxSize == null || maxSize.toBytes() > 0;
    }

    /** 生成面向用户的稳定容量文案，避免配置调整后错误提示仍保留旧值。 */
    public String displayMaxSize() {
        var bytes = maxSize.toBytes();
        if (bytes % MIB == 0) {
            return bytes / MIB + "MB";
        }
        if (bytes % KIB == 0) {
            return bytes / KIB + "KB";
        }
        return bytes + "B";
    }
}