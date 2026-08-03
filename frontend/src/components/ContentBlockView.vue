<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch, type CSSProperties } from "vue";
import type { ContentBlock } from "../types/api";
import { highlightCode } from "../utils/codeHighlight";
import FormulaBlock from "./FormulaBlock.vue";
import InlineMarkdown from "./InlineMarkdown.vue";

const copyLabel = ref("复制代码");
let copyLabelTimer: number | null = null;

const props = defineProps<{
  block: ContentBlock;
  assetBaseUrl?: string;
  highlight?: string;
  wrapCode?: boolean;
  showCodeWrapToggle?: boolean;
}>();
const emit = defineEmits<{ "update:wrapCode": [value: boolean] }>();

const imageLoadFailed = ref(false);
const imageRetryKey = ref(0);
const imagePreviewOpen = ref(false);
const imagePreviewScale = ref(1);
const imagePreviewZoomOut = ref<HTMLButtonElement | null>(null);
const imagePreviewZoomIn = ref<HTMLButtonElement | null>(null);
const imagePreviewClose = ref<HTMLButtonElement | null>(null);
let previousFocus: HTMLElement | null = null;
const imageLightboxStyle: CSSProperties = {
  position: "fixed", zIndex: "3000", inset: "0", display: "grid", gridTemplateRows: "auto minmax(0, 1fr)",
  padding: "max(16px, env(safe-area-inset-top)) max(16px, env(safe-area-inset-right)) max(16px, env(safe-area-inset-bottom)) max(16px, env(safe-area-inset-left))",
  background: "rgba(7, 15, 24, .92)",
};
const imageLightboxToolbarStyle: CSSProperties = { justifyContent: "flex-end", minHeight: "44px", color: "#f8fafc" };
const imageLightboxButtonStyle: CSSProperties = { minHeight: "36px", padding: "0 11px", border: "1px solid rgba(255, 255, 255, .42)", borderRadius: "var(--radius-sm)", background: "rgba(255, 255, 255, .1)", color: "inherit", font: "inherit" };
const imageLightboxStageStyle: CSSProperties = { minWidth: "0", minHeight: "0", overflow: "auto", display: "grid", placeItems: "center", padding: "16px" };
const imageLightboxImageStyle = computed<CSSProperties>(() => ({ display: "block", maxWidth: "100%", maxHeight: "100%", objectFit: "contain", transformOrigin: "center", transition: "transform 140ms ease-out", transform: `scale(${imagePreviewScale.value})` }));
const codeBlockStyle = computed<CSSProperties | undefined>(() => props.wrapCode ? { whiteSpace: "pre-wrap", overflowWrap: "anywhere" } : undefined);
const codeText = computed(() => codeTextFromPayload(props.block.payload, props.block.plainText));
const codeLanguageName = computed(() => codeLanguage(props.block.payload, props.block));
const highlightedCode = ref<string | null>(null);
const codeHighlightPending = ref(false);
let codeHighlightRequestId = 0;
const imageAssetKey = computed(() => typeof props.block.payload.assetKey === "string" ? props.block.payload.assetKey.trim() : "");
const imageAlt = computed(() => typeof props.block.payload.alt === "string" ? props.block.payload.alt : props.block.plainText);
const imageCaption = computed(() => typeof props.block.payload.caption === "string" ? props.block.payload.caption : "");
const formulaLatex = computed(() => typeof props.block.payload.latex === "string" ? props.block.payload.latex : props.block.plainText);
const imageDecorative = computed(() => props.block.payload.decorative === true);
const imageUrl = computed(() => {
  if (imageAssetKey.value && props.assetBaseUrl) return `${props.assetBaseUrl.replace(/\/$/, "")}/${encodeURIComponent(imageAssetKey.value)}`;
  return typeof props.block.payload.src === "string" ? props.block.payload.src : typeof props.block.payload.url === "string" ? props.block.payload.url : "";
});

// A block keeps its component instance while the editor broadcasts a new asset key.
// Retry the image when that immutable resource URL changes instead of retaining an old load failure.
watch(imageUrl, () => {
  imageLoadFailed.value = false;
  imageRetryKey.value = 0;
  closeImagePreview();
});
watch([codeText, codeLanguageName], async ([text, language]) => {
  const requestId = ++codeHighlightRequestId;
  highlightedCode.value = null;
  codeHighlightPending.value = true;
  try {
    const highlighted = await highlightCode(text, language);
    if (requestId === codeHighlightRequestId) highlightedCode.value = highlighted;
  } catch {
    if (requestId === codeHighlightRequestId) highlightedCode.value = null;
  } finally {
    if (requestId === codeHighlightRequestId) codeHighlightPending.value = false;
  }
}, { immediate: true });

onBeforeUnmount(() => {
  codeHighlightRequestId += 1;
  document.removeEventListener("keydown", handleImagePreviewKeydown);
  if (copyLabelTimer !== null) {
    window.clearTimeout(copyLabelTimer);
  }
});

function textFromPayload(payload: Record<string, unknown>, fallback: string): string {
  return typeof payload.text === "string" ? payload.text : fallback;
}

function codeTextFromPayload(payload: Record<string, unknown>, fallback: string): string {
  return textFromPayload(payload, fallback).replace(/\r\n?/g, "\n");
}

function itemsFromPayload(payload: Record<string, unknown>): string[] {
  return Array.isArray(payload.items) ? payload.items.map(String) : [];
}

function openImagePreview(event?: MouseEvent): void {
  if (!imageUrl.value || imageLoadFailed.value) return;
  previousFocus = event?.currentTarget instanceof HTMLElement
    ? event.currentTarget
    : document.activeElement instanceof HTMLElement ? document.activeElement : null;
  imagePreviewScale.value = 1;
  imagePreviewOpen.value = true;
  document.addEventListener("keydown", handleImagePreviewKeydown);
  void nextTick(() => imagePreviewClose.value?.focus());
}

function closeImagePreview(): void {
  if (!imagePreviewOpen.value) return;
  imagePreviewOpen.value = false;
  document.removeEventListener("keydown", handleImagePreviewKeydown);
  const focusTarget = previousFocus;
  previousFocus = null;
  void nextTick(() => focusTarget?.focus());
}

function adjustImagePreviewScale(delta: number): void {
  imagePreviewScale.value = Math.min(3, Math.max(1, Math.round((imagePreviewScale.value + delta) * 100) / 100));
}

function retryImageLoad(): void {
  imageRetryKey.value += 1;
  imageLoadFailed.value = false;
}

function handleImagePreviewKeydown(event: KeyboardEvent): void {
  if (event.key === "Escape") {
    event.preventDefault();
    closeImagePreview();
    return;
  }
  if (event.key !== "Tab") return;

  const controls = [imagePreviewZoomOut.value, imagePreviewZoomIn.value, imagePreviewClose.value]
    .filter((control): control is HTMLButtonElement => !!control && !control.disabled);
  if (controls.length === 0) return;
  event.preventDefault();
  const currentIndex = controls.indexOf(document.activeElement as HTMLButtonElement);
  const offset = event.shiftKey ? -1 : 1;
  const nextIndex = currentIndex < 0
    ? event.shiftKey ? controls.length - 1 : 0
    : (currentIndex + offset + controls.length) % controls.length;
  controls[nextIndex].focus();
}

function tableCellText(value: unknown): string {
  return String(value).replace(/`([^`\r\n]+)`/g, "$1").trim();
}

function tableColumns(payload: Record<string, unknown>): string[] {
  return Array.isArray(payload.columns) ? payload.columns.map(tableCellText) : [];
}

function tableRows(payload: Record<string, unknown>): string[][] {
  return Array.isArray(payload.rows)
    ? payload.rows.map((row) => (Array.isArray(row) ? row.map(tableCellText) : [tableCellText(row)]))
    : [];
}

function codeLanguage(payload: Record<string, unknown>, block: ContentBlock): string {
  return typeof payload.language === "string" ? payload.language : block.blockType === "code" ? "text" : "";
}

async function copyCode(block: ContentBlock): Promise<void> {
  const text = codeTextFromPayload(block.payload, block.plainText);
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
    } else {
      const textArea = document.createElement("textarea");
      textArea.value = text;
      textArea.style.position = "fixed";
      textArea.style.opacity = "0";
      document.body.append(textArea);
      textArea.select();
      const copied = document.execCommand("copy");
      textArea.remove();
      if (!copied) {
        throw new Error("Clipboard unavailable");
      }
    }
    copyLabel.value = "已复制";
  } catch {
    copyLabel.value = "复制失败";
  }
  if (copyLabelTimer !== null) {
    window.clearTimeout(copyLabelTimer);
  }
  copyLabelTimer = window.setTimeout(() => {
    copyLabel.value = "复制代码";
    copyLabelTimer = null;
  }, 1_600);
}
</script>

<template>
  <article class="content-block" :data-block-id="block.id">
    <p v-if="block.blockType === 'paragraph'" class="paragraph">
      <InlineMarkdown :text="textFromPayload(block.payload, block.plainText)" :highlight="highlight" />
    </p>

    <p v-else-if="block.blockType === 'heading_note'" class="heading-note">
      <InlineMarkdown :text="textFromPayload(block.payload, block.plainText)" :highlight="highlight" />
    </p>

    <ul v-else-if="block.blockType === 'unordered_list'" class="reader-list">
      <li v-for="item in itemsFromPayload(block.payload)" :key="item"><InlineMarkdown :text="item" :highlight="highlight" /></li>
    </ul>

    <ol v-else-if="block.blockType === 'ordered_list'" class="reader-list">
      <li v-for="item in itemsFromPayload(block.payload)" :key="item"><InlineMarkdown :text="item" :highlight="highlight" /></li>
    </ol>

    <figure v-else-if="block.blockType === 'code'" class="code-block">
      <figcaption>
        <span>{{ codeLanguageName }}</span>
        <div class="ui-action-row ui-action-row--compact ui-action-row--nowrap">
          <button v-if="showCodeWrapToggle" class="code-copy" type="button" style="width:auto;padding:0 8px" :aria-pressed="!!wrapCode" :aria-label="wrapCode ? '关闭代码自动换行' : '启用代码自动换行'" :title="wrapCode ? '关闭自动换行' : '自动换行'" @click="emit('update:wrapCode', !wrapCode)">换行</button>
          <button class="code-copy" type="button" :aria-label="copyLabel" :title="copyLabel" @click="copyCode(block)">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 8V5a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-3M5 9h8a2 2 0 0 1 2 2v8a2 2 0 0 1 2-2v-8a2 2 0 0 1 2-2Z" /></svg>
          </button>
        </div>
      </figcaption>
      <pre :style="codeBlockStyle" :aria-busy="codeHighlightPending"><code v-if="highlightedCode !== null" class="hljs" v-html="highlightedCode"></code><code v-else>{{ codeText }}</code></pre>
    </figure>

    <div v-else-if="block.blockType === 'table'" class="table-wrap">
      <table>
        <thead v-if="tableColumns(block.payload).length">
          <tr>
            <th v-for="column in tableColumns(block.payload)" :key="column">{{ column }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, rowIndex) in tableRows(block.payload)" :key="rowIndex">
            <td v-for="(cell, cellIndex) in row" :key="cellIndex">{{ cell }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <figure v-else-if="block.blockType === 'table_snapshot'" class="table-snapshot">
      <figcaption>表格快照</figcaption>
      <pre>{{ textFromPayload(block.payload, block.plainText) }}</pre>
    </figure>

    <blockquote v-else-if="block.blockType === 'quote'" class="callout">
      <InlineMarkdown :text="textFromPayload(block.payload, block.plainText)" :highlight="highlight" />
    </blockquote>

    <aside v-else-if="block.blockType === 'callout'" class="callout">
      <strong v-if="typeof block.payload.title === 'string'"><InlineMarkdown :text="block.payload.title" :highlight="highlight" /></strong>
      <span><InlineMarkdown :text="textFromPayload(block.payload, block.plainText)" :highlight="highlight" /></span>
    </aside>

    <FormulaBlock v-else-if="block.blockType === 'formula'" :latex="formulaLatex" />

    <figure v-else-if="block.blockType === 'image'" class="image-block" :class="{ unavailable: !imageUrl || imageLoadFailed }">
      <button v-if="imageUrl && !imageLoadFailed" type="button" style="width:100%;padding:0;border:0;border-radius:var(--radius-sm);background:transparent;cursor:zoom-in" :aria-label="`查看大图：${imageAlt || '图片'}`" @click="openImagePreview">
        <img :key="`${imageUrl}:${imageRetryKey}`" :src="imageUrl" :alt="imageDecorative ? '' : imageAlt" loading="lazy" decoding="async" @error="imageLoadFailed = true" />
      </button>
      <div v-if="!imageUrl || imageLoadFailed" class="image-unavailable-message" role="status">
        <strong>{{ imageLoadFailed ? "图片加载失败" : "图片当前不可用" }}</strong>
        <span>{{ imageLoadFailed ? "请检查网络后重试。" : "当前内容没有可用的图片地址。" }}</span>
        <button v-if="imageUrl" type="button" @click="retryImageLoad">重新加载</button>
      </div>
      <figcaption v-if="imageCaption || ((!imageUrl || imageLoadFailed) && !imageDecorative && imageAlt)">{{ imageCaption || imageAlt }}</figcaption>
    </figure>

    <hr v-else-if="block.blockType === 'divider'" />

    <pre v-else class="fallback-block">{{ block.plainText || JSON.stringify(block.payload, null, 2) }}</pre>

    <Teleport to="body">
      <div
        v-if="imagePreviewOpen && imageUrl"
        :style="imageLightboxStyle"
        role="dialog"
        aria-modal="true"
        :aria-label="`图片预览：${imageAlt || '图片'}`"
        @click.self="closeImagePreview"
      >
        <div class="ui-action-row ui-action-row--nowrap" :style="imageLightboxToolbarStyle">
          <button ref="imagePreviewZoomOut" type="button" :style="imageLightboxButtonStyle" :disabled="imagePreviewScale <= 1" aria-label="缩小图片" @click="adjustImagePreviewScale(-0.25)">−</button>
          <output style="min-width:42px;text-align:center;font-size:13px;font-variant-numeric:tabular-nums" aria-live="polite">{{ Math.round(imagePreviewScale * 100) }}%</output>
          <button ref="imagePreviewZoomIn" type="button" :style="imageLightboxButtonStyle" :disabled="imagePreviewScale >= 3" aria-label="放大图片" @click="adjustImagePreviewScale(0.25)">＋</button>
          <button ref="imagePreviewClose" type="button" :style="imageLightboxButtonStyle" aria-label="关闭图片预览" @click="closeImagePreview">关闭</button>
        </div>
        <div :style="imageLightboxStageStyle">
          <img :src="imageUrl" :alt="imageDecorative ? '' : imageAlt" :style="imageLightboxImageStyle" />
        </div>
      </div>
    </Teleport>
  </article>
</template>
