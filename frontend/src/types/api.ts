export interface AuthSession { authenticated: boolean; username: string | null; }
export interface DocumentSummary { id: string; code: string; title: string; description: string | null; currentVersionId: string | null; progressRatio: number; }
export interface DocumentListResponse { items: DocumentSummary[]; nextCursor: string | null; }
export interface TocNode { id: string; parentId: string | null; title: string; level: number; nodeType: string; semanticRole: string | null; anchor: string; sourcePageStart: number | null; children: TocNode[]; }
export type BlockType = "paragraph" | "heading_note" | "unordered_list" | "ordered_list" | "code" | "table" | "quote" | "callout" | "formula" | "image" | "divider" | "table_snapshot";
export interface ContentBlock { id: string; blockKey: string; seq: number; blockType: BlockType; payload: Record<string, unknown>; plainText: string; sourcePage: number | null; sourceBbox: SourceBbox | null; confidence: number | null; }
export interface NodeContent { node: TocNode; blocks: ContentBlock[]; nextAfterSeq: number | null; }
export interface SearchHit { documentId: string; versionId: string; nodeId: string; blockId: string; title: string; sectionPath: string[]; snippet: string; score: number; }
export interface ReadingProgress { versionId: string; sectionId: string | null; blockId: string | null; charOffset: number; blockViewportOffset: number; progressRatio: number; clientUpdatedAt: string | null; deviceId: string | null; revision: number; }
export interface SourceBbox { page?: number | null; x?: number | null; y?: number | null; width?: number | null; height?: number | null; }
export type SourceType = "JSON_PACKAGE" | "EXCEL" | "MARKDOWN" | "PDF";
export interface ImportJob { id: string; targetDocumentId: string | null; sourceType: SourceType; status: string; currentStage: string | null; progress: number; statistics: Record<string, unknown>; errorCode: string | null; errorMessage: string | null; }
export interface ImportIssue { severity: string; issueCode: string; message: string; sourcePage: number | null; sectionKey: string | null; blockKey: string | null; cellRef?: string | null; }
export interface StagedSection { sectionKey: string; parentSectionKey: string | null; level: number; nodeType: string; semanticRole: string | null; title: string; sortOrder: number; anchor: string; sourcePageStart: number | null; sourcePageEnd: number | null; sourceBbox: SourceBbox | null; }
export interface StagedBlock { blockKey: string; sectionKey: string; seq: number; blockType: BlockType; payload: Record<string, unknown>; plainText: string; language: string | null; sourcePage: number | null; sourceBbox: SourceBbox | null; confidence: number | null; }
export interface DocumentPackage { schemaVersion: string; document: { documentKey: string; title: string; description: string | null; language: string; tags: string[] }; version: { versionKey: string; sourceType: string; sourceFileName: string | null; sourceSha256: string | null; converterVersion: string | null; metadata: Record<string, unknown> }; sections: StagedSection[]; blocks: StagedBlock[]; assets: Array<Record<string, unknown>>; }
export interface VersionSummary { id: string; versionNo: number; parentVersionId: string | null; originImportJobId: string | null; sourceType: string; sourceFileName: string | null; status: "DRAFT" | "PUBLISHED" | "RETIRED"; draftRevision: number; publishedAt: string | null; createdAt: string; }
export interface EditableVersion { version: VersionSummary; documentPackage: DocumentPackage; }
export interface AdminDocumentSummary { id: string; code: string; title: string; status: string; currentVersionId: string | null; versionCount: number; draftCount: number; updatedAt: string; }
export interface AdminDocumentPage { items: AdminDocumentSummary[]; page: number; size: number; hasNext: boolean; }
export interface EditorDocument { id: string; code: string; title: string; description: string | null; language: string; }
export interface EditorNode { id: string; parentId: string | null; nodeKey: string; nodeType: string; semanticRole: string | null; title: string; level: number; sortOrder: number; anchor: string; sourcePageStart: number | null; sourcePageEnd: number | null; }
export interface EditorSnapshot { version: VersionSummary; document: EditorDocument; nodes: EditorNode[]; }
export interface EditorBlock { id: string; blockKey: string; seq: number; blockType: BlockType; payload: Record<string, unknown>; plainText: string; language: string | null; sourcePage: number | null; sourceBbox: SourceBbox | null; confidence: number | null; }
export interface NodeBlocksPage { items: EditorBlock[]; nextCursor: string | null; }
export interface StructureNode { id: string; parentId: string | null; sortOrder: number; }
export interface BlockMutationResult { draftRevision: number; removedCount: number; }