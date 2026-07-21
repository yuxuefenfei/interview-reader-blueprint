import { http } from "./http";
import type { AdminDocumentPage, AdminDocumentSummary, BlockMutationResult, DeletionJob, DocumentMetadata, DocumentVersion, EditableVersion, EditorBlock, EditorSnapshot, ImportDocumentPreview, ImportIssue, ImportJob, ImportResolution, NodeBlocksPage, StructureNode, VersionSummary } from "../types/api";

export const adminApi = {
  documents: (query = "", page = 1, size = 20) => http.get<AdminDocumentPage>("/admin/documents", { params: { query: query || undefined, page, size } }).then(({ data }) => data),
  document: (documentId: string) => http.get<AdminDocumentSummary>(`/admin/documents/${documentId}`).then(({ data }) => data),
  documentMetadata: (documentId: string) => http.get<DocumentMetadata>(`/admin/documents/${documentId}/metadata`).then(({ data }) => data),
  updateDocumentMetadata: (documentId: string, metadata: Pick<DocumentMetadata, "metadataRevision" | "title" | "description" | "tags">) => http.patch<DocumentMetadata>(`/admin/documents/${documentId}/metadata`, metadata).then(({ data }) => data),
  versions: (documentId: string) => http.get<VersionSummary[]>(`/admin/documents/${documentId}/versions`).then(({ data }) => data),
  createRevision: (documentId: string, sourceVersionId: string) => http.post<VersionSummary>(`/admin/documents/${documentId}/versions/${sourceVersionId}/revisions`).then(({ data }) => data),
  publish: (documentId: string, versionId: string) => http.post(`/admin/documents/${documentId}/versions/${versionId}/publish`),
  takeDown: (documentId: string) => http.post(`/admin/documents/${documentId}/take-down`),
  restore: (documentId: string) => http.post(`/admin/documents/${documentId}/restore`),
  deleteDocument: (documentId: string, confirmationTitle: string) => http.post<DeletionJob>(`/admin/documents/${documentId}/deletion`, { confirmationTitle }).then(({ data }) => data),
  deletionJob: (jobId: string) => http.get<DeletionJob>(`/admin/document-deletions/${jobId}`).then(({ data }) => data),
  retryDeletion: (jobId: string) => http.post<DeletionJob>(`/admin/document-deletions/${jobId}/retry`).then(({ data }) => data),
  deleteDraft: (versionId: string) => http.delete(`/admin/versions/${versionId}/editor`),
  editor: (versionId: string) => http.get<EditorSnapshot>(`/admin/versions/${versionId}/editor`).then(({ data }) => data),
  nodeBlocks: (versionId: string, nodeId: string, cursor?: string) => http.get<NodeBlocksPage>(`/admin/versions/${versionId}/editor/nodes/${nodeId}/blocks`, { params: { cursor, limit: 40 } }).then(({ data }) => data),
  createBlock: (versionId: string, nodeId: string, draftRevision: number, block: Pick<EditorBlock, "blockType" | "payload" | "plainText" | "language">) => http.post<EditorBlock>(`/admin/versions/${versionId}/editor/nodes/${nodeId}/blocks`, { draftRevision, ...block }).then(({ data }) => data),
  updateNode: (versionId: string, nodeId: string, draftRevision: number, node: Pick<EditorSnapshot["nodes"][number], "title" | "nodeType" | "semanticRole" | "anchor">) => http.patch<EditorSnapshot>(`/admin/versions/${versionId}/editor/nodes/${nodeId}`, { draftRevision, ...node }).then(({ data }) => data),
  updateStructure: (versionId: string, draftRevision: number, nodes: StructureNode[]) => http.patch<EditorSnapshot>(`/admin/versions/${versionId}/editor/structure`, { draftRevision, nodes }).then(({ data }) => data),
  updateBlock: (versionId: string, blockId: string, draftRevision: number, block: Pick<EditorBlock, "blockType" | "payload" | "plainText" | "language">) => http.patch<EditorBlock>(`/admin/versions/${versionId}/editor/blocks/${blockId}`, { draftRevision, ...block }).then(({ data }) => data),
  deleteBlock: (versionId: string, blockId: string, draftRevision: number) => http.delete<BlockMutationResult>(`/admin/versions/${versionId}/editor/blocks/${blockId}`, { params: { draftRevision } }).then(({ data }) => data),
  cleanupEmptyBlocks: (versionId: string, draftRevision: number) => http.post<BlockMutationResult>(`/admin/versions/${versionId}/editor/blocks/cleanup-empty`, { draftRevision }).then(({ data }) => data),
  saveEditor: (versionId: string, draftRevision: number, documentPackage: EditableVersion["documentPackage"]) => http.put<EditableVersion>(`/admin/versions/${versionId}/editor`, { draftRevision, documentPackage }).then(({ data }) => data),
  upload: (file: File, targetDocumentId?: string) => { const body = new FormData(); body.set("file", file); if (targetDocumentId) body.set("targetDocumentId", targetDocumentId); return http.post<ImportJob>("/admin/import-jobs", body).then(({ data }) => data); },
  importJob: (jobId: string) => http.get<ImportJob>(`/admin/import-jobs/${jobId}`).then(({ data }) => data),
  importIssues: (jobId: string) => http.get<ImportIssue[]>(`/admin/import-jobs/${jobId}/issues`).then(({ data }) => data),
  importDocumentMetadata: (jobId: string) => http.get<ImportDocumentPreview>(`/admin/import-jobs/${jobId}/document-metadata`).then(({ data }) => data),
  updateImportDocumentMetadata: (jobId: string, metadata: Pick<ImportDocumentPreview, "title" | "description" | "tags">) => http.patch<ImportDocumentPreview>(`/admin/import-jobs/${jobId}/document-metadata`, metadata).then(({ data }) => data),
  commitImport: (jobId: string, resolution?: ImportResolution) => http.post<DocumentVersion>(`/admin/import-jobs/${jobId}/commit`, resolution ? { resolution } : undefined).then(({ data }) => data),
  cancelImport: (jobId: string) => http.post(`/admin/import-jobs/${jobId}/cancel`).then(({ data }) => data)
};
