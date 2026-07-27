import { flushPromises, mount } from "@vue/test-utils";
import { defineComponent, nextTick, reactive } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ContentBlock, DocumentSummary, NodeContent, TocNode } from "../types/api";
import ReaderView from "../views/ReaderView.vue";

const api = vi.hoisted(() => ({
  documents: vi.fn(),
  document: vi.fn(),
  latestReadDocument: vi.fn(),
  toc: vi.fn(),
  progress: vi.fn(),
  content: vi.fn(),
  saveProgress: vi.fn(),
  search: vi.fn(),
}));
const routing = vi.hoisted(() => ({
  route: null as { params: { documentId?: string } } | null,
  push: vi.fn(),
}));

vi.mock("../api/reader", () => ({ readerApi: api }));
vi.mock("vue-router", () => ({
  useRoute: () => routing.route,
  useRouter: () => ({ push: routing.push }),
}));
vi.mock("../offline/contentCache", () => ({
  cacheNodeContent: vi.fn(() => Promise.resolve()),
  getCachedNodeContent: vi.fn(() => Promise.resolve(null)),
}));
vi.mock("../offline/progressQueue", () => ({
  enqueueReadingProgress: vi.fn(() => Promise.resolve()),
  flushReadingProgressQueue: vi.fn(() => Promise.resolve(0)),
  shouldDiscardReadingProgress: vi.fn(() => false),
  shouldQueueReadingProgress: vi.fn(() => false),
}));
vi.mock("../utils/readingDevice", () => ({ getOrCreateReadingDeviceId: () => "test-device" }));
vi.mock("element-plus/es/components/message/index", () => ({ ElMessage: { success: vi.fn() } }));
vi.mock("../components/ContentBlockView.vue", () => ({
  default: defineComponent({ template: "<div />" }),
}));
vi.mock("../components/TocTree.vue", () => ({
  default: defineComponent({ template: "<div />" }),
}));

function document(id: string, versionId: string): DocumentSummary {
  return { id, code: id, title: id, description: null, currentVersionId: versionId, progressRatio: 0 };
}

function node(id: string): TocNode {
  return {
    id,
    parentId: null,
    title: id,
    level: 1,
    nodeType: "QUESTION",
    semanticRole: "QUESTION",
    anchor: `anchor-${id}`,
    sourcePageStart: null,
    children: [],
  };
}

function block(id: string): ContentBlock {
  return {
    id,
    blockKey: id,
    seq: 1,
    blockType: "paragraph",
    payload: { text: id },
    plainText: id,
    sourcePage: null,
    sourceBbox: null,
    confidence: null,
  };
}

function content(currentNode: TocNode, blockId: string): NodeContent {
  return { node: currentNode, blocks: [block(blockId)], nextAfterSeq: null };
}

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((next) => { resolve = next; });
  return { promise, resolve };
}

describe("ReaderView request coordination", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.clearAllMocks();
    const values = new Map<string, string>();
    vi.stubGlobal("localStorage", {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
      removeItem: (key: string) => values.delete(key),
      clear: () => values.clear(),
    });
    routing.route = reactive({ params: { documentId: "document-a" } });
    routing.push.mockResolvedValue(undefined);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it("never saves an old chapter with the newly selected document version", async () => {
    const documentA = document("document-a", "version-a");
    const documentB = document("document-b", "version-b");
    const nodeA = node("node-a");
    const nodeB = node("node-b");
    const delayedA = deferred<NodeContent>();
    api.documents.mockResolvedValue({ items: [documentA, documentB], nextCursor: null });
    api.toc.mockImplementation((versionId: string) => Promise.resolve(versionId === "version-a" ? [nodeA] : [nodeB]));
    api.progress.mockResolvedValue(null);
    api.content.mockImplementation((versionId: string) =>
      versionId === "version-a" ? delayedA.promise : Promise.resolve(content(nodeB, "block-b")));
    api.saveProgress.mockImplementation(async (_documentId: string, progressValue) => progressValue);

    const wrapper = mount(ReaderView, {
      global: {
        config: { warnHandler: () => undefined },
      },
    });
    await flushPromises();
    expect(api.content).toHaveBeenCalledWith("version-a", "node-a");

    routing.route!.params.documentId = "document-b";
    await nextTick();
    await flushPromises();
    delayedA.resolve(content(nodeA, "block-a"));
    await flushPromises();
    await vi.advanceTimersByTimeAsync(701);

    expect(api.saveProgress).toHaveBeenCalledTimes(1);
    expect(api.saveProgress).toHaveBeenCalledWith(
      "document-b",
      expect.objectContaining({
        versionId: "version-b",
        sectionId: "node-b",
        blockId: "block-b",
      })
    );
    expect(wrapper.text()).not.toContain("阅读章节不属于目标版本");
    wrapper.unmount();
  });
});
