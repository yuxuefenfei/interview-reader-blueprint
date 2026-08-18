<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import type { ReaderTheme } from "../utils/readingComfort";
import {
  MermaidRenderError,
  renderMermaidFlowchart,
  type MermaidPalette,
  type MermaidRenderFailure,
} from "../utils/mermaidRenderer";

const props = defineProps<{
  source: string;
  diagramId: string;
  theme: ReaderTheme;
  fitWidth?: boolean;
}>();
const emit = defineEmits<{
  error: [reason: MermaidRenderFailure];
  rendered: [];
}>();

const host = ref<HTMLElement | null>(null);
const canvas = ref<HTMLElement | null>(null);
const svg = ref("");
const rendering = ref(false);
const nearViewport = ref(false);
let observer: IntersectionObserver | null = null;
let renderTimer: number | null = null;
let renderRequestId = 0;

const canvasClass = computed(() => props.fitWidth ? "fit-width" : "reading-size");

function cssToken(name: string): string | undefined {
  const element = host.value;
  if (!element) return undefined;
  const localValue = getComputedStyle(element).getPropertyValue(name).trim();
  if (localValue) return localValue;
  const rootValue = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return rootValue || undefined;
}

function currentPalette(): MermaidPalette {
  return {
    surface: cssToken("--reader-surface") ?? cssToken("--surface-1"),
    text: cssToken("--reader-text") ?? cssToken("--ink-900"),
    muted: cssToken("--reader-muted") ?? cssToken("--ink-500"),
    line: cssToken("--reader-line") ?? cssToken("--line-200"),
    accent: cssToken("--reader-accent") ?? cssToken("--brand-500"),
    codeBackground: cssToken("--reader-code-bg") ?? cssToken("--surface-2"),
    fontFamily: getComputedStyle(host.value ?? document.documentElement).fontFamily || undefined,
  };
}

async function renderDiagram(): Promise<void> {
  if (!nearViewport.value || !canvas.value) return;
  const requestId = ++renderRequestId;
  rendering.value = true;
  svg.value = "";
  try {
    await nextTick();
    const rendered = await renderMermaidFlowchart({
      id: `mermaid-${props.diagramId}-${requestId}`.replace(/[^a-zA-Z0-9_-]/g, "-"),
      source: props.source,
      theme: props.theme,
      palette: currentPalette(),
      container: canvas.value,
    });
    if (requestId !== renderRequestId) return;
    svg.value = rendered;
    emit("rendered");
  } catch (error) {
    if (requestId !== renderRequestId) return;
    emit("error", error instanceof MermaidRenderError ? error.reason : "invalid");
  } finally {
    if (requestId === renderRequestId) rendering.value = false;
  }
}

function scheduleRender(): void {
  if (!nearViewport.value) return;
  if (renderTimer !== null) window.clearTimeout(renderTimer);
  renderTimer = window.setTimeout(() => {
    renderTimer = null;
    void renderDiagram();
  }, svg.value ? 180 : 0);
}

watch(() => [props.source, props.theme] as const, scheduleRender);

onMounted(() => {
  if (typeof IntersectionObserver === "undefined") {
    nearViewport.value = true;
    scheduleRender();
    return;
  }
  observer = new IntersectionObserver((entries) => {
    if (!entries.some((entry) => entry.isIntersecting)) return;
    observer?.disconnect();
    observer = null;
    nearViewport.value = true;
    scheduleRender();
  }, { rootMargin: "400px 0px" });
  if (host.value) observer.observe(host.value);
});

onBeforeUnmount(() => {
  renderRequestId += 1;
  observer?.disconnect();
  if (renderTimer !== null) window.clearTimeout(renderTimer);
});
</script>

<template>
  <div ref="host" class="mermaid-diagram" :aria-busy="rendering">
    <div ref="canvas" class="mermaid-canvas" :class="canvasClass" v-html="svg"></div>
    <p v-if="rendering && !svg" class="mermaid-status" role="status">正在渲染流程图…</p>
    <p v-else-if="!nearViewport" class="mermaid-status">滚动到附近时渲染流程图</p>
  </div>
</template>

<style scoped>
.mermaid-diagram {
  width: 100%;
  min-width: 0;
  max-width: 100%;
  min-height: 96px;
  overflow: hidden;
  background: var(--reader-surface, var(--surface-1));
}

.mermaid-canvas {
  width: 100%;
  min-width: 0;
  max-width: 100%;
  max-height: min(72vh, 720px);
  overflow: auto;
  padding: 18px;
  overscroll-behavior: contain;
}

.mermaid-canvas :deep(svg) {
  display: block;
  height: auto;
  margin: 0 auto;
}

.mermaid-canvas.reading-size :deep(svg) {
  width: auto;
  max-width: none !important;
}

.mermaid-canvas.fit-width :deep(svg) {
  width: 100%;
  max-width: 100% !important;
}

.mermaid-status {
  display: grid;
  min-height: 96px;
  place-items: center;
  margin: 0;
  padding: 18px;
  color: var(--reader-muted, var(--ink-500));
  font-size: 13px;
}
</style>
