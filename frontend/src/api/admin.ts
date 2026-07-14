import { http } from "./http";
import type { AdminDocumentPage, EditableVersion, ImportIssue, ImportJob, SourceType, VersionSummary } from "../types/api";

export const adminApi = {
  documents: (page = 1, size = 20) => http.get<AdminDocumentPage>("/admin/documents", { params: { page, size } }).then(({ data }) => data),
  versions: (documentId: string) => http.get<VersionSummary[]>(`/admin/documents/${documentId}/versions`).then(({ data }) => data),
  createRevision: (documentId: string, sourceVersionId: string) => http.post<VersionSummary>(`/admin/documents/${documentId}/versions/${sourceVersionId}/revisions`).then(({ data }) => data),
  publish: (documentId: string, versionId: string) => http.post(`/admin/documents/${documentId}/versions/${versionId}/publish`),
  editor: (versionId: string) => http.get<EditableVersion>(`/admin/versions/${versionId}/editor`).then(({ data }) => data),
  saveEditor: (versionId: string, draftRevision: number, documentPackage: EditableVersion["documentPackage"]) => http.put<EditableVersion>(`/admin/versions/${versionId}/editor`, { draftRevision, documentPackage }).then(({ data }) => data),
  deleteDraft: (versionId: string) => http.delete(`/admin/versions/${versionId}/editor`),
  upload: (file: File, sourceType: SourceType, targetDocumentId?: string) => {
    const body = new FormData();
    body.set("file", file);
    body.set("sourceType", sourceType);
    if (targetDocumentId) body.set("targetDocumentId", targetDocumentId);
    return http.post<ImportJob>("/admin/import-jobs", body).then(({ data }) => data);
  },
  importJob: (jobId: string) => http.get<ImportJob>(`/admin/import-jobs/${jobId}`).then(({ data }) => data),
  importIssues: (jobId: string) => http.get<ImportIssue[]>(`/admin/import-jobs/${jobId}/issues`).then(({ data }) => data),
  commitImport: (jobId: string) => http.post(`/admin/import-jobs/${jobId}/commit`).then(({ data }) => data),
  cancelImport: (jobId: string) => http.post(`/admin/import-jobs/${jobId}/cancel`).then(({ data }) => data)
};