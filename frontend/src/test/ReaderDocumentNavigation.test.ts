import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import ReaderDocumentList from "../components/ReaderDocumentList.vue";
import ReaderDocumentSelector from "../components/ReaderDocumentSelector.vue";
import type { DocumentSummary } from "../types/api";

function document(index: number, progressRatio = 0): DocumentSummary {
  return {
    id: `document-${index}`,
    code: `DOC-${index}`,
    title: `文档 ${index}`,
    description: null,
    currentVersionId: `version-${index}`,
    progressRatio,
  };
}

describe("reader document navigation", () => {
  it("renders the compact selector progress and emits open", async () => {
    const wrapper = mount(ReaderDocumentSelector, {
      props: { document: { ...document(1, 0.456), title: "Redis 高级面试题" } },
    });

    expect(wrapper.text()).toContain("Redis 高级面试题");
    expect(wrapper.text()).toContain("46%");
    expect(wrapper.get(".reader-document-selector-chevron").element.tagName.toLowerCase()).toBe("svg");
    expect(wrapper.get(".reader-document-selector-progress i").attributes("style")).toContain("width: 45.6%");
    await wrapper.get("button").trigger("click");
    expect(wrapper.emitted("open")).toHaveLength(1);
  });

  it("distinguishes started, incomplete, and completed reading positions", async () => {
    const wrapper = mount(ReaderDocumentSelector, {
      props: { document: document(1, 0.004) },
    });
    expect(wrapper.text()).toContain("<1%");

    await wrapper.setProps({ document: document(1, 0.999) });
    expect(wrapper.text()).not.toContain("100%");

    await wrapper.setProps({ document: document(1, 1) });
    expect(wrapper.text()).toContain("100%");
  });

  it("emits a remote filter query for long lists and exposes compact current/progress state", async () => {
    const shortList = mount(ReaderDocumentList, {
      props: {
        documents: [document(1, 0.34), document(2, 0.8)],
        selectedDocumentId: "document-1",
        pendingDocumentId: null,
      },
    });
    expect(shortList.find('input[type="search"]').exists()).toBe(false);
    expect(shortList.get(".reader-document-option.active").attributes("aria-current")).toBe("page");
    expect(shortList.get(".reader-document-option.active").text()).toContain("34%");

    const longList = mount(ReaderDocumentList, {
      props: {
        documents: Array.from({ length: 9 }, (_, index) => document(index + 1)),
        selectedDocumentId: "document-1",
        pendingDocumentId: null,
      },
    });
    const filter = longList.get('input[type="search"]');
    await filter.setValue("DOC-9");
    expect(longList.emitted("update:query")?.at(-1)).toEqual(["DOC-9"]);
    await longList.setProps({ documents: [document(9)] });
    expect(longList.findAll(".reader-document-option")).toHaveLength(1);
    expect(longList.get(".reader-document-option").text()).toContain("文档 9");
  });
});
