export const SOURCE_TYPES = ["PDF", "EXCEL", "JSON_PACKAGE", "MARKDOWN", "MANUAL"] as const;
export type SourceType = typeof SOURCE_TYPES[number];

export const NODE_TYPES = ["PART", "CHAPTER", "SECTION", "SUBSECTION", "QUESTION", "APPENDIX", "OTHER"] as const;
export type NodeType = typeof NODE_TYPES[number];

export const BLOCK_TYPES = ["paragraph", "heading_note", "unordered_list", "ordered_list", "code", "table", "quote", "callout", "formula", "image", "divider", "table_snapshot"] as const;
export type BlockType = typeof BLOCK_TYPES[number];

export const SEMANTIC_ROLES = ["QUESTION", "ANSWER", "EXPLANATION", "CONCLUSION", "INTRODUCTION", "DIRECTORY", "PRINCIPLE", "PRACTICE", "PITFALL", "FOLLOW_UP"] as const;
export type SemanticRole = typeof SEMANTIC_ROLES[number];

export const MASTERY_STATES = ["UNKNOWN", "HARD", "FUZZY", "KNOWN"] as const;
export type MasteryState = typeof MASTERY_STATES[number];

export const VERSION_STATUSES = ["DRAFT", "PUBLISHED", "RETIRED"] as const;
export const DOCUMENT_STATUSES = ["DRAFT", "PUBLISHED", "OFFLINE", "DELETING", "DELETE_FAILED"] as const;
export type DocumentStatus = typeof DOCUMENT_STATUSES[number];
export const DELETION_JOB_STATUSES = ["QUEUED", "RUNNING", "FAILED", "COMPLETED"] as const;
export type DeletionJobStatus = typeof DELETION_JOB_STATUSES[number];
export const DELETION_STAGES = ["QUEUED", "CLIENT_SYNC_MARKED", "DELETING_FILES", "DELETING_DATA", "COMPLETED", "FAILED"] as const;
export type DeletionStage = typeof DELETION_STAGES[number];
export type VersionStatus = typeof VERSION_STATUSES[number];

export const IMPORT_STATUSES = ["UPLOADED", "PREFLIGHT", "EXTRACTING", "NORMALIZING", "VALIDATING", "READY", "REVIEW_REQUIRED", "IMPORTED", "FAILED", "CANCELED"] as const;
export type ImportStatus = typeof IMPORT_STATUSES[number];

export const IMPORT_STAGES = ["UPLOADED", "PREFLIGHT", "EXTRACTING", "NORMALIZING", "VALIDATING", "READY", "REVIEW_REQUIRED", "REVIEWING", "FAILED", "CANCELED", "COMMITTED", "DRAFT_DISCARDED"] as const;
export type ImportStage = typeof IMPORT_STAGES[number];

export const TERMINAL_IMPORT_STATUSES = new Set<ImportStatus>(["READY", "REVIEW_REQUIRED", "IMPORTED", "FAILED", "CANCELED"]);

export interface AuthSession { authenticated: boolean; username: string | null; }
export interface DocumentSummary { id: string; code: string; title: string; description: string | null; currentVersionId: string | null; progressRatio: number; }
export interface DocumentListResponse { items: DocumentSummary[]; nextCursor: string | null; }
export interface TocNode { id: string; parentId: string | null; title: string; level: number; nodeType: NodeType; semanticRole: SemanticRole | null; anchor: string; sourcePageStart: number | null; children: TocNode[]; }
export interface ContentBlock { id: string; blockKey: string; seq: number; blockType: BlockType; payload: Record<string, unknown>; plainText: string; sourcePage: number | null; sourceBbox: SourceBbox | null; confidence: number | null; }
export interface NodeContent { node: TocNode; blocks: ContentBlock[]; nextAfterSeq: number | null; }
export interface SearchHit { documentId: string; versionId: string; nodeId: string; blockId: string; title: string; sectionPath: string[]; snippet: string; score: number; }
export interface ReadingProgress { versionId: string; sectionId: string | null; blockId: string | null; charOffset: number; blockViewportOffset: number; progressRatio: number; clientUpdatedAt: string | null; deviceId: string | null; revision: number; }
export interface SourceBbox { page?: number | null; x?: number | null; y?: number | null; width?: number | null; height?: number | null; }
export interface ImportJob { id: string; targetDocumentId: string | null; sourceType: SourceType; status: ImportStatus; currentStage: ImportStage | null; progress: number; statistics: Record<string, unknown>; errorCode: string | null; errorMessage: string | null; }
export const IMPORT_RESOLUTIONS = ["CREATE_NEW", "IMPORT_AS_NEW_VERSION"] as const;
export type ImportResolution = typeof IMPORT_RESOLUTIONS[number];
export const IMPORT_ISSUE_SEVERITIES = ["BLOCKING", "WARNING"] as const;
export type ImportIssueSeverity = typeof IMPORT_ISSUE_SEVERITIES[number];
export interface ExistingDocumentMatch { id: string; code: string; title: string; status: DocumentStatus; }
export interface ImportDocumentPreview { documentKey: string; title: string; description: string | null; tags: string[]; editable: boolean; matchingDocument: ExistingDocumentMatch | null; suggestedDocumentKey: string | null; duplicateTitleCount: number; }
export interface ImportIssue { severity: ImportIssueSeverity; issueCode: string; message: string; sourcePage: number | null; sectionKey: string | null; blockKey: string | null; cellRef: string | null; }
export interface StagedSection { sectionKey: string; parentSectionKey: string | null; level: number; nodeType: NodeType; semanticRole: SemanticRole | null; title: string; sortOrder: number; anchor: string; sourcePageStart: number | null; sourcePageEnd: number | null; sourceBbox: SourceBbox | null; contentHash: string | null; }
export interface StagedBlock { blockKey: string; sectionKey: string; seq: number; blockType: BlockType; payload: Record<string, unknown>; plainText: string; language: string | null; sourcePage: number | null; sourceBbox: SourceBbox | null; confidence: number | null; contentHash: string | null; }
export interface DocumentInfo { documentKey: string; title: string; description: string | null; language: string; tags: string[]; }
export interface VersionInfo { versionKey: string; sourceType: SourceType; sourceFileName: string | null; sourceSha256: string | null; converterVersion: string | null; metadata: Record<string, unknown>; }
export interface AssetInfo { assetKey: string; path: string; mimeType: string; sha256: string; alt: string | null; }
export interface DocumentPackage { schemaVersion: string; document: DocumentInfo; version: VersionInfo; sections: StagedSection[]; blocks: StagedBlock[]; assets: AssetInfo[]; }
export interface VersionSummary { id: string; versionNo: number; parentVersionId: string | null; parentVersionNo: number | null; originImportJobId: string | null; sourceType: SourceType; sourceFileName: string | null; status: VersionStatus; draftRevision: number; publishedAt: string | null; createdAt: string; }
export interface EditableVersion { version: VersionSummary; documentPackage: DocumentPackage; }
export interface DeletionJob { id: string; documentId: string; status: DeletionJobStatus; currentStage: DeletionStage; attemptCount: number; errorCode: string | null; errorMessage: string | null; requestedAt: string; startedAt: string | null; completedAt: string | null; updatedAt: string; }
export interface DeletedDocumentTombstone { documentId: string; deletedAt: string; }
export interface AdminDocumentSummary { id: string; code: string; title: string; status: DocumentStatus; currentVersionId: string | null; versionCount: number; draftCount: number; updatedAt: string; deletionJob: DeletionJob | null; }
export interface DocumentMetadata { documentId: string; code: string; title: string; description: string | null; tags: string[]; metadataRevision: number; duplicateTitleCount: number; }
export interface DocumentVersion { id: string; documentId: string; versionNo: number; status: VersionStatus; schemaVersion: string; }
export interface AdminDocumentPage { items: AdminDocumentSummary[]; page: number; size: number; hasNext: boolean; }
export interface EditorDocument { id: string; code: string; title: string; description: string | null; language: string; }
export interface EditorNode { id: string; parentId: string | null; nodeKey: string; nodeType: NodeType; semanticRole: SemanticRole | null; title: string; level: number; sortOrder: number; anchor: string; sourcePageStart: number | null; sourcePageEnd: number | null; }
export interface EditorSnapshot { version: VersionSummary; document: EditorDocument; nodes: EditorNode[]; }
export interface EditorBlock { id: string; blockKey: string; seq: number; blockType: BlockType; payload: Record<string, unknown>; plainText: string; language: string | null; sourcePage: number | null; sourceBbox: SourceBbox | null; confidence: number | null; }
export interface NodeBlocksPage { items: EditorBlock[]; nextCursor: string | null; }
export interface StructureNode { id: string; parentId: string | null; sortOrder: number; }
export interface BlockMutationResult { draftRevision: number; removedCount: number; }
