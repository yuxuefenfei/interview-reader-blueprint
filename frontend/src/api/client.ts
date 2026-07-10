import type {
  Bookmark,
  BookmarkRequest,
  DocumentListResponse,
  DocumentSummary,
  DocumentVersion,
  ImportIssue,
  ImportJob,
  Mastery,
  Note,
  NoteRequest,
  NodeContent,
  NormalizedPackage,
  ReadingProgress,
  ReviewQueueItem,
  ReviewState,
  SearchHit,
  SourceType,
  StagedBlock,
  StagedSection,
  TocNode
} from "../types/api";

async function request<T>(input: RequestInfo | URL, init?: RequestInit): Promise<T> {
  const response = await fetch(input, init);
  if (!response.ok) {
    const body = await response.json().catch(() => ({ error: response.statusText }));
    throw new Error(typeof body.error === "string" ? body.error : response.statusText);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return response.json() as Promise<T>;
}

export function listDocuments(query = ""): Promise<DocumentListResponse> {
  const params = query.trim() ? `?query=${encodeURIComponent(query.trim())}` : "";
  return request<DocumentListResponse>(`/api/documents${params}`);
}

export function getDocument(documentId: string): Promise<DocumentSummary> {
  return request<DocumentSummary>(`/api/documents/${documentId}`);
}

export function getToc(versionId: string): Promise<TocNode[]> {
  return request<TocNode[]>(`/api/versions/${versionId}/toc`);
}

export function getNodeContent(versionId: string, nodeId: string, limit = 50): Promise<NodeContent> {
  return request<NodeContent>(`/api/versions/${versionId}/nodes/${nodeId}/content?limit=${limit}`);
}

export function searchContent(query: string, documentId?: string | null, limit = 8): Promise<SearchHit[]> {
  const params = new URLSearchParams({
    q: query,
    limit: String(limit)
  });
  if (documentId) {
    params.set("documentId", documentId);
  }
  return request<SearchHit[]>(`/api/search?${params.toString()}`);
}

export function getReadingProgress(documentId: string): Promise<ReadingProgress | null> {
  return request<ReadingProgress | null>(`/api/reading-progress/${documentId}`);
}

export function saveReadingProgress(documentId: string, progress: ReadingProgress): Promise<ReadingProgress> {
  return request<ReadingProgress>(`/api/reading-progress/${documentId}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(progress)
  });
}

export function saveBookmark(bookmark: BookmarkRequest): Promise<Bookmark> {
  return request<Bookmark>("/api/bookmarks", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(bookmark)
  });
}

export function createNote(note: NoteRequest): Promise<Note> {
  return request<Note>("/api/notes", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(note)
  });
}

export function saveReviewState(nodeId: string, documentId: string, mastery: Mastery): Promise<ReviewState> {
  return request<ReviewState>(`/api/review-states/${nodeId}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ documentId, mastery })
  });
}

export function getReviewQueue(documentId: string, limit = 5, dueOnly = false): Promise<ReviewQueueItem[]> {
  const params = new URLSearchParams({
    documentId,
    limit: String(limit),
    dueOnly: String(dueOnly)
  });
  return request<ReviewQueueItem[]>(`/api/review-queue?${params.toString()}`);
}

export function uploadSourceFile(file: File, sourceType: SourceType): Promise<ImportJob> {
  const body = new FormData();
  body.set("sourceType", sourceType);
  body.set("file", file);
  return request<ImportJob>("/api/import-jobs", {
    method: "POST",
    body
  });
}

export function getImportJob(jobId: string): Promise<ImportJob> {
  return request<ImportJob>(`/api/import-jobs/${jobId}`);
}

export function getImportIssues(jobId: string): Promise<ImportIssue[]> {
  return request<ImportIssue[]>(`/api/import-jobs/${jobId}/issues`);
}

export function sourceFileUrl(jobId: string, page?: number | null): string {
  const url = `/api/import-jobs/${jobId}/source-file`;
  return page && page > 0 ? `${url}#page=${page}` : url;
}

export function getNormalizedPackage(jobId: string): Promise<NormalizedPackage> {
  return request<NormalizedPackage>(`/api/import-jobs/${jobId}/normalized-package`);
}

export function reviseStagedSection(
  jobId: string,
  sectionKey: string,
  patch: Partial<Pick<StagedSection, "parentSectionKey" | "level" | "title" | "nodeType" | "semanticRole" | "sortOrder" | "anchor">>
): Promise<NormalizedPackage> {
  return request<NormalizedPackage>(`/api/import-jobs/${jobId}/normalized-package/sections/${encodeURIComponent(sectionKey)}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(patch)
  });
}

export function reviseStagedBlock(
  jobId: string,
  blockKey: string,
  patch: Partial<Pick<StagedBlock, "sectionKey" | "seq" | "blockType" | "payload" | "plainText" | "language">>
): Promise<NormalizedPackage> {
  return request<NormalizedPackage>(`/api/import-jobs/${jobId}/normalized-package/blocks/${encodeURIComponent(blockKey)}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(patch)
  });
}

export function commitImportJob(jobId: string): Promise<DocumentVersion> {
  return request<DocumentVersion>(`/api/import-jobs/${jobId}/commit`, {
    method: "POST"
  });
}

export function cancelImportJob(jobId: string): Promise<ImportJob> {
  return request<ImportJob>(`/api/import-jobs/${jobId}/cancel`, {
    method: "POST"
  });
}

export function publishVersion(documentId: string, versionId: string): Promise<void> {
  return request<void>(`/api/documents/${documentId}/versions/${versionId}/publish`, {
    method: "POST"
  });
}
