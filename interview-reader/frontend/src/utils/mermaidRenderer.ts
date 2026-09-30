import type {MermaidConfig} from "mermaid";
import type {ReaderTheme} from "./readingComfort";

export const MERMAID_MAX_TEXT_SIZE = 50_000;
export const MERMAID_MAX_EDGES = 500;

export type MermaidRenderFailure = "too-large" | "unsupported" | "invalid";

export interface MermaidPalette {
    surface?: string;
    text?: string;
    muted?: string;
    line?: string;
    accent?: string;
    codeBackground?: string;
    fontFamily?: string;
}

export class MermaidRenderError extends Error {
    constructor(public readonly reason: MermaidRenderFailure, message: string, options?: ErrorOptions) {
        super(message, options);
        this.name = "MermaidRenderError";
    }
}

let mermaidPromise: ReturnType<typeof loadMermaid> | null = null;
let renderQueue: Promise<unknown> = Promise.resolve();

function loadMermaid() {
    return import("mermaid").then((module) => module.default);
}

function enqueueRender<T>(task: () => Promise<T>): Promise<T> {
    const queued = renderQueue.then(task, task);
    renderQueue = queued.then(() => undefined, () => undefined);
    return queued;
}

function presentValues(values: Record<string, string | undefined>): Record<string, string> {
    return Object.fromEntries(Object.entries(values).filter((entry): entry is [string, string] => !!entry[1]));
}

function mermaidConfig(theme: ReaderTheme, palette: MermaidPalette): MermaidConfig {
    return {
        startOnLoad: false,
        securityLevel: "strict",
        suppressErrorRendering: true,
        htmlLabels: false,
        maxTextSize: MERMAID_MAX_TEXT_SIZE,
        maxEdges: MERMAID_MAX_EDGES,
        theme: theme === "dark" ? "dark" : "base",
        fontFamily: palette.fontFamily,
        themeVariables: presentValues({
            background: palette.surface,
            primaryColor: palette.surface,
            primaryTextColor: palette.text,
            primaryBorderColor: palette.line,
            secondaryColor: palette.codeBackground,
            secondaryTextColor: palette.text,
            secondaryBorderColor: palette.line,
            tertiaryColor: palette.codeBackground,
            tertiaryTextColor: palette.text,
            tertiaryBorderColor: palette.line,
            lineColor: palette.muted,
            textColor: palette.text,
            mainBkg: palette.surface,
            nodeBorder: palette.accent,
            clusterBkg: palette.codeBackground,
            clusterBorder: palette.line,
            edgeLabelBackground: palette.surface,
        }),
        flowchart: {
            useMaxWidth: false,
        },
    };
}

export async function renderMermaidFlowchart(options: {
    id: string;
    source: string;
    theme: ReaderTheme;
    palette: MermaidPalette;
    container: Element;
}): Promise<string> {
    if (options.source.length > MERMAID_MAX_TEXT_SIZE) {
        throw new MermaidRenderError("too-large", `Mermaid source exceeds ${MERMAID_MAX_TEXT_SIZE} characters`);
    }

    return enqueueRender(async () => {
        try {
            mermaidPromise ??= loadMermaid();
            const mermaid = await mermaidPromise;
            const config = mermaidConfig(options.theme, options.palette);
            mermaid.initialize(config);
            const diagramType = mermaid.detectType(options.source, config);
            if (diagramType !== "flowchart" && diagramType !== "flowchart-v2") {
                throw new MermaidRenderError("unsupported", `Unsupported Mermaid diagram type: ${diagramType}`);
            }
            const result = await mermaid.render(options.id, options.source, options.container);
            return result.svg;
        } catch (error) {
            if (error instanceof MermaidRenderError) throw error;
            throw new MermaidRenderError("invalid", "Mermaid could not render the flowchart", {cause: error});
        }
    });
}
