package com.example.interviewreader.importpkg;

import com.example.interviewreader.document.BlockType;
import com.fasterxml.jackson.databind.JsonNode;

public final class DocumentBlockContent {
    private DocumentBlockContent() {
    }

    public static boolean isMeaningful(BlockType blockType, String plainText, JsonNode payload) {
        if (blockType == BlockType.DIVIDER) return true;
        if (blockType == BlockType.IMAGE) return hasText(payload, "assetKey") || hasText(payload, "src") || hasText(payload, "url");
        if (blockType == BlockType.UNORDERED_LIST || blockType == BlockType.ORDERED_LIST) {
            return hasNonBlankArrayItem(payload, "items") || hasText(plainText);
        }
        if (blockType == BlockType.TABLE || blockType == BlockType.TABLE_SNAPSHOT) {
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
