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
const messages = vi.hoisted(() => ({
  success: vi.fn(),
  error: vi.fn(),
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
vi.mock("element-plus/es/components/message/index", () => ({ ElMessage: messages }));
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

function section(id: string, children: TocNode[]): TocNode {
  return {
    id,
    parentId: null,
    title: id,
    level: 1,
    nodeType: "SECTION",
    semanticRole: null,
    anchor: `anchor-${id}`,
    sourcePageStart: null,
    children,
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

const InputStub = defineComponent({
  inheritAttrs: false,
  props: { modelValue: { type: String, default: "" } },
  emits: ["update:modelValue", "keyup"],
  methods: {
    focus() {
      (this.$el.querySelector("input") as HTMLInputElement | null)?.focus();
    },
  },
  template: '<div><input v-bind="$attrs" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" @keyup="$emit(\'keyup\', $event)" /><slot name="append" /></div>',
});

function mountReader() {
  return mount(ReaderView, {
    global: {
      config: { warnHandler: () => undefined },
      stubs: { ElButton: ButtonStub, ElDrawer: DrawerStub, ElInput: InputStub },
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

  it("searches the current document by default and exposes an explicit all-documents scope", async () => {
    const currentDocument = document("document-a", "version-a");
    const currentNode = node("node-a");
    api.documents.mockResolvedValue({ items: [currentDocument], nextCursor: null });
    api.toc.mockResolvedValue([currentNode]);
    api.progress.mockResolvedValue(null);
    api.content.mockResolvedValue(content(currentNode, "block-a"));
    api.search.mockResolvedValue([]);

    const wrapper = mountReader();
    await flushPromises();
    await wrapper.get(".reader-header-search-trigger").trigger("click");
    await nextTick();
    const input = wrapper.get('input[name="reader-search"]');
    await input.setValue("HashMap");
    await input.trigger("keyup", { key: "Enter" });
    await flushPromises();
    expect(api.search).toHaveBeenLastCalledWith("HashMap", "document-a", expect.any(AbortSignal));

    await wrapper.get('[aria-label="搜索范围"]').findAll("button")[1].trigger("click");
    await input.trigger("keyup", { key: "Enter" });
    await flushPromises();
    expect(api.search).toHaveBeenLastCalledWith("HashMap", undefined, expect.any(AbortSignal));
    wrapper.unmount();
  });

  it("debounces search, requires two characters, and aborts stale requests", async () => {
    const currentDocument = document("document-a", "version-a");
    const currentNode = node("node-a");
    const firstSearch = deferred<never[]>();
    api.documents.mockResolvedValue({ items: [currentDocument], nextCursor: null });
    api.toc.mockResolvedValue([currentNode]);
    api.progress.mockResolvedValue(null);
    api.content.mockResolvedValue(content(currentNode, "block-a"));
    api.search
      .mockImplementationOnce(() => firstSearch.promise)
      .mockResolvedValueOnce([]);

    const wrapper = mountReader();
    await flushPromises();
    await wrapper.get(".reader-header-search-trigger").trigger("click");
    const input = wrapper.get('input[name="reader-search"]');

    await input.setValue("H");
    await vi.advanceTimersByTimeAsync(600);
    expect(api.search).not.toHaveBeenCalled();

    await input.setValue("Ha");
    await vi.advanceTimersByTimeAsync(499);
    expect(api.search).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    expect(api.search).toHaveBeenCalledTimes(1);
    const firstSignal = api.search.mock.calls[0][2] as AbortSignal;

    await input.setValue("Hash");
    expect(firstSignal.aborted).toBe(true);
    await vi.advanceTimersByTimeAsync(500);
    await flushPromises();
    expect(api.search).toHaveBeenLastCalledWith("Hash", "document-a", expect.any(AbortSignal));
    expect(wrapper.get(".reader-search-feedback").text()).toContain("没有找到");

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
    expect(wrapper.find(".chapter-transition-status").exists()).toBe(false);
    expect(wrapper.findAll(".chapter-loading-dots")).toHaveLength(2);
    expect(wrapper.find(".mobile-progress-label").exists()).toBe(false);
    expect(wrapper.text()).toContain("正在加载下一节");

    await wrapper.get(".chapter-nav-next").trigger("click");
    expect(api.content.mock.calls.filter((call) => call[1] === "node-b")).toHaveLength(1);

    delayedB.resolve(content(nodeB, "block-b"));
    await flushPromises();
    expect(wrapper.get(".reader-content").attributes("aria-busy")).toBe("false");
    expect(wrapper.get(".reader-article").attributes("data-node-id")).toBe("node-b");
    expect(wrapper.get(".mock-content").text()).toBe("block-b");
    expect(wrapper.findAll(".chapter-loading-dots")).toHaveLength(0);
    expect(wrapper.get(".mobile-progress-label").text()).toBe("0%");
    expect(HTMLElement.prototype.scrollTo).toHaveBeenCalledWith({ top: 0, behavior: "auto" });
    wrapper.unmount();
  });

  it("reuses a conditionally prefetched next chapter without issuing a duplicate request", async () => {
    const currentDocument = document("document-a", "version-a");
    const nodeA = node("node-a");
    const nodeB = node("node-b");
    api.documents.mockResolvedValue({ items: [currentDocument], nextCursor: null });
    api.toc.mockResolvedValue([nodeA, nodeB]);
    api.progress.mockResolvedValue(null);
    api.content.mockImplementation((_versionId: string, nodeId: string) =>
      Promise.resolve(content(nodeId === nodeA.id ? nodeA : nodeB, `block-${nodeId}`)));

    const wrapper = mountReader();
    await flushPromises();
    await wrapper.get(".chapter-nav-next").trigger("pointerenter");
    await flushPromises();
    expect(api.content.mock.calls.filter((call) => call[1] === nodeB.id)).toHaveLength(1);

    await wrapper.get(".chapter-nav-next").trigger("click");
    await flushPromises();
    expect(api.content.mock.calls.filter((call) => call[1] === nodeB.id)).toHaveLength(1);
    expect(wrapper.get(".reader-article").attributes("data-node-id")).toBe(nodeB.id);
    wrapper.unmount();
  });

  it("opens a section at its first readable chapter so pagination remains available", async () => {
    const currentDocument = document("document-a", "version-a");
    const first = { ...node("first"), parentId: "section", level: 2 };
    const second = { ...node("second"), parentId: "section", level: 2 };
    const root = section("section", [first, second]);
    api.documents.mockResolvedValue({ items: [currentDocument], nextCursor: null });
    api.toc.mockResolvedValue([root]);
    api.progress.mockResolvedValue(null);
    api.content.mockImplementation((_versionId: string, nodeId: string) =>
      Promise.resolve(content(nodeId === first.id ? first : second, `block-${nodeId}`)));
    api.saveProgress.mockImplementation(async (_documentId: string, progressValue) => progressValue);

    const wrapper = mountReader();
    await flushPromises();
    api.content.mockClear();

    await wrapper.get(".reader-desktop-nav-expand").trigger("click");
    await wrapper.get('.reader-desktop-nav [data-node-id="section"]').trigger("click");
    await flushPromises();

    expect(api.content).toHaveBeenCalledWith("version-a", "first", undefined, expect.any(AbortSignal));
    expect(wrapper.get(".reader-article").attributes("data-node-id")).toBe("first");
    expect(wrapper.get(".chapter-position").text()).toBe("1 / 2");
    expect(wrapper.get(".chapter-nav-next").attributes("disabled")).toBeUndefined();
    wrapper.unmount();
  });

  it("uses a two-level mobile drawer and persists the active chapter path", async () => {
    const currentDocument = { ...document("document-a", "version-a"), title: "Redis", progressRatio: 0.73 };
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
    api.progress.mockResolvedValue({ sectionId: child.id, progressRatio: 0.46 });
    api.content.mockResolvedValue(content(child, "block-child"));
    api.saveProgress.mockImplementation(async (_documentId: string, progressValue) => progressValue);

    const wrapper = mountReader();
    await flushPromises();
    expect(JSON.parse(localStorage.getItem("reader.toc.expanded.document-a") ?? "[]")).toContain(root.id);
    await vi.advanceTimersByTimeAsync(701);
    expect(api.saveProgress).toHaveBeenCalledWith(
      "document-a",
      expect.objectContaining({ sectionId: child.id, progressRatio: 0.46 }),
    );

    await wrapper.get(".reader-menu-button").trigger("click");
    await nextTick();
    expect(wrapper.get(".reader-document-selector").text()).toContain("Redis");
    expect(wrapper.get(".reader-document-selector").text()).toContain("46%");
    expect(wrapper.find(".reader-document-list").exists()).toBe(false);

    await wrapper.get(".reader-document-selector").trigger("click");
    await nextTick();
    expect(wrapper.get(".reader-drawer-back").text()).toContain("返回目录");
    expect(wrapper.find('[aria-label="关闭目录"]').exists()).toBe(false);
    const documentList = wrapper.get(".reader-document-list");
    expect(documentList.text()).toContain("Redis");
    expect(documentList.text()).toContain("MongoDB");
    expect(documentList.get(".reader-document-option.active").text()).toContain("当前");

    const mongoOption = documentList.findAll(".reader-document-option")
      .find((option) => option.text().includes("MongoDB"));
    await mongoOption?.trigger("click");
    await flushPromises();
    expect(routing.push).toHaveBeenCalledWith("/reader/documents/document-b");
    routing.route!.params.documentId = "document-b";
    await nextTick();
    await flushPromises();
    expect(wrapper.find(".reader-drawer").exists()).toBe(true);
    expect(wrapper.get(".reader-document-selector").text()).toContain("MongoDB");
    wrapper.unmount();
  });

  it("reuses the compact document navigation on desktop and persists its collapsed state", async () => {
    localStorage.setItem("reader.desktopNav.collapsed", "false");
    const currentDocument = { ...document("document-a", "version-a"), title: "Redis", progressRatio: 0.46 };
    const otherDocument = { ...document("document-b", "version-b"), title: "MongoDB", progressRatio: 0.2 };
    const currentNode = node("node-a");
    api.documents.mockResolvedValue({ items: [currentDocument, otherDocument], nextCursor: null });
    api.toc.mockResolvedValue([currentNode]);
    api.progress.mockResolvedValue(null);
    api.content.mockResolvedValue(content(currentNode, "block-a"));
    api.saveProgress.mockImplementation(async (_documentId: string, progressValue) => progressValue);

    const wrapper = mountReader();
    await flushPromises();
    expect(wrapper.get(".reader-desktop-nav").classes()).not.toContain("collapsed");
    expect(wrapper.get(".reader-desktop-nav .reader-document-selector").text()).toContain("Redis");
    expect(wrapper.find(".reader-desktop-nav .reader-document-list").exists()).toBe(false);

    await wrapper.get(".reader-desktop-nav .reader-document-selector").trigger("click");
    await nextTick();
    expect(wrapper.get(".reader-desktop-nav .reader-document-list").text()).toContain("MongoDB");

    await wrapper.get(".reader-desktop-nav-collapse").trigger("click");
    await nextTick();
    expect(wrapper.get(".reader-desktop-nav").classes()).toContain("collapsed");
    expect(localStorage.getItem("reader.desktopNav.collapsed")).toBe("true");
    expect(wrapper.get(".reader-desktop-rail-progress").attributes("role")).toBe("progressbar");

    await wrapper.get(".reader-desktop-nav-expand").trigger("click");
    await nextTick();
    expect(wrapper.get(".reader-desktop-nav").classes()).not.toContain("collapsed");
    expect(localStorage.getItem("reader.desktopNav.collapsed")).toBe("false");
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
    expect(wrapper.findAll(".chapter-loading-dots")).toHaveLength(0);
    expect(messages.error).toHaveBeenCalledWith({
      message: "章节加载失败，请重试",
      duration: 2_000,
      showClose: false,
    });
    wrapper.unmount();
  });
});
