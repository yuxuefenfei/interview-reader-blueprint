<script setup lang="ts">
import { Check, CopyDocument } from "@element-plus/icons-vue";
import { onBeforeUnmount, ref } from "vue";

const props = withDefaults(defineProps<{
  value?: string | null;
  label?: string;
}>(), {
  value: "",
  label: "文档标识",
});

const copyLabel = ref("复制标识");
let resetTimer: number | null = null;

onBeforeUnmount(() => {
  if (resetTimer !== null) window.clearTimeout(resetTimer);
});

async function copyIdentifier(): Promise<void> {
  const value = props.value?.trim() ?? "";
  if (!value) return;
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(value);
    } else {
      const textArea = document.createElement("textarea");
      textArea.value = value;
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
  if (resetTimer !== null) window.clearTimeout(resetTimer);
  resetTimer = window.setTimeout(() => {
    copyLabel.value = "复制标识";
    resetTimer = null;
  }, 1_600);
}
</script>

<template>
  <div class="readonly-identifier" role="group" :aria-label="label">
    <code dir="auto">{{ value || "—" }}</code>
    <el-tooltip :content="copyLabel" placement="top">
      <el-button
        class="readonly-identifier-copy"
        :class="{ 'is-copied': copyLabel === '已复制' }"
        text
        circle
        :icon="copyLabel === '已复制' ? Check : CopyDocument"
        :disabled="!value"
        :aria-label="copyLabel"
        @click="copyIdentifier"
      />
    </el-tooltip>
    <span class="sr-only" aria-live="polite">{{ copyLabel === "复制标识" ? "" : copyLabel }}</span>
  </div>
</template>

<style scoped>
.readonly-identifier {
  width: fit-content;
  max-width: 100%;
  min-height: 28px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--ink-700);
}
.readonly-identifier code {
  min-width: 0;
  flex: 0 1 auto;
  color: #27364a;
  font-family: inherit;
  font-size: 14px;
  font-weight: 500;
  font-variant-ligatures: none;
  letter-spacing: 0;
  line-height: 1.6;
  overflow-wrap: anywhere;
  user-select: text;
}
.readonly-identifier-copy {
  width: 28px;
  min-height: 28px;
  height: 28px;
  flex: 0 0 auto;
  color: var(--ink-300);
}
.readonly-identifier-copy:hover,
.readonly-identifier-copy:focus-visible {
  color: var(--brand-500);
  background: var(--brand-050);
}
.readonly-identifier-copy.is-copied {
  color: var(--brand-500);
}
</style>
