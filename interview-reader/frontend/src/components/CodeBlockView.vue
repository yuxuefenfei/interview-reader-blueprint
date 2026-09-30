<script lang="ts" setup>
import {computed, type CSSProperties, defineAsyncComponent, onBeforeUnmount, ref, watch} from "vue";
import type {ReaderTheme} from "../utils/readingComfort";
import {highlightCode} from "../utils/codeHighlight";
import type {MermaidRenderFailure} from "../utils/mermaidRenderer";

const MermaidDiagram = defineAsyncComponent(() => import("./MermaidDiagram.vue"));

const props = withDefaults(defineProps<{
  blockId: string;
  code: string;
  language: string;
  theme?: ReaderTheme;
  wrap?: boolean;
  showWrapToggle?: boolean;
}>(), {
  theme: "light",
  wrap: false,
  showWrapToggle: false,
});
const emit = defineEmits<{ "update:wrap": [value: boolean] }>();

const normalizedCode = computed(() => props.code.replace(/\r\n?/g, "\n"));
const normalizedLanguage = computed(() => props.language.trim().toLowerCase() || "text");
const isMermaid = computed(() => normalizedLanguage.value === "mermaid");
const displayMode = ref<"diagram" | "source">("diagram");
const fitWidth = ref(false);
const diagramNotice = ref("");
const copyLabel = ref("复制代码");
const highlightedCode = ref<string | null>(null);
const codeHighlightPending = ref(false);
let copyLabelTimer: number | null = null;
let codeHighlightRequestId = 0;

const codeBlockStyle = computed<CSSProperties | undefined>(() => props.wrap ? {
  whiteSpace: "pre-wrap",
  overflowWrap: "anywhere"
} : undefined);
const showSource = computed(() => !isMermaid.value || displayMode.value === "source");

watch([normalizedCode, normalizedLanguage, showSource], async ([code, language, shouldHighlight]) => {
  const requestId = ++codeHighlightRequestId;
  highlightedCode.value = null;
  if (!shouldHighlight) {
    codeHighlightPending.value = false;
    return;
  }
  codeHighlightPending.value = true;
  try {
    const highlighted = await highlightCode(code, language);
    if (requestId === codeHighlightRequestId) highlightedCode.value = highlighted;
  } catch {
    if (requestId === codeHighlightRequestId) highlightedCode.value = null;
  } finally {
    if (requestId === codeHighlightRequestId) codeHighlightPending.value = false;
  }
}, {immediate: true});

watch([normalizedCode, normalizedLanguage], () => {
  displayMode.value = "diagram";
  fitWidth.value = false;
  diagramNotice.value = "";
});

function diagramFailureMessage(reason: MermaidRenderFailure): string {
  if (reason === "too-large") return "流程图规模过大，请查看源码。";
  if (reason === "unsupported") return "当前仅支持 Mermaid 流程图，请查看源码。";
  return "流程图暂时无法渲染，请检查语法或查看源码。";
}

function handleDiagramError(reason: MermaidRenderFailure): void {
  diagramNotice.value = diagramFailureMessage(reason);
  displayMode.value = "source";
}

function showDiagram(): void {
  diagramNotice.value = "";
  displayMode.value = "diagram";
}

async function copyCode(): Promise<void> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(normalizedCode.value);
    } else {
      const textArea = document.createElement("textarea");
      textArea.value = normalizedCode.value;
      textArea.style.position = "fixed";
      textArea.style.opacity = "0";
      document.body.append(textArea);
      textArea.select();
      const copied = document.execCommand("copy");
      textArea.remove();
      if (!copied) throw new Error("Clipboard unavailable");
    }
    copyLabel.value = "已复制";
  } catch {
    copyLabel.value = "复制失败";
  }
  if (copyLabelTimer !== null) window.clearTimeout(copyLabelTimer);
  copyLabelTimer = window.setTimeout(() => {
    copyLabel.value = "复制代码";
    copyLabelTimer = null;
  }, 1_600);
}

onBeforeUnmount(() => {
  codeHighlightRequestId += 1;
  if (copyLabelTimer !== null) window.clearTimeout(copyLabelTimer);
});
</script>

<template>
  <figure :class="{ 'mermaid-code-block': isMermaid }" class="code-block">
    <figcaption>
      <span>{{ normalizedLanguage }}</span>
      <div class="ui-action-row ui-action-row--compact ui-action-row--nowrap code-block-actions">
        <template v-if="isMermaid">
          <button :aria-pressed="displayMode === 'diagram'" :class="{ active: displayMode === 'diagram' }" class="code-copy code-toolbar-button"
                  type="button" @click="showDiagram">图形
          </button>
          <button :aria-pressed="displayMode === 'source'" :class="{ active: displayMode === 'source' }" class="code-copy code-toolbar-button"
                  type="button" @click="displayMode = 'source'">源码
          </button>
          <button v-if="displayMode === 'diagram'" :aria-label="fitWidth ? '切换到阅读尺寸' : '使流程图适应正文宽度'" :aria-pressed="fitWidth"
                  class="code-copy code-toolbar-button" type="button"
                  @click="fitWidth = !fitWidth">{{ fitWidth ? "阅读尺寸" : "适应宽度" }}
          </button>
        </template>
        <button v-if="showWrapToggle && showSource" :aria-label="wrap ? '关闭代码自动换行' : '启用代码自动换行'" :aria-pressed="wrap"
                :title="wrap ? '关闭自动换行' : '自动换行'" class="code-copy code-toolbar-button"
                type="button" @click="emit('update:wrap', !wrap)">换行
        </button>
        <button :aria-label="copyLabel" :title="copyLabel" class="code-copy" type="button" @click="copyCode">
          <svg aria-hidden="true" viewBox="0 0 24 24">
            <path
                d="M9 8V5a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-3M5 9h8a2 2 0 0 1 2 2v8a2 2 0 0 1 2-2v-8a2 2 0 0 1 2-2Z"/>
          </svg>
        </button>
      </div>
    </figcaption>

    <p v-if="diagramNotice" class="diagram-notice" role="status">{{ diagramNotice }}</p>
    <Suspense v-if="isMermaid && displayMode === 'diagram'">
      <MermaidDiagram :diagram-id="blockId" :fit-width="fitWidth" :source="normalizedCode" :theme="theme"
                      @error="handleDiagramError" @rendered="diagramNotice = ''"/>
      <template #fallback><p class="mermaid-component-loading" role="status">正在加载流程图组件…</p></template>
    </Suspense>
    <pre v-else :aria-busy="codeHighlightPending" :style="codeBlockStyle"><code v-if="highlightedCode !== null"
                                                                                class="hljs"
                                                                                v-html="highlightedCode"></code><code
        v-else>{{ normalizedCode }}</code></pre>
  </figure>
</template>
