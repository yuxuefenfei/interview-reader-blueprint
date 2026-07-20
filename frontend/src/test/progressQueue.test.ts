import { afterEach, describe, expect, it, vi } from "vitest";
import { AppError } from "../api/http";
import { enqueueReadingProgress, flushReadingProgressQueue, purgeReadingProgressForDocument, shouldQueueReadingProgress } from "../offline/progressQueue";
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
  it("shares one flush when several triggers fire concurrently", async () => {
    vi.stubGlobal("indexedDB", undefined);
    await enqueueReadingProgress("document-1", progress);
    let release!: () => void;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    const send = vi.fn(async (_documentId: string, queuedProgress: ReadingProgress) => {
      await gate;
      return queuedProgress;
    });

    const first = flushReadingProgressQueue(send);
    const second = flushReadingProgressQueue(send);
    release();

    await expect(Promise.all([first, second])).resolves.toEqual([1, 1]);
    expect(send).toHaveBeenCalledTimes(1);
  });

  it("serializes fallback enqueue with a running flush without losing the new item", async () => {
    vi.stubGlobal("indexedDB", undefined);
    await enqueueReadingProgress("document-1", progress);
    let release!: () => void;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    const flushing = flushReadingProgressQueue(async (_documentId, queuedProgress) => {
      await gate;
      return queuedProgress;
    });
    const enqueueing = enqueueReadingProgress("document-2", progress);

    release();
    await Promise.all([flushing, enqueueing]);
    const sent: string[] = [];
    await flushReadingProgressQueue(async (documentId, queuedProgress) => {
      sent.push(documentId);
      return queuedProgress;
    });

    expect(sent).toEqual(["document-2"]);
  });

  it("queues only errors that the HTTP contract marks retryable", () => {
    const validation = new AppError("invalid", "validation", 400, "INVALID", undefined, undefined, false, null);
    const network = new AppError("offline", "network", undefined, "NETWORK_ERROR", undefined, undefined, true, null);
    const server = new AppError("busy", "server", 503, "UNAVAILABLE", undefined, undefined, true, null);

    expect(shouldQueueReadingProgress(validation)).toBe(false);
    expect(shouldQueueReadingProgress(new Error("unexpected"))).toBe(false);
    expect(shouldQueueReadingProgress(network)).toBe(true);
    expect(shouldQueueReadingProgress(server)).toBe(true);
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
