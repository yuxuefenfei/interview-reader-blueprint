import { flushPromises, mount } from "@vue/test-utils";
import { describe, expect, it, vi } from "vitest";
import CodeBlockView from "../components/CodeBlockView.vue";

const MermaidDiagramStub = {
  props: ["source", "diagramId", "theme", "fitWidth"],
  emits: ["error", "rendered"],
  template: `
    <div class="mock-mermaid" :data-source="source" :data-theme="theme" :data-fit-width="String(fitWidth)">
      <button class="mock-mermaid-fail" type="button" @click="$emit('error', 'unsupported')">fail</button>
    </div>
  `,
};

const mermaidStubs = { global: { stubs: { MermaidDiagram: MermaidDiagramStub } } };

describe("CodeBlockView", () => {
  it("keeps ordinary code highlighting, wrapping and copy controls isolated from Mermaid", async () => {
    const wrapper = mount(CodeBlockView, {
      ...mermaidStubs,
      props: {
        blockId: "code-1",
        code: "class A {\n  void run() {}\n}",
        language: "java",
        wrap: false,
        showWrapToggle: true,
      },
    });

    await flushPromises();
    await vi.waitFor(() => expect(wrapper.find(".hljs-keyword").exists()).toBe(true));
    expect(wrapper.get("pre").element.textContent).toBe("class A {\n  void run() {}\n}");
    expect(wrapper.find(".mock-mermaid").exists()).toBe(false);

    await wrapper.get('[aria-label="启用代码自动换行"]').trigger("click");
    expect(wrapper.emitted("update:wrap")).toEqual([[true]]);
  });

  it("shows Mermaid visually by default and keeps source and sizing controls local", async () => {
    const wrapper = mount(CodeBlockView, {
      ...mermaidStubs,
      props: {
        blockId: "diagram-1",
        code: "flowchart TD\nA --> B",
        language: "mermaid",
        theme: "sepia",
      },
    });

    await flushPromises();
    expect(wrapper.get(".mock-mermaid").attributes("data-theme")).toBe("sepia");
    expect(wrapper.get(".mock-mermaid").attributes("data-fit-width")).toBe("false");
    expect(wrapper.find("pre").exists()).toBe(false);

    await wrapper.get('[aria-label="使流程图适应正文宽度"]').trigger("click");
    await flushPromises();
    expect(wrapper.get(".mock-mermaid").attributes("data-fit-width")).toBe("true");

    await wrapper.get('button[aria-pressed="false"]').trigger("click");
    expect(wrapper.get("pre").text()).toContain("flowchart TD");
  });

  it("falls back to escaped source when the Mermaid renderer rejects the diagram type", async () => {
    const wrapper = mount(CodeBlockView, {
      ...mermaidStubs,
      props: {
        blockId: "diagram-2",
        code: "sequenceDiagram\nA->>B: hello",
        language: "mermaid",
      },
    });

    await flushPromises();
    await wrapper.get(".mock-mermaid-fail").trigger("click");
    expect(wrapper.get(".diagram-notice").text()).toContain("仅支持 Mermaid 流程图");
    expect(wrapper.get("pre").text()).toContain("sequenceDiagram");
  });
});
