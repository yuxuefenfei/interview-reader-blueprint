import { http } from "./http";
import type { AuthSession, DocumentListResponse, DocumentSummary, NodeContent, ReadingProgress, SearchHit, TocNode } from "../types/api";

export const readerApi = {
  session: () => http.get<AuthSession>("/auth/session").then(({ data }) => data),
  login: (username: string, password: string) => http.post<AuthSession>("/auth/login", { username, password }).then(({ data }) => data),
  logout: () => http.post<AuthSession>("/auth/logout").then(({ data }) => data),
  documents: (query = "", cursor: string | null = null, limit = 16) => http.get<DocumentListResponse>("/reader/documents", { params: { query: query || undefined, cursor: cursor || undefined, limit } }).then(({ data }) => data),
  document: (documentId: string) => http.get<DocumentSummary>(`/reader/documents/${documentId}`).then(({ data }) => data),
  toc: (versionId: string) => http.get<TocNode[]>(`/reader/versions/${versionId}/toc`).then(({ data }) => data),
  content: (versionId: string, nodeId: string, afterSeq?: number) => http.get<NodeContent>(`/reader/versions/${versionId}/nodes/${nodeId}/content`, { params: { afterSeq, limit: 100 } }).then(({ data }) => data),
  search: (q: string, documentId?: string) => http.get<SearchHit[]>("/reader/search", { params: { q, documentId, limit: 12 } }).then(({ data }) => data),
  progress: (documentId: string) => http.get<ReadingProgress | null>(`/reader/reading-progress/${documentId}`).then(({ data }) => data),
  saveProgress: (documentId: string, progress: ReadingProgress) => http.put<ReadingProgress>(`/reader/reading-progress/${documentId}`, progress).then(({ data }) => data)
};
