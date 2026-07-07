package com.example.interviewreader.importpkg;

import java.util.UUID;

public record DocumentVersionDto(
        UUID id,
        UUID documentId,
        int versionNo,
        String status,
        String schemaVersion
) {
}
