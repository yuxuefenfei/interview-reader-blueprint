package com.example.interviewreader.document;

import com.example.interviewreader.common.ApiException;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** 统一校验并规范化文档级标题、描述与标签。 */
public final class DocumentMetadataPolicy {
    public static final int TITLE_MAX_LENGTH = 500;
    public static final int DESCRIPTION_MAX_LENGTH = 5_000;
    public static final int TAG_MAX_COUNT = 20;
    public static final int TAG_MAX_LENGTH = 50;

    private DocumentMetadataPolicy() {
    }

    public static Normalized normalize(String title, String description, List<String> tags) {
        var normalizedTitle = title == null ? "" : title.strip();
        if (normalizedTitle.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DOCUMENT_TITLE_REQUIRED", "文档标题不能为空。");
        }
        if (normalizedTitle.length() > TITLE_MAX_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DOCUMENT_TITLE_TOO_LONG", "文档标题不能超过 500 个字符。");
        }

        var normalizedDescription = description == null ? null : description.strip();
        if (normalizedDescription != null && normalizedDescription.isEmpty()) {
            normalizedDescription = null;
        }
        if (normalizedDescription != null && normalizedDescription.length() > DESCRIPTION_MAX_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DOCUMENT_DESCRIPTION_TOO_LONG", "文档描述不能超过 5000 个字符。");
        }

        var uniqueTags = new LinkedHashMap<String, String>();
        for (var tag : tags == null ? List.<String>of() : tags) {
            var displayName = tag == null ? "" : tag.strip();
            if (displayName.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "DOCUMENT_TAG_REQUIRED", "标签不能为空。");
            }
            if (displayName.length() > TAG_MAX_LENGTH) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "DOCUMENT_TAG_TOO_LONG", "单个标签不能超过 50 个字符。");
            }
            uniqueTags.putIfAbsent(displayName.toLowerCase(Locale.ROOT), displayName);
        }
        if (uniqueTags.size() > TAG_MAX_COUNT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DOCUMENT_TAG_LIMIT_EXCEEDED", "每个文档最多设置 20 个标签。");
        }
        return new Normalized(normalizedTitle, normalizedDescription, List.copyOf(uniqueTags.values()));
    }

    public record Normalized(String title, String description, List<String> tags) {
    }
}