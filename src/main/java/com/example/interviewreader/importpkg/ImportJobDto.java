package com.example.interviewreader.importpkg;

import com.example.interviewreader.document.SourceType;
import java.util.Map;
import java.util.UUID;

public record ImportJobDto(
        UUID id,
        UUID targetDocumentId,
        SourceType sourceType,
        String status,
        String currentStage,
        int progress,
        Map<String, Object> statistics,
        String errorCode,
        String errorMessage
) {
}
