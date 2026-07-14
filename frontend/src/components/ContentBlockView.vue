<script setup lang="ts">
import { onBeforeUnmount, ref } from "vue";
import type { ContentBlock } from "../types/api";

const copyLabel = ref("复制代码");
let copyLabelTimer: number | null = null;

defineProps<{
  block: ContentBlock;
}>();

onBeforeUnmount(() => {
  if (copyLabelTimer !== null) {
    window.clearTimeout(copyLabelTimer);
  }
});

function textFromPayload(payload: Record<string, unknown>, fallback: string): string {
  return typeof payload.text === "string" ? payload.text : fallback;
}

function codeTextFromPayload(payload: Record<string, unknown>, fallback: string): string {
  return textFromPayload(payload, fallback)
    .replace(/\r\n?/g, "\n")
    .replace(/}\s+@(?=[A-Za-z])/g, "}\n@");
}

function itemsFromPayload(payload: Record<string, unknown>): string[] {
  return Array.isArray(payload.items) ? payload.items.map(String) : [];
}

function tableColumns(payload: Record<string, unknown>): string[] {
  return Array.isArray(payload.columns) ? payload.columns.map(String) : [];
}

function tableRows(payload: Record<string, unknown>): string[][] {
  return Array.isArray(payload.rows)
    ? payload.rows.map((row) => (Array.isArray(row) ? row.map(String) : [String(row)]))
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
      {{ textFromPayload(block.payload, block.plainText) }}
    </p>

    <p v-else-if="block.blockType === 'heading_note'" class="heading-note">
      {{ textFromPayload(block.payload, block.plainText) }}
    </p>

    <ul v-else-if="block.blockType === 'unordered_list'" class="reader-list">
      <li v-for="item in itemsFromPayload(block.payload)" :key="item">{{ item }}</li>
    </ul>

    <ol v-else-if="block.blockType === 'ordered_list'" class="reader-list">
      <li v-for="item in itemsFromPayload(block.payload)" :key="item">{{ item }}</li>
    </ol>

    <figure v-else-if="block.blockType === 'code'" class="code-block">
      <figcaption>
        <span>{{ codeLanguage(block.payload, block) }}</span>
        <button class="code-copy" type="button" :aria-label="copyLabel" :title="copyLabel" @click="copyCode(block)">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 8V5a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-3M5 9h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2Z" /></svg>
        </button>
      </figcaption>
      <pre><code>{{ codeTextFromPayload(block.payload, block.plainText) }}</code></pre>
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
      <figcaption>table snapshot</figcaption>
      <pre>{{ textFromPayload(block.payload, block.plainText) }}</pre>
    </figure>

    <blockquote v-else-if="block.blockType === 'quote'">
      {{ textFromPayload(block.payload, block.plainText) }}
    </blockquote>

    <aside v-else-if="block.blockType === 'callout'" class="callout">
      <strong v-if="typeof block.payload.title === 'string'">{{ block.payload.title }}</strong>
      <span>{{ textFromPayload(block.payload, block.plainText) }}</span>
    </aside>

    <p v-else-if="block.blockType === 'formula'" class="formula">
      {{ typeof block.payload.latex === "string" ? block.payload.latex : block.plainText }}
    </p>

    <figure v-else-if="block.blockType === 'image'" class="image-placeholder">
      <span>{{ typeof block.payload.alt === "string" ? block.payload.alt : "Image" }}</span>
    </figure>

    <hr v-else-if="block.blockType === 'divider'" />

    <pre v-else class="fallback-block">{{ block.plainText || JSON.stringify(block.payload, null, 2) }}</pre>
  </article>
</template>
