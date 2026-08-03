import type {
  AdminDocumentPage as GeneratedAdminDocumentPage,
  AdminDocumentSummary as GeneratedAdminDocumentSummary,
  AssetInfo as GeneratedAssetInfo,
  AuthSession as GeneratedAuthSession,
  BlockMutationResult as GeneratedBlockMutationResult,
  BlockType as GeneratedBlockType,
  ContentBlock as GeneratedContentBlock,
  DeletedDocumentTombstone as GeneratedDeletedDocumentTombstone,
  DeletionJob as GeneratedDeletionJob,
  DeletionJobStatus as GeneratedDeletionJobStatus,
  DeletionStage as GeneratedDeletionStage,
  DocumentInfo as GeneratedDocumentInfo,
  DocumentMetadata as GeneratedDocumentMetadata,
  DocumentPackage as GeneratedDocumentPackage,
  DocumentPackageBlock as GeneratedDocumentPackageBlock,
  DocumentPackageSection as GeneratedDocumentPackageSection,
  DocumentPage as GeneratedDocumentPage,
  DocumentStatus as GeneratedDocumentStatus,
  DocumentSummary as GeneratedDocumentSummary,
  DocumentVersion as GeneratedDocumentVersion,
  EditorBlock as GeneratedEditorBlock,
  EditorDocument as GeneratedEditorDocument,
  EditorNode as GeneratedEditorNode,
  EditorSnapshot as GeneratedEditorSnapshot,
  ExistingDocumentMatch as GeneratedExistingDocumentMatch,
  ImageBlockUploadResult as GeneratedImageBlockUploadResult,
  ImportDocumentPreview as GeneratedImportDocumentPreview,
  ImportIssue as GeneratedImportIssue,
  ImportIssueSeverity as GeneratedImportIssueSeverity,
  ImportJob as GeneratedImportJob,
  ImportResolution as GeneratedImportResolution,
  ImportStage as GeneratedImportStage,
  ImportStatus as GeneratedImportStatus,
  MasteryState as GeneratedMasteryState,
  NodeBlocksPage as GeneratedNodeBlocksPage,
  NodeContent as GeneratedNodeContent,
  NodeType as GeneratedNodeType,
  ReadingProgress as GeneratedReadingProgress,
  SearchHit as GeneratedSearchHit,
  SemanticRole as GeneratedSemanticRole,
  SourceBbox as GeneratedSourceBbox,
  SourceType as GeneratedSourceType,
  StructureNode as GeneratedStructureNode,
  TocNode as GeneratedTocNode,
  VersionInfo as GeneratedVersionInfo,
  VersionStatus as GeneratedVersionStatus,
  VersionSummary as GeneratedVersionSummary
} from "../generated/api";

export const SOURCE_TYPES = ["PDF", "EXCEL", "JSON_PACKAGE", "MARKDOWN", "MANUAL"] as const satisfies readonly GeneratedSourceType[];
export type SourceType = GeneratedSourceType;

export const NODE_TYPES = ["PART", "CHAPTER", "SECTION", "SUBSECTION", "QUESTION", "APPENDIX", "OTHER"] as const satisfies readonly GeneratedNodeType[];
export type NodeType = GeneratedNodeType;

export const BLOCK_TYPES = ["paragraph", "heading_note", "unordered_list", "ordered_list", "code", "table", "quote", "callout", "formula", "image", "divider", "table_snapshot"] as const satisfies readonly GeneratedBlockType[];
export type BlockType = GeneratedBlockType;

export const SEMANTIC_ROLES = ["QUESTION", "ANSWER", "EXPLANATION", "CONCLUSION", "INTRODUCTION", "DIRECTORY", "PRINCIPLE", "PRACTICE", "PITFALL", "FOLLOW_UP"] as const satisfies readonly GeneratedSemanticRole[];
export type SemanticRole = GeneratedSemanticRole;

export const MASTERY_STATES = ["UNKNOWN", "HARD", "FUZZY", "KNOWN"] as const satisfies readonly GeneratedMasteryState[];
export type MasteryState = GeneratedMasteryState;

export const VERSION_STATUSES = ["DRAFT", "PUBLISHED", "RETIRED"] as const satisfies readonly GeneratedVersionStatus[];
export type VersionStatus = GeneratedVersionStatus;

export const DOCUMENT_STATUSES = ["DRAFT", "PUBLISHED", "OFFLINE", "DELETING", "DELETE_FAILED"] as const satisfies readonly GeneratedDocumentStatus[];
export type DocumentStatus = GeneratedDocumentStatus;

export const DELETION_JOB_STATUSES = ["QUEUED", "RUNNING", "FAILED", "COMPLETED"] as const satisfies readonly GeneratedDeletionJobStatus[];
export type DeletionJobStatus = GeneratedDeletionJobStatus;

export const DELETION_STAGES = ["QUEUED", "CLIENT_SYNC_MARKED", "DELETING_FILES", "DELETING_DATA", "COMPLETED", "FAILED"] as const satisfies readonly GeneratedDeletionStage[];
export type DeletionStage = GeneratedDeletionStage;

export const IMPORT_STATUSES = ["UPLOADED", "PREFLIGHT", "EXTRACTING", "NORMALIZING", "VALIDATING", "READY", "REVIEW_REQUIRED", "IMPORTED", "FAILED", "CANCELED"] as const satisfies readonly GeneratedImportStatus[];
export type ImportStatus = GeneratedImportStatus;

export const IMPORT_STAGES = ["UPLOADED", "PREFLIGHT", "EXTRACTING", "NORMALIZING", "VALIDATING", "READY", "REVIEW_REQUIRED", "REVIEWING", "FAILED", "CANCELED", "COMMITTED", "DRAFT_DISCARDED"] as const satisfies readonly GeneratedImportStage[];
export type ImportStage = GeneratedImportStage;

export const IMPORT_RESOLUTIONS = ["CREATE_NEW", "IMPORT_AS_NEW_VERSION"] as const satisfies readonly GeneratedImportResolution[];
export type ImportResolution = GeneratedImportResolution;

export const IMPORT_ISSUE_SEVERITIES = ["BLOCKING", "WARNING"] as const satisfies readonly GeneratedImportIssueSeverity[];
export type ImportIssueSeverity = GeneratedImportIssueSeverity;

export const TERMINAL_IMPORT_STATUSES = new Set<ImportStatus>(["READY", "REVIEW_REQUIRED", "IMPORTED", "FAILED", "CANCELED"]);

export type AuthSession = GeneratedAuthSession;
export type DocumentSummary = GeneratedDocumentSummary;
export type DocumentListResponse = GeneratedDocumentPage;
export type TocNode = GeneratedTocNode;
export type ContentBlock = GeneratedContentBlock;
export type NodeContent = GeneratedNodeContent;
export type SearchHit = GeneratedSearchHit;
export type ReadingProgress = GeneratedReadingProgress;
export type SourceBbox = GeneratedSourceBbox;
export type ImportJob = GeneratedImportJob;
export type ExistingDocumentMatch = GeneratedExistingDocumentMatch;
export type ImportDocumentPreview = GeneratedImportDocumentPreview;
export type ImportIssue = GeneratedImportIssue;
export type StagedSection = GeneratedDocumentPackageSection;
export type StagedBlock = GeneratedDocumentPackageBlock;
export type DocumentInfo = GeneratedDocumentInfo;
export type VersionInfo = GeneratedVersionInfo;
export type AssetInfo = GeneratedAssetInfo;
export type DocumentPackage = GeneratedDocumentPackage;
export type VersionSummary = GeneratedVersionSummary;
export type DeletionJob = GeneratedDeletionJob;
export type DeletedDocumentTombstone = GeneratedDeletedDocumentTombstone;
export type AdminDocumentSummary = GeneratedAdminDocumentSummary;
export type DocumentMetadata = GeneratedDocumentMetadata;
export type DocumentVersion = GeneratedDocumentVersion;
export type AdminDocumentPage = GeneratedAdminDocumentPage;
export type EditorDocument = GeneratedEditorDocument;
export type EditorNode = GeneratedEditorNode;
export type EditorSnapshot = GeneratedEditorSnapshot;
export type EditorBlock = GeneratedEditorBlock;
export type NodeBlocksPage = GeneratedNodeBlocksPage;
export type StructureNode = GeneratedStructureNode;
export type BlockMutationResult = GeneratedBlockMutationResult;
export type ImageBlockUploadResult = GeneratedImageBlockUploadResult;
