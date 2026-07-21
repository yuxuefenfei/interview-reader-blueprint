package com.example.interviewreader.document;

import com.example.interviewreader.importpkg.ImportJobStatus;
import com.example.interviewreader.importpkg.ImportIssueSeverity;
import com.example.interviewreader.importpkg.ImportResolution;
import com.example.interviewreader.importpkg.ImportStage;

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
