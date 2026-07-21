import { flushPromises, mount } from "@vue/test-utils";
import { defineComponent } from "vue";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ImportDocumentPreview, ImportJob } from "../types/api";

const mocks = vi.hoisted(() => ({
  push: vi.fn(),
  documents: vi.fn(),
  upload: vi.fn(),
  importJob: vi.fn(),
  importIssues: vi.fn(),
  importDocumentMetadata: vi.fn(),
  updateImportDocumentMetadata: vi.fn(),
  commitImport: vi.fn(),
  messageError: vi.fn(),
  messageSuccess: vi.fn(),
  messageWarning: vi.fn()
}));

vi.mock("vue-router", () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ push: mocks.push })
}));
vi.mock("../api/admin", () => ({ adminApi: mocks }));
vi.mock("element-plus/es/components/message/index", () => ({
  ElMessage: { error: mocks.messageError, success: mocks.messageSuccess, warning: mocks.messageWarning }
}));

import ImportCenterView from "../views/ImportCenterView.vue";

const ButtonStub = defineComponent({
  inheritAttrs: true,
  props: { disabled: Boolean, loading: Boolean },
  emits: ["click"],
  template: '<button :disabled="disabled || loading" @click="$emit(\'click\')"><slot /></button>'
});
const UploadStub = defineComponent({
  props: { beforeUpload: Function },
  setup(props) {
    return { choose: () => props.beforeUpload?.(new File(["source"], "guide.md", { type: "text/markdown" })) };
  },
  template: '<button data-testid="choose-file" @click="choose"><slot /><slot name="tip" /></button>'
});
const CardStub = defineComponent({ template: '<section><header><slot name="header" /></header><slot /></section>' });
const FormStub = defineComponent({ template: '<form><slot /></form>' });
const FormItemStub = defineComponent({ template: '<label><slot /></label>' });
const SelectStub = defineComponent({ template: '<div><slot /></div>' });
const InputStub = defineComponent({ props: { modelValue: [String, Number] }, template: '<input :value="modelValue" />' });
const AlertStub = defineComponent({ props: { title: String, description: String }, template: '<div><strong>{{ title }}</strong><span>{{ description }}</span></div>' });
const ProgressStub = defineComponent({ template: '<div />' });
const TagStub = defineComponent({ template: '<span><slot /></span>' });
const SimpleStub = defineComponent({ template: '<div><slot /></div>' });

const readyJob: ImportJob = {
  id: "job-1", targetDocumentId: null, sourceType: "MARKDOWN", status: "READY", currentStage: "REVIEWING",
  progress: 100, statistics: {}, errorCode: null, errorMessage: null
};
const preview: ImportDocumentPreview = {
  documentKey: "guide",
  title: "Guide",
  description: "来源描述",
  tags: ["Java"],
  editable: true,
  matchingDocument: null,
  suggestedDocumentKey: "guide",
  duplicateTitleCount: 0
};

function mountView() {
  return mount(ImportCenterView, {
    global: {
      stubs: {
        ElButton: ButtonStub,
        ElUpload: UploadStub,
        ElCard: CardStub,
        ElForm: FormStub,
        ElFormItem: FormItemStub,
        ElSelect: SelectStub,
        ElOption: SimpleStub,
        ElInput: InputStub,
        ElAlert: AlertStub,
        ElProgress: ProgressStub,
        ElTag: TagStub,
        ElIcon: SimpleStub,
        ElRadioGroup: SimpleStub,
        ElRadio: SimpleStub
      }
    }
  });
}

describe("ImportCenterView", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.documents.mockResolvedValue({ items: [], page: 1, size: 30, hasNext: false });
    mocks.upload.mockResolvedValue(readyJob);
    mocks.importIssues.mockResolvedValue([]);
    mocks.importDocumentMetadata.mockResolvedValue(preview);
    mocks.updateImportDocumentMetadata.mockResolvedValue(preview);
    mocks.commitImport.mockResolvedValue({ id: "version-1", documentId: "document-1", versionNo: 1, status: "DRAFT", schemaVersion: "1.0" });
    mocks.push.mockResolvedValue(undefined);
  });

  it("requires an explicit decision instead of silently merging a matching code", async () => {
    mocks.importDocumentMetadata.mockResolvedValue({
      ...preview,
      matchingDocument: { id: "existing-1", code: "guide", title: "Existing Guide", status: "PUBLISHED" },
      suggestedDocumentKey: "guide-2"
    });
    const wrapper = mountView();
    await flushPromises();

    await wrapper.get('[data-testid="choose-file"]').trigger("click");
    await wrapper.get('[data-testid="start-import"]').trigger("click");
    await flushPromises();
    expect(wrapper.get(".import-resolution-panel").text()).toContain("系统不会静默合并");

    await wrapper.get('[data-testid="commit-import"]').trigger("click");
    await flushPromises();
    expect(mocks.messageWarning).toHaveBeenCalledWith("标识已匹配已有文档，请明确选择导入方式");
    expect(mocks.updateImportDocumentMetadata).not.toHaveBeenCalled();
    expect(mocks.commitImport).not.toHaveBeenCalled();
  });
  it("reviews and saves new document metadata before committing the draft", async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.get('[data-testid="choose-file"]').trigger("click");
    await wrapper.get('[data-testid="start-import"]').trigger("click");
    await flushPromises();

    expect(wrapper.get(".import-document-preview").text()).toContain("新文档资料");
    expect(wrapper.get('.import-document-preview input[value="guide"]').attributes("value")).toBe("guide");
    await wrapper.get('[data-testid="commit-import"]').trigger("click");
    await flushPromises();

    expect(mocks.updateImportDocumentMetadata).toHaveBeenCalledWith("job-1", {
      title: "Guide",
      description: "来源描述",
      tags: ["Java"]
    });
    expect(mocks.commitImport).toHaveBeenCalledWith("job-1", "CREATE_NEW");
    expect(mocks.push).toHaveBeenCalledWith("/admin/documents/document-1");
  });
});