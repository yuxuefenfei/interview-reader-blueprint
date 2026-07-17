import { readerApi } from "../api/reader";
import { purgeNodeContentForDocument } from "./contentCache";
import { purgeReadingProgressForDocument } from "./progressQueue";

export async function syncDeletedDocuments(): Promise<number> {
  const tombstones = await readerApi.deletedDocuments();
  for (const tombstone of tombstones) {
    await Promise.all([
      purgeNodeContentForDocument(tombstone.documentId),
      purgeReadingProgressForDocument(tombstone.documentId)
    ]);
    navigator.serviceWorker?.controller?.postMessage({ type: "PURGE_DOCUMENT", documentId: tombstone.documentId });
  }
  return tombstones.length;
}