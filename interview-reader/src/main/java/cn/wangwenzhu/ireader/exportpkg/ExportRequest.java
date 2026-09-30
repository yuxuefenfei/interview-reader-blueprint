package cn.wangwenzhu.ireader.exportpkg;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ExportRequest(
        @NotNull UUID documentId,
        @NotNull UUID versionId,
        @NotNull ExportFormat format
) {
}
