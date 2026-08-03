import { flushPromises, mount } from "@vue/test-utils";
import { describe, expect, it, vi } from "vitest";
import FormulaBlock from "../components/FormulaBlock.vue";

describe("FormulaBlock", () => {
  it("renders valid LaTeX as accessible KaTeX markup", async () => {
    const wrapper = mount(FormulaBlock, { props: { latex: String.raw`\frac{n(n+1)}{2}` } });
    await flushPromises();
    await vi.waitFor(() => expect(wrapper.find(".katex").exists()).toBe(true));

    expect(wrapper.find("math").exists()).toBe(true);
    expect(wrapper.text()).not.toContain("公式暂时无法渲染");
  });

  it("keeps invalid source available behind a friendly fallback", async () => {
    const wrapper = mount(FormulaBlock, { props: { latex: String.raw`\frac{` } });
    await flushPromises();
    await vi.waitFor(() => expect(wrapper.find(".formula-fallback").exists()).toBe(true));

    expect(wrapper.get(".formula-fallback").text()).toContain("公式暂时无法渲染");
    expect(wrapper.get("code").text()).toContain(String.raw`\frac{`);
  });
});
