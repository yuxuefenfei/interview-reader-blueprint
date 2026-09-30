package cn.wangwenzhu.ireader.persistence.entity;

import cn.wangwenzhu.ireader.document.SourceType;
import cn.wangwenzhu.ireader.importpkg.ImportJobStatus;
import cn.wangwenzhu.ireader.importpkg.ImportStage;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/** 文档导入任务持久化实体。 */
@Getter
@Setter
@Table("import_job")
public class ImportJobEntity {
    @Id(keyType = KeyType.None)
    private String id;
    private String ownerId;
    private String targetDocumentId;
    private SourceType sourceType;
    private String sourceObjectKey;
    private String sourceSha256;
    private String converterVersion;
    private String importFingerprint;
    private ImportJobStatus status;
    private int progress;
    private ImportStage currentStage;
    private String resultVersionId;
    private String errorCode;
    @Column(isLarge = true)
    private String errorMessage;
    @Column(isLarge = true)
    private String statistics;
    private String rawExtractionObjectKey;
    @Column(isLarge = true)
    private String rawExtractionJson;
    @Column(isLarge = true)
    private String normalizedObjectKey;
    private OffsetDateTime createdAt;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;
}
