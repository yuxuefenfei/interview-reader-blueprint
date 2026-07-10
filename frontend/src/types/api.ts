export interface DocumentSummary {
  id: string;
  code: string;
  title: string;
  description: string | null;
  currentVersionId: string | null;
  progressRatio: number;
}

export interface DocumentListResponse {
  items: DocumentSummary[];
  nextCursor: string | null;
}

export interface TocNode {
  id: string;
  parentId: string | null;
  title: string;
  level: number;
  nodeType: string;
  semanticRole: string | null;
  anchor: string;
  sourcePageStart: number | null;
  children: TocNode[];
}

export interface ContentBlock {
  id: string;
  blockKey: string;
  seq: number;
  blockType: BlockType;
  payload: Record<string, unknown>;
  plainText: string;
  sourcePage: number | null;
  sourceBbox: SourceBbox | null;
  confidence: number | null;
}

export type BlockType =
  | "paragraph"
  | "heading_note"
  | "unordered_list"
  | "ordered_list"
  | "code"
  | "table"
  | "quote"
  | "callout"
  | "formula"
  | "image"
  | "divider"
  | "table_snapshot";

export interface NodeContent {
  node: TocNode;
  blocks: ContentBlock[];
  nextAfterSeq: number | null;
}

export interface SearchHit {
  documentId: string;
  versionId: string;
  nodeId: string;
  blockId: string;
  title: string;
  sectionPath: string[];
  snippet: string;
  score: number;
}

export interface ReadingProgress {
  versionId: string;
  sectionId: string | null;
  blockId: string | null;
  charOffset: number;
  blockViewportOffset: number;
  progressRatio: number;
  clientUpdatedAt: string | null;
  deviceId: string | null;
  revision: number;
}

export interface BookmarkRequest {
  documentId: string;
  versionId: string;
  sectionId: string;
  blockId: string;
  title: string | null;
}

export interface Bookmark {
  id: string;
  documentId: string;
  versionId: string;
  sectionId: string;
  blockId: string;
  title: string | null;
  createdAt: string;
}

export interface NoteRequest {
  documentId: string;
  versionId: string;
  sectionId: string;
  blockId: string | null;
  selectedText: string | null;
  body: string;
}

export interface Note {
  id: string;
  documentId: string;
  versionId: string;
  sectionId: string;
  blockId: string | null;
  selectedText: string | null;
  body: string;
  createdAt: string;
  updatedAt: string;
}

export type Mastery = "UNKNOWN" | "HARD" | "FUZZY" | "KNOWN";

export interface ReviewState {
  id: string;
  documentId: string;
  nodeId: string;
  mastery: Mastery;
  dueAt: string | null;
  intervalDays: number | null;
  repetitions: number;
  updatedAt: string;
}

export interface ReviewQueueItem {
  documentId: string;
  versionId: string;
  nodeId: string;
  title: string;
  sectionPath: string[];
  nodeType: string;
  semanticRole: string | null;
  sourcePageStart: number | null;
  mastery: Mastery;
  dueAt: string | null;
  intervalDays: number | null;
  repetitions: number;
}

export interface ImportJob {
  id: string;
  status: string;
  currentStage: string | null;
  progress: number;
  statistics: Record<string, unknown>;
  errorCode: string | null;
  errorMessage: string | null;
}

export interface ImportIssue {
  severity: string;
  issueCode: string;
  message: string;
  sourcePage: number | null;
  sectionKey: string | null;
  blockKey: string | null;
  cellRef?: string | null;
}

export interface SourceBbox {
  page?: number | null;
  x?: number | null;
  y?: number | null;
  width?: number | null;
  height?: number | null;
}

export interface StagedSection {
  sectionKey: string;
  parentSectionKey: string | null;
  level: number;
  nodeType: string;
  semanticRole: string | null;
  title: string;
  sortOrder: number;
  anchor: string;
  sourcePageStart: number | null;
  sourcePageEnd: number | null;
  sourceBbox: SourceBbox | null;
  [key: string]: unknown;
}

export interface StagedBlock {
  blockKey: string;
  sectionKey: string;
  seq: number;
  blockType: BlockType;
  payload: Record<string, unknown>;
  plainText: string;
  language: string | null;
  sourcePage: number | null;
  sourceBbox: SourceBbox | null;
  confidence: number | null;
  [key: string]: unknown;
}

export interface NormalizedPackage {
  schemaVersion: string;
  document: Record<string, unknown>;
  version: Record<string, unknown>;
  sections: StagedSection[];
  blocks: StagedBlock[];
  assets: Record<string, unknown>[];
}

export interface DocumentVersion {
  id: string;
  documentId: string;
  versionNo: number;
  status: string;
  schemaVersion: string;
}

export type SourceType = "JSON_PACKAGE" | "EXCEL" | "MARKDOWN" | "PDF";
