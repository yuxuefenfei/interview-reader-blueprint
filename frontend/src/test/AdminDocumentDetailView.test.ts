import { flushPromises, mount } from "@vue/test-utils";
import { defineComponent } from "vue";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { AdminDocumentSummary, VersionSummary } from "../types/api";

const mocks = vi.hoisted(() => ({
  push: vi.fn(),
  document: vi.fn(),
  versions: vi.fn(),
  createRevision: vi.fn(),
  publish: vi.fn(),
  deleteDraft: vi.fn(),
  confirm: vi.fn(),
  messageError: vi.fn(),
  messageSuccess: vi.fn()
}));

vi.mock("vue-router", () => ({
  useRoute: () => ({ params: { documentId: "document-1" } }),
  useRouter: () => ({ push: mocks.push })
}));

vi.mock("../api/admin", () => ({
  adminApi: {
    document: mocks.document,
    versions: mocks.versions,
    createRevision: mocks.createRevision,
    publish: mocks.publish,
    deleteDraft: mocks.deleteDraft
  }
}));

vi.mock("element-plus/es/components/message/index", () => ({
  ElMessage: { error: mocks.messageError, success: mocks.messageSuccess }
}));

vi.mock("element-plus/es/components/message-box/index", () => ({
  ElMessageBox: { confirm: mocks.confirm }
}));

import AdminDocumentDetailView from "../views/AdminDocumentDetailView.vue";

const ButtonStub = defineComponent({
  inheritAttrs: true,
  props: { disabled: Boolean, loading: Boolean },
  emits: ["click"],
  template: '<button :disabled="disabled || loading" @click="$emit(\'click\')"><slot /></button>'
});
const CardStub = defineComponent({ template: '<section><header><slot name="header" /></header><slot /></section>' });
const TagStub = defineComponent({ template: '<span><slot /></span>' });
const EmptyStub = defineComponent({ props: { description: String }, template: '<div>{{ description }}</div>' });
const DropdownStub = defineComponent({ emits: ["command"], template: '<div><slot /><slot name="dropdown" /></div>' });
const DropdownMenuStub = defineComponent({ template: '<div><slot /></div>' });
const DropdownItemStub = defineComponent({ template: '<button><slot /></button>' });

function version(overrides: Partial<VersionSummary> & Pick<VersionSummary, "id" | "versionNo" | "status">): VersionSummary {
  return {
    parentVersionId: null,
    parentVersionNo: null,
    originImportJobId: null,
    sourceType: "PDF",
    sourceFileName: `source-v${overrides.versionNo}.pdf`,
    draftRevision: 0,
    publishedAt: null,
    createdAt: `2026-07-17T09:0${overrides.versionNo}:00+08:00`,
    ...overrides
  };
}

const documentSummary: AdminDocumentSummary = {
  id: "document-1",
  code: "java-guide",
  title: "Java 高级开发面试题",
  status: "ACTIVE",
  currentVersionId: "v2",
  versionCount: 4,
  draftCount: 2,
  updatedAt: "2026-07-17T09:04:00+08:00"
};

const versionFixtures: VersionSummary[] = [
  version({ id: "v4", versionNo: 4, status: "DRAFT", parentVersionId: "v2", parentVersionNo: 2 }),
  version({ id: "v3", versionNo: 3, status: "DRAFT", parentVersionId: "v1", parentVersionNo: 1 }),
  version({ id: "v2", versionNo: 2, status: "PUBLISHED", parentVersionId: "v1", parentVersionNo: 1, publishedAt: "2026-07-17T09:02:30+08:00" }),
  version({ id: "v1", versionNo: 1, status: "RETIRED" })
];

function mountView() {
  return mount(AdminDocumentDetailView, {
    global: {
      directives: { loading: () => undefined },
      stubs: {
        ElButton: ButtonStub,
        ElCard: CardStub,
        ElTag: TagStub,
        ElEmpty: EmptyStub,
        ElDropdown: DropdownStub,
        ElDropdownMenu: DropdownMenuStub,
        ElDropdownItem: DropdownItemStub
      }
    }
  });
}

describe("AdminDocumentDetailView", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.push.mockResolvedValue(undefined);
    mocks.document.mockResolvedValue(documentSummary);
    mocks.versions.mockResolvedValue(versionFixtures);
    mocks.createRevision.mockResolvedValue(versionFixtures[0]);
    mocks.publish.mockResolvedValue(undefined);
    mocks.deleteDraft.mockResolvedValue(undefined);
    mocks.confirm.mockResolvedValue("confirm");
  });

  it("pins the current published version and exposes branch lineage", async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.get(".current-version-panel").text()).toContain("当前线上版本");
    expect(wrapper.get(".current-version-panel").text()).toContain("v2");
    expect(wrapper.get('[data-version-id="v4"]').text()).toContain("修订链：v1 → v2 → v4");
    expect(wrapper.get('[data-version-id="v4"]').text()).toContain("最新草稿");
    expect(wrapper.get('[data-version-id="v3"]').text()).toContain("分支草稿");
    expect(wrapper.get('[data-version-id="v3"]').text()).toContain("不包含当前线上版本的后续变更");
  });

  it("includes branch risk in the publish confirmation", async () => {
    mocks.confirm.mockRejectedValueOnce("cancel");
    const wrapper = mountView();
    await flushPromises();

    await wrapper.get('[data-testid="publish-v3"]').trigger("click");
    await flushPromises();

    expect(mocks.confirm).toHaveBeenCalledWith(
      expect.stringContaining("该草稿不包含当前线上 v2 的后续变更"),
      "发布 v3",
      expect.any(Object)
    );
    expect(mocks.publish).not.toHaveBeenCalled();
  });

  it("locks every version action while publishing to prevent duplicate commands", async () => {
    let resolvePublish!: () => void;
    mocks.publish.mockReturnValueOnce(new Promise<void>((resolve) => { resolvePublish = resolve; }));
    const wrapper = mountView();
    await flushPromises();

    const publishButton = wrapper.get('[data-testid="publish-v4"]');
    await publishButton.trigger("click");
    await flushPromises();
    await publishButton.trigger("click");

    expect(mocks.confirm).toHaveBeenCalledTimes(1);
    expect(mocks.publish).toHaveBeenCalledTimes(1);
    expect(wrapper.get('[data-testid="edit-v3"]').attributes("disabled")).toBeDefined();

    resolvePublish();
    await flushPromises();
    expect(wrapper.get('[data-testid="edit-v3"]').attributes("disabled")).toBeUndefined();
  });
});
