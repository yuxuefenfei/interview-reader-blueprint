import type {
  Bookmark,
  BookmarkRequest,
  DocumentListResponse,
  DocumentSummary,
  DocumentVersion,
  ImportJob,
  Mastery,
  Note,
  NoteRequest,
  NodeContent,
  ReadingProgress,
  ReviewState,
  SourceType,
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

export function uploadSourceFile(file: File, sourceType: SourceType): Promise<ImportJob> {
  const body = new FormData();
  body.set("sourceType", sourceType);
  body.set("file", file);
  return request<ImportJob>("/api/import-jobs", {
    method: "POST",
    body
  });
}

export function commitImportJob(jobId: string): Promise<DocumentVersion> {
  return request<DocumentVersion>(`/api/import-jobs/${jobId}/commit`, {
    method: "POST"
  });
}

export function publishVersion(documentId: string, versionId: string): Promise<void> {
  return request<void>(`/api/documents/${documentId}/versions/${versionId}/publish`, {
    method: "POST"
  });
}
