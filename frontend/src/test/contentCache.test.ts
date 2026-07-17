import { afterEach, describe, expect, it, vi } from "vitest";
import { cacheNodeContent, clearNodeContentCache, getCachedNodeContent, purgeNodeContentForDocument } from "../offline/contentCache";
import type { NodeContent } from "../types/api";

const content: NodeContent = {
  node: {
    id: "node-1",
    parentId: null,
    title: "1. HashMap 为什么线程不安全？",
    level: 1,
    nodeType: "QUESTION",
    semanticRole: "QUESTION",
    anchor: "hashmap",
    sourcePageStart: 1,
    children: []
  },
  blocks: [
    {
      id: "block-1",
      blockKey: "q1-p1",
      seq: 1,
      blockType: "paragraph",
      payload: { text: "HashMap is not thread-safe." },
      plainText: "HashMap is not thread-safe.",
      sourcePage: 1,
      sourceBbox: null,
      confidence: 0.9
    }
  ],
  nextAfterSeq: null
};

describe("offline node content cache", () => {
  afterEach(() => {
    globalThis.localStorage?.clear();
    vi.unstubAllGlobals();
  });

  it("stores and reads node content from the localStorage fallback", async () => {
    vi.stubGlobal("indexedDB", undefined);

    await cacheNodeContent("document-1", "version-1", "node-1", 50, content);
    const cached = await getCachedNodeContent("version-1", "node-1", 50);

    expect(cached?.node.title).toBe("1. HashMap 为什么线程不安全？");
    expect(cached?.blocks[0].plainText).toBe("HashMap is not thread-safe.");
  });

  it("misses when the limit does not match the cached page size", async () => {
    vi.stubGlobal("indexedDB", undefined);

    await cacheNodeContent("document-1", "version-1", "node-1", 50, content);

    await expect(getCachedNodeContent("version-1", "node-1", 20)).resolves.toBeNull();
  });

  it("purges only the deleted document from the fallback cache", async () => {
    vi.stubGlobal("indexedDB", undefined);
    await cacheNodeContent("document-1", "version-1", "node-1", 50, content);
    await cacheNodeContent("document-2", "version-2", "node-1", 50, content);

    await purgeNodeContentForDocument("document-1");

    await expect(getCachedNodeContent("version-1", "node-1", 50)).resolves.toBeNull();
    await expect(getCachedNodeContent("version-2", "node-1", 50)).resolves.not.toBeNull();
  });
  it("clears cached node content without requiring IndexedDB", async () => {
    vi.stubGlobal("indexedDB", undefined);

    await cacheNodeContent("document-1", "version-1", "node-1", 50, content);
    await clearNodeContentCache();

    await expect(getCachedNodeContent("version-1", "node-1", 50)).resolves.toBeNull();
  });
});
