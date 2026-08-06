import { flushPromises, mount } from "@vue/test-utils";
import { defineComponent } from "vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import ReadonlyIdentifier from "../components/ReadonlyIdentifier.vue";

const ButtonStub = defineComponent({
  inheritAttrs: false,
  props: { disabled: Boolean },
  emits: ["click"],
  template: '<button v-bind="$attrs" :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
});

const TooltipStub = defineComponent({
  props: { content: { type: String, default: "" } },
  template: '<span class="tooltip-stub" :data-content="content"><slot /></span>',
});

describe("ReadonlyIdentifier", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("preserves mixed-language identifiers and copies the exact value", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal("navigator", { clipboard: { writeText } });
    const value = "Java并发-guide_2026";
    const wrapper = mount(ReadonlyIdentifier, {
      props: { value },
      global: {
        stubs: {
          ElButton: ButtonStub,
          ElTooltip: TooltipStub,
        },
      },
    });

    expect(wrapper.get("code").text()).toBe(value);
    await wrapper.get("button").trigger("click");
    await flushPromises();

    expect(writeText).toHaveBeenCalledWith(value);
    expect(wrapper.get(".tooltip-stub").attributes("data-content")).toBe("已复制");
    expect(wrapper.get("button").classes()).toContain("is-copied");
    expect(wrapper.get("button").attributes("aria-label")).toBe("已复制");
    wrapper.unmount();
  });
});
