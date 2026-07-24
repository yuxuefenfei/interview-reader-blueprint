import { flushPromises, shallowMount } from "@vue/test-utils";
import { defineComponent } from "vue";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { EditorBlock, EditorNode, EditorSnapshot } from "../types/api";

const mocks = vi.hoisted(() => ({ editor: vi.fn(), nodeBlocks: vi.fn() }));

vi.mock("vue-router", () => ({
  useRoute: () => ({ params: { versionId: "version-1" }, query: { nodeId: "node-1" } })
}));
vi.mock("../api/admin", () => ({ adminApi: { editor: mocks.editor, nodeBlocks: mocks.nodeBlocks } }));

import DetachedPreviewView from "../views/DetachedPreviewView.vue";

const node: EditorNode = {
  id: "node-1", parentId: null, nodeKey: "node", nodeType: "SECTION", semanticRole: null,
  title: "当前节点", level: 1, sortOrder: 10, anchor: "node", sourcePageStart: null, sourcePageEnd: null
};
const snapshot: EditorSnapshot = {
  version: { id: "version-1", versionNo: 1, parentVersionId: null, parentVersionNo: null, originImportJobId: null, sourceType: "MANUAL", sourceFileName: null, status: "DRAFT", draftRevision: 2, publishedAt: null, createdAt: "2026-07-24T12:00:00+08:00" },
  document: { id: "document-1", code: "document", title: "草稿", description: null, language: "zh-CN" },
  nodes: [node]
};
const image: EditorBlock = {
  id: "block-1", blockKey: "image", seq: 10, blockType: "image", payload: { assetKey: "new-image", alt: "新图片" }, plainText: "新图片", language: null, sourcePage: null, sourceBbox: null, confidence: null
};

const ButtonStub = defineComponent({ emits: ["click"], template: "<button v-bind='$attrs' @click='$emit(\"click\")'><slot /></button>" });
const ImageBlockStub = defineComponent({
  props: { block: { type: Object, required: true }, assetBaseUrl: { type: String, required: true } },
  template: "<img :src='`${assetBaseUrl}/${block.payload.assetKey}`' />"
});

describe("DetachedPreviewView", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.editor.mockResolvedValue(snapshot);
    mocks.nodeBlocks.mockResolvedValue({ items: [], nextCursor: null });
    const channels: Array<{ onmessage: ((event: MessageEvent<unknown>) => void) | null; postMessage: ReturnType<typeof vi.fn>; close: () => void }> = [];
    vi.stubGlobal("BroadcastChannel", class {
      onmessage: ((event: MessageEvent<unknown>) => void) | null = null;
      constructor() { channels.push(this); }
      postMessage = vi.fn();
      close() {}
      static get latest() { return channels.at(-1); }
    });
  });

  it("uses the saved draft when refresh is requested after a live update", async () => {
    const wrapper = shallowMount(DetachedPreviewView, {
      global: { directives: { loading: () => undefined }, stubs: { ElButton: ButtonStub, ContentBlockView: ImageBlockStub } }
    });
    await flushPromises();

    const Channel = BroadcastChannel as unknown as { latest: { onmessage: ((event: MessageEvent<unknown>) => void) | null } };
    Channel.latest.onmessage?.({ data: { type: "preview-state", state: { versionId: "version-1", document: { title: "草稿" }, node, blocks: [], activeBlockId: null, updatedAt: Date.now() } } } as MessageEvent<unknown>);
    await flushPromises();
    mocks.nodeBlocks.mockResolvedValue({ items: [image], nextCursor: null });

    await wrapper.get('button[title="刷新已保存草稿"]').trigger("click");
    await flushPromises();

    expect(wrapper.get("img").attributes("src")).toBe("/api/admin/versions/version-1/editor/assets/new-image");
  });

  it("notifies the editor when the popup preview closes", async () => {
    const wrapper = shallowMount(DetachedPreviewView, {
      global: { directives: { loading: () => undefined }, stubs: { ElButton: ButtonStub, ContentBlockView: ImageBlockStub } }
    });
    await flushPromises();

    const Channel = BroadcastChannel as unknown as { latest: { postMessage: ReturnType<typeof vi.fn> } };
    wrapper.unmount();

    expect(Channel.latest.postMessage).toHaveBeenCalledWith({ type: "preview-dismissed" });
  });
});
