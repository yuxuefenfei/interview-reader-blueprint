import { flushPromises, shallowMount } from "@vue/test-utils";
import { defineComponent } from "vue";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { EditorBlock, EditorNode, EditorSnapshot } from "../types/api";

const mocks = vi.hoisted(() => ({
  editor: vi.fn(),
  nodeBlocks: vi.fn(),
  updateBlock: vi.fn(),
  messageError: vi.fn(),
  routeGuard: vi.fn()
}));

vi.mock("vue-router", () => ({
  useRoute: () => ({ params: { versionId: "version-1" } }),
  useRouter: () => ({ push: vi.fn(), resolve: vi.fn(() => ({ href: "/preview" })) }),
  onBeforeRouteLeave: mocks.routeGuard
}));

vi.mock("../api/admin", () => ({
  adminApi: {
    editor: mocks.editor,
    nodeBlocks: mocks.nodeBlocks,
    updateBlock: mocks.updateBlock
  }
}));

vi.mock("element-plus/es/components/message/index", () => ({
  ElMessage: { error: mocks.messageError, success: vi.fn(), warning: vi.fn() }
}));

vi.mock("element-plus/es/components/message-box/index", () => ({
  ElMessageBox: { confirm: vi.fn() }
}));

import VersionEditorView from "../views/VersionEditorView.vue";

const nodes: EditorNode[] = [
  { id: "node-1", parentId: null, nodeKey: "n1", nodeType: "SECTION", semanticRole: null, title: "第一节", level: 1, sortOrder: 10, anchor: "n1", sourcePageStart: 1, sourcePageEnd: 1 },
  { id: "node-2", parentId: null, nodeKey: "n2", nodeType: "SECTION", semanticRole: null, title: "第二节", level: 1, sortOrder: 20, anchor: "n2", sourcePageStart: 2, sourcePageEnd: 2 }
];

const snapshot: EditorSnapshot = {
  version: { id: "version-1", versionNo: 1, parentVersionId: null, parentVersionNo: null, originImportJobId: null, sourceType: "PDF", sourceFileName: "source.pdf", status: "DRAFT", draftRevision: 0, publishedAt: null, createdAt: "2026-07-20T10:00:00+08:00" },
  document: { id: "document-1", code: "doc", title: "文档", description: null, language: "zh-CN" },
  nodes
};

function block(id: string, text: string): EditorBlock {
  return { id, blockKey: id, seq: 1, blockType: "paragraph", payload: { text }, plainText: text, language: null, sourcePage: 1, sourceBbox: null, confidence: null };
}

const TreeStub = defineComponent({
  props: { data: { type: Array, default: () => [] } },
  emits: ["node-click"],
  template: '<div><button v-for="node in data" :key="node.id" :data-testid="`tree-${node.id}`" @click="$emit(\'node-click\', node)">{{ node.title }}</button></div>'
});

const InputStub = defineComponent({
  inheritAttrs: false,
  props: { modelValue: { type: [String, Number], default: "" } },
  emits: ["update:modelValue", "input"],
  template: '<textarea v-bind="$attrs" :value="modelValue" @input="onInput" />',
  methods: {
    onInput(event: Event) {
      const value = (event.target as HTMLTextAreaElement).value;
      this.$emit("update:modelValue", value);
      this.$emit("input", value);
    }
  }
});

function mountView() {
  return shallowMount(VersionEditorView, {
    global: {
      directives: { loading: () => undefined },
      stubs: { ElTree: TreeStub, ElInput: InputStub, Teleport: true }
    }
  });
}

describe("VersionEditorView autosave", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.stubGlobal("BroadcastChannel", class {
      onmessage: ((event: MessageEvent<unknown>) => void) | null = null;
      postMessage() {}
      close() {}
    });
    Object.defineProperty(HTMLElement.prototype, "scrollTo", { configurable: true, value: vi.fn() });
    mocks.editor.mockResolvedValue(snapshot);
    mocks.nodeBlocks.mockImplementation((_versionId: string, nodeId: string) => Promise.resolve({
      items: nodeId === "node-1" ? [block("block-1", "原内容")] : [block("block-2", "第二节内容")],
      nextCursor: null
    }));
    mocks.updateBlock.mockImplementation((_versionId: string, _blockId: string, _revision: number, update: Partial<EditorBlock>) =>
      Promise.resolve({ ...block("block-1", String(update.plainText)), ...update }));
  });

  it("flushes the edited block before loading a newly selected node", async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.get("textarea.block-main-editor").setValue("切换节点前必须保存");
    await wrapper.get('[data-testid="tree-node-2"]').trigger("click");
    await flushPromises();

    expect(mocks.updateBlock).toHaveBeenCalledWith(
      "version-1",
      "block-1",
      0,
      expect.objectContaining({ plainText: "切换节点前必须保存" })
    );
    expect(mocks.nodeBlocks).toHaveBeenLastCalledWith("version-1", "node-2", undefined);
    expect(mocks.updateBlock.mock.invocationCallOrder[0]).toBeLessThan(mocks.nodeBlocks.mock.invocationCallOrder.at(-1)!);
  });
});
