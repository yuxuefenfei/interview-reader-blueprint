<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import type { DocumentSummary } from "../types/api";
import { formatProgressPercent } from "../utils/readingProgress";

const props = defineProps<{
  documents: DocumentSummary[];
  selectedDocumentId: string | null;
  pendingDocumentId: string | null;
  error?: string;
  loading?: boolean;
  hasMore?: boolean;
  loadError?: string;
}>();

const emit = defineEmits<{
  select: [document: DocumentSummary];
  loadMore: [];
}>();

const query = defineModel<string>("query", { default: "" });
const loadSentinel = ref<HTMLElement | null>(null);
let loadObserver: IntersectionObserver | null = null;

function requestMoreIfVisible(): void {
  const sentinel = loadSentinel.value;
  if (!sentinel || !props.hasMore || props.loading || props.loadError) return;
  const root = sentinel.closest(".reader-desktop-document-browser, .reader-mobile-document-browser");
  const sentinelRect = sentinel.getBoundingClientRect();
  const rootRect = root?.getBoundingClientRect();
  const visible = rootRect
    ? sentinelRect.top <= rootRect.bottom + 120 && sentinelRect.bottom >= rootRect.top
    : sentinelRect.top <= window.innerHeight + 120;
  if (visible) emit("loadMore");
}

function connectLoadObserver(): void {
  loadObserver?.disconnect();
  if (typeof IntersectionObserver === "undefined" || !loadSentinel.value) return;
  const root = loadSentinel.value.closest(".reader-desktop-document-browser, .reader-mobile-document-browser");
  loadObserver = new IntersectionObserver((entries) => {
    if (entries.some((entry) => entry.isIntersecting)) requestMoreIfVisible();
  }, { root, rootMargin: "0px 0px 160px", threshold: 0 });
  loadObserver.observe(loadSentinel.value);
}

watch(() => [props.loading, props.hasMore, props.loadError, props.documents.length], async () => {
  await nextTick();
  connectLoadObserver();
  requestMoreIfVisible();
});

onMounted(() => {
  connectLoadObserver();
  requestMoreIfVisible();
});

onBeforeUnmount(() => loadObserver?.disconnect());

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
        v-for="document in documents"
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
      <div v-if="documents.length === 0 && !loading" class="reader-toc-state">没有匹配的文档</div>
      <div v-if="loadError" class="reader-document-load-state" role="alert">
        <span>{{ loadError }}</span>
        <button type="button" @click="emit('loadMore')">重试</button>
      </div>
      <div v-else-if="loading" class="reader-document-load-state" role="status">正在加载文档…</div>
      <div v-if="hasMore" ref="loadSentinel" class="reader-document-load-sentinel" aria-hidden="true"></div>
    </div>
  </div>
</template>
