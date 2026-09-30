import {beforeEach, describe, expect, it, vi} from "vitest";
import {MERMAID_MAX_TEXT_SIZE, renderMermaidFlowchart,} from "../utils/mermaidRenderer";

const mermaid = vi.hoisted(() => ({
    initialize: vi.fn(),
    detectType: vi.fn(),
    render: vi.fn(),
}));

vi.mock("mermaid", () => ({default: mermaid}));

describe("mermaidRenderer", () => {
    beforeEach(() => {
        mermaid.initialize.mockReset();
        mermaid.detectType.mockReset().mockReturnValue("flowchart-v2");
        mermaid.render.mockReset().mockResolvedValue({svg: "<svg></svg>"});
    });

    it("applies strict limits and renders only flowcharts", async () => {
        const container = document.createElement("div");
        await expect(renderMermaidFlowchart({
            id: "diagram-1",
            source: "flowchart TD\nA --> B",
            theme: "dark",
            palette: {surface: "token-surface", text: "token-text", line: "token-line"},
            container,
        })).resolves.toBe("<svg></svg>");

        expect(mermaid.initialize).toHaveBeenCalledWith(expect.objectContaining({
            securityLevel: "strict",
            startOnLoad: false,
            htmlLabels: false,
            maxTextSize: 50_000,
            maxEdges: 500,
            theme: "dark",
        }));
        expect(mermaid.render).toHaveBeenCalledWith("diagram-1", "flowchart TD\nA --> B", container);
    });

    it("rejects oversized source before loading or initializing Mermaid", async () => {
        await expect(renderMermaidFlowchart({
            id: "diagram-large",
            source: "x".repeat(MERMAID_MAX_TEXT_SIZE + 1),
            theme: "light",
            palette: {},
            container: document.createElement("div"),
        })).rejects.toMatchObject({reason: "too-large"});
        expect(mermaid.initialize).not.toHaveBeenCalled();
    });

    it("rejects Mermaid diagram types outside the first-phase flowchart scope", async () => {
        mermaid.detectType.mockReturnValue("sequence");
        await expect(renderMermaidFlowchart({
            id: "diagram-sequence",
            source: "sequenceDiagram\nA->>B: hello",
            theme: "light",
            palette: {},
            container: document.createElement("div"),
        })).rejects.toMatchObject({reason: "unsupported"});
        expect(mermaid.render).not.toHaveBeenCalled();
    });
});
