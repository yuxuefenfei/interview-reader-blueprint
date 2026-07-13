<script setup lang="ts">
import type { ContentBlock } from "../types/api";

defineProps<{
  block: ContentBlock;
}>();

function textFromPayload(payload: Record<string, unknown>, fallback: string): string {
  return typeof payload.text === "string" ? payload.text : fallback;
}

function codeTextFromPayload(payload: Record<string, unknown>, fallback: string): string {
  return textFromPayload(payload, fallback)
    .replace(/\r\n?/g, "\n")
    .replace(/}\s+(?=[\u4e00-\u9fff])/g, "}\n");
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
      <figcaption>{{ codeLanguage(block.payload, block) }}</figcaption>
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
