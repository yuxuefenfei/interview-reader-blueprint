package cn.wangwenzhu.ireader.document;

import cn.wangwenzhu.ireader.importpkg.ImportIssueSeverity;
import cn.wangwenzhu.ireader.importpkg.ImportJobStatus;
import cn.wangwenzhu.ireader.importpkg.ImportResolution;
import cn.wangwenzhu.ireader.importpkg.ImportStage;

import java.util.Set;

public final class ApiContractValues {
    public static final Set<String> SOURCE_TYPES = SourceType.codes();
    public static final Set<String> NODE_TYPES = NodeType.codes();
    public static final Set<String> BLOCK_TYPES = BlockType.codes();
    public static final Set<String> VERSION_STATUSES = DocumentVersionStatus.codes();
    public static final Set<String> IMPORT_STATUSES = ImportJobStatus.codes();
    public static final Set<String> IMPORT_ISSUE_SEVERITIES = ImportIssueSeverity.codes();

    public static final Set<String> IMPORT_RESOLUTIONS = ImportResolution.codes();
    public static final Set<String> IMPORT_STAGES = ImportStage.codes();

    public static final Set<String> SEMANTIC_ROLES = SemanticRole.codes();
    public static final Set<String> MASTERY_STATES = MasteryState.codes();

    private ApiContractValues() {
    }
}
