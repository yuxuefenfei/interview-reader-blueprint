package com.example.interviewreader.importpkg;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** 原始文件导入时统一生成稳定、可读的文档名称与标识。 */
public final class ImportDocumentNaming {
    private static final int GENERATED_KEY_MAX_LENGTH = 80;

    private ImportDocumentNaming() {
    }

    public static String baseName(String fileName, List<String> extensions, String fallback) {
        var normalized = Objects.requireNonNullElse(fileName, "").strip();
        if (normalized.isBlank()) {
            return fallback;
        }
        var slashIndex = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'));
        var name = slashIndex >= 0 ? normalized.substring(slashIndex + 1) : normalized;
        var lowerName = name.toLowerCase(Locale.ROOT);
        for (var extension : extensions) {
            if (lowerName.endsWith(extension.toLowerCase(Locale.ROOT))) {
                name = name.substring(0, name.length() - extension.length());
                break;
            }
        }
        var cleaned = name.strip().replaceAll("\\s+", " ");
        return cleaned.isBlank() ? fallback : cleaned;
    }

    public static String slug(String value, String fallback) {
        var slug = Objects.requireNonNullElse(value, "")
                .strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "-")
                .replaceAll("(^-|-$)", "");
        if (slug.isBlank()) {
            slug = fallback;
        }
        return slug.length() <= GENERATED_KEY_MAX_LENGTH
                ? slug
                : slug.substring(0, GENERATED_KEY_MAX_LENGTH).replaceAll("-$", "");
    }
}