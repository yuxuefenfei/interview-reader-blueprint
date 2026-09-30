package cn.wangwenzhu.ireader.importpkg;

import cn.wangwenzhu.ireader.document.DocumentVersionStatus;

import java.util.UUID;

public record DocumentVersionDto(
        UUID id,
        UUID documentId,
        int versionNo,
        DocumentVersionStatus status,
        String schemaVersion
) {
}
