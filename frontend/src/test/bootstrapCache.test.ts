import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  cacheReaderDocuments,
  cacheReaderToc,
  getCachedReaderDocuments,
  getCachedReaderToc,
  purgeReaderBootstrapForDocument,
} from "../offline/bootstrapCache";
import type { DocumentSummary, TocNode } from "../types/api";

const documents: DocumentSummary[] = [
  { id: "document-a", code: "A", title: "文档 A", description: null, currentVersionId: "version-a", progressRatio: 0 },
  { id: "document-b", code: "B", title: "文档 B", description: null, currentVersionId: "version-b", progressRatio: 0 },
];
const toc: TocNode[] = [{
  id: "node-a",
  parentId: null,
  title: "章节 A",
  level: 1,
  nodeType: "QUESTION",
  semanticRole: "QUESTION",
  anchor: "node-a",
  sourcePageStart: null,
  children: [],
}];

describe("reader bootstrap cache", () => {
  beforeEach(() => {
    const values = new Map<string, string>();
    vi.stubGlobal("localStorage", {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
      removeItem: (key: string) => values.delete(key),
      clear: () => values.clear(),
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("restores cached document metadata and table of contents without IndexedDB", async () => {
    vi.stubGlobal("indexedDB", undefined);
    await cacheReaderDocuments(documents);
    await cacheReaderToc("document-a", "version-a", toc);

    await expect(getCachedReaderDocuments()).resolves.toEqual(documents);
    await expect(getCachedReaderToc("version-a")).resolves.toEqual(toc);
  });

  it("purges both the deleted document and its cached table of contents", async () => {
    vi.stubGlobal("indexedDB", undefined);
    await cacheReaderDocuments(documents);
    await cacheReaderToc("document-a", "version-a", toc);

    await purgeReaderBootstrapForDocument("document-a");

    await expect(getCachedReaderDocuments()).resolves.toEqual([documents[1]]);
    await expect(getCachedReaderToc("version-a")).resolves.toBeNull();
  });
});
