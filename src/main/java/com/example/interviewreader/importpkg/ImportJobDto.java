package com.example.interviewreader.importpkg;

import java.util.Map;
import java.util.UUID;

public record ImportJobDto(
        UUID id,
        UUID targetDocumentId,
        String sourceType,
        String status,
        String currentStage,
        int progress,
        Map<String, Object> statistics,
        String errorCode,
        String errorMessage
) {
}
