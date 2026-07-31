import { flushPromises, shallowMount } from "@vue/test-utils";
import { defineComponent } from "vue";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { EditorBlock, EditorNode, EditorSnapshot } from "../types/api";

const mocks = vi.hoisted(() => ({
  editor: vi.fn(),
  nodeBlocks: vi.fn(),
  updateNode: vi.fn(),
  deleteNode: vi.fn(),
  updateBlock: vi.fn(),
  messageError: vi.fn(),
  messageSuccess: vi.fn(),
  messageConfirm: vi.fn(),
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
    updateNode: mocks.updateNode,
    deleteNode: mocks.deleteNode,
    updateBlock: mocks.updateBlock
  }
}));

vi.mock("element-plus/es/components/message/index", () => ({
  ElMessage: { error: mocks.messageError, success: mocks.messageSuccess, warning: vi.fn() }
}));

vi.mock("element-plus/es/components/message-box/index", () => ({
  ElMessageBox: { confirm: mocks.messageConfirm }
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

const PageHeaderStub = defineComponent({
  template: '<header><slot name="status" /><slot name="actions" /></header>'
});

const ButtonStub = defineComponent({
  inheritAttrs: false,
  props: {
    disabled: { type: Boolean, default: false },
    loading: { type: Boolean, default: false }
  },
  emits: ["click"],
  template: '<button v-bind="$attrs" :disabled="disabled" @click="$emit(\'click\')"><slot /></button>'
});

const DrawerStub = defineComponent({
  props: {
    modelValue: { type: Boolean, default: false },
    beforeClose: { type: Function, default: undefined }
  },
  emits: ["update:modelValue"],
  template: '<aside v-if="modelValue" data-testid="node-property-drawer"><slot /><footer><slot name="footer" /></footer></aside>'
});

const FormStub = defineComponent({
  template: "<form><slot /></form>"
});

const FormItemStub = defineComponent({
  props: {
    label: { type: String, default: "" },
    error: { type: String, default: "" }
  },
  template: '<label><span>{{ label }}</span><slot /><span v-if="error" class="form-error">{{ error }}</span></label>'
});
const TooltipStub = defineComponent({
  props: { content: { type: String, default: "" } },
  template: '<span :data-tooltip="content"><slot /></span>'
});

function mountView() {
  return shallowMount(VersionEditorView, {
    global: {
      directives: { loading: () => undefined },
      stubs: {
        AdminPageHeader: PageHeaderStub,
        ElButton: ButtonStub,
        ElCollapse: true,
        ElCollapseItem: true,
        ElDrawer: DrawerStub,
        ElDropdown: true,
        ElDropdownItem: true,
        ElDropdownMenu: true,
        ElEmpty: true,
        ElForm: FormStub,
        ElFormItem: FormItemStub,
        ElIcon: true,
        ElInput: InputStub,
        ElOption: true,
        ElRadioButton: true,
        ElRadioGroup: true,
        ElSelect: true,
        ElSwitch: true,
        ElTag: true,
        ElTooltip: TooltipStub,
        ElTree: TreeStub,
        ElUpload: true,
        Teleport: true
      }
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
    mocks.editor.mockImplementation(() => Promise.resolve(structuredClone(snapshot)));
    mocks.messageConfirm.mockResolvedValue(undefined);
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

    expect(wrapper.find('[name="node-anchor"]').exists()).toBe(false);

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

  it("keeps the page header as the only manual save entry", async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.get('[data-testid="save-editor"]').attributes("disabled")).toBeDefined();
    await wrapper.get("textarea.block-main-editor").setValue("修改后的内容");

    const saveActions = wrapper.findAll("button")
      .map((button) => button.text().trim())
      .filter((label) => label === "保存" || label === "保存当前节点");
    expect(saveActions).toEqual(["保存"]);
    expect(wrapper.get('[data-testid="save-editor"]').attributes("disabled")).toBeUndefined();
  });

  it("deletes an empty structural node and selects the next available node", async () => {
    mocks.nodeBlocks.mockImplementation((_versionId: string, nodeId: string) => Promise.resolve({
      items: nodeId === "node-1" ? [] : [block("block-2", "第二节内容")],
      nextCursor: null
    }));
    mocks.deleteNode.mockResolvedValue({
      ...snapshot,
      version: { ...snapshot.version, draftRevision: 1 },
      nodes: [nodes[1]]
    });
    const wrapper = mountView();
    await flushPromises();

    const deleteButton = wrapper.get('[data-testid="delete-node"]');
    expect(deleteButton.attributes("disabled")).toBeUndefined();
    await deleteButton.trigger("click");
    await flushPromises();

    expect(mocks.messageConfirm).toHaveBeenCalledWith(
      expect.stringContaining("第一节"),
      "删除结构节点",
      expect.objectContaining({ confirmButtonText: "删除节点" })
    );
    expect(mocks.deleteNode).toHaveBeenCalledWith("version-1", "node-1", 0);
    expect(mocks.nodeBlocks).toHaveBeenLastCalledWith("version-1", "node-2", undefined);
    expect(wrapper.text()).toContain("第二节");
    expect(mocks.messageSuccess).toHaveBeenCalledWith("节点已删除");
  });

  it("does not offer node deletion while the node still has content blocks", async () => {
    const wrapper = mountView();
    await flushPromises();

    const deleteButton = wrapper.get('[data-testid="delete-node"]');
    expect(deleteButton.attributes("disabled")).toBeDefined();
    expect(deleteButton.attributes("title")).toBe("请先删除该节点的全部内容块");
  });

  it("shows node taxonomy choices inline and validates unsaved node changes", async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.get('[data-testid="node-properties-trigger"]').trigger("click");

    const drawer = wrapper.get('[data-testid="node-property-drawer"]');
    expect(drawer.text()).toContain("当前位置");
    expect(drawer.text()).toContain("结构类型");
    expect(drawer.text()).toContain("内容语义");
    expect(drawer.findAll(".node-property-info")).toHaveLength(3);
    expect(drawer.text()).not.toContain("标题会同步显示在目录与阅读页中");
    expect(drawer.text()).not.toContain("决定该节点在目录层级中的结构定位");
    expect(drawer.findAll('input[name="node-type"]')).toHaveLength(7);
    expect(drawer.findAll('input[name="semantic-role"]')).toHaveLength(11);
    expect(drawer.get('[data-testid="save-node-properties"]').attributes("disabled")).toBeDefined();

    await drawer.get('[name="node-title"]').setValue("新的节点标题");
    expect(drawer.text()).toContain("新的节点标题");
    expect(drawer.get('[data-testid="save-node-properties"]').attributes("disabled")).toBeUndefined();
    expect(drawer.text()).toContain("有未保存修改");

    await drawer.get('[name="node-title"]').setValue("   ");
    expect(drawer.text()).toContain("请输入节点标题");
    expect(drawer.get('[data-testid="save-node-properties"]').attributes("disabled")).toBeDefined();
  });

  it("confirms before discarding dirty node properties", async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.get('[data-testid="node-properties-trigger"]').trigger("click");
    const drawer = wrapper.get('[data-testid="node-property-drawer"]');
    await drawer.get('[name="node-title"]').setValue("未保存的标题");
    await drawer.get('[data-testid="cancel-node-properties"]').trigger("click");
    await flushPromises();

    expect(mocks.messageConfirm).toHaveBeenCalledWith(
      expect.stringContaining("尚未保存"),
      "放弃节点修改？",
      expect.objectContaining({ confirmButtonText: "放弃修改" })
    );
    expect(wrapper.find('[data-testid="node-property-drawer"]').exists()).toBe(false);
  });
});
