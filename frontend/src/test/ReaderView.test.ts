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
  default: defineComponent({
    props: { block: { type: Object, required: true } },
    template: '<div class="mock-content">{{ block.plainText }}</div>',
  }),
}));
vi.mock("../components/TocTree.vue", () => ({
  default: defineComponent({
    props: {
      nodes: { type: Array, default: () => [] },
      activeNodeId: { type: String, default: null },
      expandedNodeIds: { type: Array, default: () => [] },
      pendingNodeId: { type: String, default: null },
      failedNodeId: { type: String, default: null },
    },
    emits: ["select", "toggle"],
    template: `
      <div class="mock-toc-tree">
        <button
          v-for="item in nodes"
          :key="item.id"
          class="mock-toc-node"
          :data-node-id="item.id"
          :data-failed="item.id === failedNodeId"
          @click="$emit('select', item)"
        >{{ item.title }}<span v-if="item.id === failedNodeId">失败，重试</span></button>
      </div>
    `,
  }),
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

const ButtonStub = defineComponent({
  inheritAttrs: false,
  props: { disabled: Boolean, loading: Boolean },
  emits: ["click"],
  template: '<button v-bind="$attrs" :disabled="disabled" :data-loading="loading" @click="$emit(\'click\')"><slot /></button>',
});

const DrawerStub = defineComponent({
  props: { modelValue: Boolean },
  emits: ["update:modelValue"],
  template: '<div v-if="modelValue" class="mock-drawer"><slot /></div>',
});

function mountReader() {
  return mount(ReaderView, {
    global: {
      config: { warnHandler: () => undefined },
      stubs: { ElButton: ButtonStub, ElDrawer: DrawerStub },
    },
  });
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
    Object.defineProperty(HTMLElement.prototype, "scrollTo", {
      configurable: true,
      value: vi.fn(),
    });
    Object.defineProperty(HTMLElement.prototype, "scrollIntoView", {
      configurable: true,
      value: vi.fn(),
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

    const wrapper = mountReader();
    await flushPromises();
    expect(api.content).toHaveBeenCalledWith("version-a", "node-a", undefined, expect.any(AbortSignal));

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

  it("keeps the current chapter visible and locks pagination until the next chapter is ready", async () => {
    const currentDocument = document("document-a", "version-a");
    const nodeA = node("node-a");
    const nodeB = node("node-b");
    const delayedB = deferred<NodeContent>();
    api.documents.mockResolvedValue({ items: [currentDocument], nextCursor: null });
    api.toc.mockResolvedValue([nodeA, nodeB]);
    api.progress.mockResolvedValue(null);
    api.content.mockImplementation((_versionId: string, nodeId: string) =>
      nodeId === nodeA.id ? Promise.resolve(content(nodeA, "block-a")) : delayedB.promise);
    api.saveProgress.mockImplementation(async (_documentId: string, progressValue) => progressValue);

    const wrapper = mountReader();
    await flushPromises();
    expect(wrapper.get(".reader-article").attributes("data-node-id")).toBe("node-a");
    expect(wrapper.get(".mock-content").text()).toBe("block-a");

    await wrapper.get(".chapter-nav-next").trigger("click");
    await nextTick();
    expect(wrapper.get(".chapter-nav-next").attributes("disabled")).toBeDefined();
    expect(wrapper.get(".chapter-nav-next").attributes("data-loading")).toBe("true");
    expect(wrapper.get(".reader-content").attributes("aria-busy")).toBe("true");
    expect(wrapper.get(".reader-article").attributes("data-node-id")).toBe("node-a");
    expect(wrapper.get(".mock-content").text()).toBe("block-a");

    await wrapper.get(".chapter-nav-next").trigger("click");
    expect(api.content.mock.calls.filter((call) => call[1] === "node-b")).toHaveLength(1);

    delayedB.resolve(content(nodeB, "block-b"));
    await flushPromises();
    expect(wrapper.get(".reader-content").attributes("aria-busy")).toBe("false");
    expect(wrapper.get(".reader-article").attributes("data-node-id")).toBe("node-b");
    expect(wrapper.get(".mock-content").text()).toBe("block-b");
    expect(HTMLElement.prototype.scrollTo).toHaveBeenCalledWith({ top: 0, behavior: "auto" });
    wrapper.unmount();
  });

  it("uses a two-level mobile drawer and persists the active chapter path", async () => {
    const currentDocument = { ...document("document-a", "version-a"), title: "Redis", progressRatio: 0.46 };
    const otherDocument = { ...document("document-b", "version-b"), title: "MongoDB", progressRatio: 0.2 };
    const child = { ...node("child"), parentId: "root", level: 2 };
    const root = {
      ...node("root"),
      nodeType: "SECTION" as const,
      semanticRole: null,
      children: [child],
    };
    api.documents.mockResolvedValue({ items: [currentDocument, otherDocument], nextCursor: null });
    api.toc.mockResolvedValue([root]);
    api.progress.mockResolvedValue({ sectionId: child.id });
    api.content.mockResolvedValue(content(child, "block-child"));
    api.saveProgress.mockImplementation(async (_documentId: string, progressValue) => progressValue);

    const wrapper = mountReader();
    await flushPromises();
    expect(JSON.parse(localStorage.getItem("reader.toc.expanded.document-a") ?? "[]")).toContain(root.id);

    await wrapper.get(".reader-menu-button").trigger("click");
    await nextTick();
    expect(wrapper.get(".reader-current-document").text()).toContain("Redis");
    expect(wrapper.get(".reader-current-document").text()).toContain("46%");
    expect(wrapper.find(".reader-document-list").exists()).toBe(false);

    await wrapper.get(".reader-switch-document").trigger("click");
    await nextTick();
    const documentList = wrapper.get(".reader-document-list");
    expect(documentList.text()).toContain("Redis");
    expect(documentList.text()).toContain("MongoDB");
    expect(documentList.get(".reader-document-option.active").text()).toContain("正在阅读");

    const mongoOption = documentList.findAll(".reader-document-option")
      .find((option) => option.text().includes("MongoDB"));
    await mongoOption?.trigger("click");
    await flushPromises();
    expect(routing.push).toHaveBeenCalledWith("/reader/documents/document-b");
    routing.route!.params.documentId = "document-b";
    await nextTick();
    await flushPromises();
    expect(wrapper.find(".reader-drawer").exists()).toBe(true);
    expect(wrapper.get(".reader-current-document").text()).toContain("MongoDB");
    wrapper.unmount();
  });

  it("keeps the drawer and current content visible when chapter navigation fails", async () => {
    const currentDocument = document("document-a", "version-a");
    const nodeA = node("node-a");
    const nodeB = node("node-b");
    api.documents.mockResolvedValue({ items: [currentDocument], nextCursor: null });
    api.toc.mockResolvedValue([nodeA, nodeB]);
    api.progress.mockResolvedValue(null);
    api.content.mockImplementation((_versionId: string, nodeId: string) =>
      nodeId === nodeA.id
        ? Promise.resolve(content(nodeA, "block-a"))
        : Promise.reject(new Error("chapter unavailable")));
    api.saveProgress.mockImplementation(async (_documentId: string, progressValue) => progressValue);

    const wrapper = mountReader();
    await flushPromises();
    await wrapper.get(".reader-menu-button").trigger("click");
    await nextTick();
    await wrapper.get(`.reader-drawer [data-node-id="${nodeB.id}"]`).trigger("click");
    await flushPromises();

    expect(wrapper.find(".reader-drawer").exists()).toBe(true);
    expect(wrapper.get(".reader-article").attributes("data-node-id")).toBe(nodeA.id);
    expect(wrapper.get(".reader-drawer").text()).toContain("失败，重试");
    wrapper.unmount();
  });
});
