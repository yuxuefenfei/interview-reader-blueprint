package com.example.interviewreader.exportpkg;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ExportRequest(
        @NotNull UUID documentId,
        @NotNull UUID versionId,
        @NotNull ExportFormat format
) {
}
