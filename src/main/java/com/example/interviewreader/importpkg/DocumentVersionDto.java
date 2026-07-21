package com.example.interviewreader.importpkg;

import com.example.interviewreader.document.DocumentVersionStatus;
import java.util.UUID;

public record DocumentVersionDto(
        UUID id,
        UUID documentId,
        int versionNo,
        DocumentVersionStatus status,
        String schemaVersion
) {
}
