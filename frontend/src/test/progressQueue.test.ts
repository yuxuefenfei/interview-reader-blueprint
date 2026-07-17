import { afterEach, describe, expect, it, vi } from "vitest";
import { enqueueReadingProgress, flushReadingProgressQueue, purgeReadingProgressForDocument } from "../offline/progressQueue";
import type { ReadingProgress } from "../types/api";

const progress: ReadingProgress = {
  versionId: "version-1",
  sectionId: "section-1",
  blockId: "block-1",
  charOffset: 0,
  blockViewportOffset: 0,
  progressRatio: 0.4,
  clientUpdatedAt: "2026-07-07T00:00:00.000Z",
  deviceId: "test",
  revision: 0
};

describe("offline reading progress queue", () => {
  afterEach(() => {
    globalThis.localStorage?.clear();
    vi.unstubAllGlobals();
  });

  it("queues progress in localStorage when IndexedDB is unavailable", async () => {
    vi.stubGlobal("indexedDB", undefined);
    await enqueueReadingProgress("document-1", progress);

    const sent: string[] = [];
    const count = await flushReadingProgressQueue(async (documentId, queuedProgress) => {
      sent.push(`${documentId}:${queuedProgress.versionId}`);
      return queuedProgress;
    });

    expect(count).toBe(1);
    expect(sent).toEqual(["document-1:version-1"]);
  });

  it("keeps unsent progress when flushing fails", async () => {
    vi.stubGlobal("indexedDB", undefined);
    await enqueueReadingProgress("document-1", progress);

    await expect(
      flushReadingProgressQueue(async () => {
        throw new Error("offline");
      })
    ).rejects.toThrow("offline");

    const count = await flushReadingProgressQueue(async (_documentId, queuedProgress) => queuedProgress);
    expect(count).toBe(1);
  });
  it("purges queued progress for a permanently deleted document", async () => {
    vi.stubGlobal("indexedDB", undefined);
    await enqueueReadingProgress("document-1", progress);
    await enqueueReadingProgress("document-2", progress);

    await purgeReadingProgressForDocument("document-1");
    const sent: string[] = [];
    const count = await flushReadingProgressQueue(async (documentId, queued) => {
      sent.push(documentId);
      return queued;
    });

    expect(count).toBe(1);
    expect(sent).toEqual(["document-2"]);
  });
});
