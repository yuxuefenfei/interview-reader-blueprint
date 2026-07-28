<script setup lang="ts">
import { ArrowRight } from "@element-plus/icons-vue";
import { computed } from "vue";
import type { DocumentSummary } from "../types/api";
import { formatProgressPercent, progressWidth } from "../utils/readingProgress";

const props = defineProps<{
  document: DocumentSummary | null;
}>();

const emit = defineEmits<{
  open: [];
}>();

const progressLabel = computed(() => formatProgressPercent(props.document?.progressRatio));
const progressStyle = computed(() => ({ width: progressWidth(props.document?.progressRatio) }));
</script>

<template>
  <button
    class="reader-document-selector"
    type="button"
    :disabled="!document"
    :aria-label="document ? `切换文档，当前为${document.title}` : '当前没有可切换的文档'"
    @click="emit('open')"
  >
    <span class="reader-document-selector-main">
      <strong :title="document?.title">{{ document?.title || "选择文档" }}</strong>
      <output :aria-label="`文档阅读位置 ${progressLabel}`">{{ progressLabel }}</output>
      <ArrowRight class="reader-document-selector-chevron" aria-hidden="true" />
    </span>
    <span class="reader-document-selector-progress" aria-hidden="true">
      <i :style="progressStyle"></i>
    </span>
  </button>
</template>
