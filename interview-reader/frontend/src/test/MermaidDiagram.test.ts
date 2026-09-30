import {flushPromises, mount} from "@vue/test-utils";
import {afterEach, beforeEach, describe, expect, it, vi} from "vitest";
import MermaidDiagram from "../components/MermaidDiagram.vue";

const renderMermaidFlowchart = vi.hoisted(() => vi.fn());

vi.mock("../utils/mermaidRenderer", () => ({
    MermaidRenderError: class MermaidRenderError extends Error {
        constructor(public readonly reason: "too-large" | "unsupported" | "invalid", message: string) {
            super(message);
        }
    },
    renderMermaidFlowchart,
}));

let intersectionCallback: IntersectionObserverCallback;

class IntersectionObserverStub {
    observe = vi.fn();
    disconnect = vi.fn();
    unobserve = vi.fn();
    takeRecords = vi.fn(() => []);
    root = null;
    rootMargin = "400px 0px";
    thresholds = [0];

    constructor(callback: IntersectionObserverCallback) {
        intersectionCallback = callback;
    }
}

describe("MermaidDiagram", () => {
    beforeEach(() => {
        vi.useFakeTimers();
        renderMermaidFlowchart.mockReset().mockResolvedValue('<svg role="graphics-document"></svg>');
        vi.stubGlobal("IntersectionObserver", IntersectionObserverStub);
    });

    afterEach(() => {
        vi.useRealTimers();
        vi.unstubAllGlobals();
    });

    it("does not load Mermaid until the diagram is near the viewport", async () => {
        const wrapper = mount(MermaidDiagram, {
            props: {source: "flowchart TD\nA --> B", diagramId: "diagram-1", theme: "light"},
        });

        expect(renderMermaidFlowchart).not.toHaveBeenCalled();
        intersectionCallback([{isIntersecting: true} as IntersectionObserverEntry], {} as IntersectionObserver);
        await vi.runAllTimersAsync();
        await flushPromises();

        expect(renderMermaidFlowchart).toHaveBeenCalledOnce();
        expect(wrapper.get(".mermaid-canvas").html()).toContain("graphics-document");
    });

    it("re-renders for a reading theme change and changes size through CSS only", async () => {
        const wrapper = mount(MermaidDiagram, {
            props: {source: "flowchart TD\nA --> B", diagramId: "diagram-2", theme: "light", fitWidth: false},
        });
        intersectionCallback([{isIntersecting: true} as IntersectionObserverEntry], {} as IntersectionObserver);
        await vi.runAllTimersAsync();
        await flushPromises();

        await wrapper.setProps({fitWidth: true});
        expect(wrapper.get(".mermaid-canvas").classes()).toContain("fit-width");
        expect(renderMermaidFlowchart).toHaveBeenCalledTimes(1);

        await wrapper.setProps({theme: "dark"});
        await vi.advanceTimersByTimeAsync(181);
        await flushPromises();
        expect(renderMermaidFlowchart).toHaveBeenCalledTimes(2);
        expect(renderMermaidFlowchart.mock.calls[1][0]).toEqual(expect.objectContaining({theme: "dark"}));
    });
});
