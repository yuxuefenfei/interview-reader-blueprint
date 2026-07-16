package com.example.interviewreader.importpkg;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;

public final class DocumentBlockContent {
    private DocumentBlockContent() {
    }

    public static boolean isMeaningful(String blockType, String plainText, JsonNode payload) {
        var type = blockType == null ? "" : blockType.toLowerCase(Locale.ROOT);
        if ("divider".equals(type)) return true;
        if ("image".equals(type)) return hasText(payload, "assetKey") || hasText(payload, "src") || hasText(payload, "url");
        if ("unordered_list".equals(type) || "ordered_list".equals(type)) {
            return hasNonBlankArrayItem(payload, "items") || hasText(plainText);
        }
        if ("table".equals(type) || "table_snapshot".equals(type)) {
            return hasNonBlankArrayItem(payload, "rows") || hasNonBlankArrayItem(payload, "columns") || hasText(plainText);
        }
        return hasText(plainText) || hasText(payload, "text") || hasText(payload, "latex");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean hasText(JsonNode payload, String field) {
        return payload != null && !payload.isNull() && hasText(payload.path(field).asText(null));
    }

    private static boolean hasNonBlankArrayItem(JsonNode payload, String field) {
        if (payload == null || !payload.path(field).isArray()) return false;
        for (var item : payload.path(field)) {
            if (hasText(item.asText(null))) return true;
        }
        return false;
    }
}