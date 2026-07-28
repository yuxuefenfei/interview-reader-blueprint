<script setup lang="ts">
import { computed } from "vue";
import type { DocumentSummary } from "../types/api";
import { formatProgressPercent } from "../utils/readingProgress";

const props = defineProps<{
  documents: DocumentSummary[];
  selectedDocumentId: string | null;
  pendingDocumentId: string | null;
  error?: string;
}>();

const emit = defineEmits<{
  select: [document: DocumentSummary];
}>();

const query = defineModel<string>("query", { default: "" });
const filteredDocuments = computed(() => {
  const normalizedQuery = query.value.trim().toLocaleLowerCase();
  if (!normalizedQuery) return props.documents;
  return props.documents.filter((document) =>
    `${document.title} ${document.code}`.toLocaleLowerCase().includes(normalizedQuery));
});

</script>

<template>
  <div class="reader-document-browser">
    <label v-if="documents.length > 8" class="reader-document-filter">
      <span class="sr-only">筛选文档</span>
      <input v-model="query" type="search" placeholder="筛选文档…" autocomplete="off" />
    </label>
    <p v-if="error" class="reader-document-switch-error" role="alert">{{ error }}</p>
    <div class="reader-document-list" aria-label="文档列表">
      <button
        v-for="document in filteredDocuments"
        :key="document.id"
        class="reader-document-option"
        :class="{ active: document.id === selectedDocumentId }"
        type="button"
        :aria-current="document.id === selectedDocumentId ? 'page' : undefined"
        :disabled="pendingDocumentId !== null"
        @click="emit('select', document)"
      >
        <strong :title="document.title">{{ document.title }}</strong>
        <small v-if="document.id === selectedDocumentId">当前</small>
        <small v-else-if="document.id === pendingDocumentId">加载中</small>
        <output :aria-label="`${document.title}阅读位置 ${formatProgressPercent(document.progressRatio)}`">
          {{ formatProgressPercent(document.progressRatio) }}
        </output>
      </button>
      <div v-if="filteredDocuments.length === 0" class="reader-toc-state">没有匹配的文档</div>
    </div>
  </div>
</template>
