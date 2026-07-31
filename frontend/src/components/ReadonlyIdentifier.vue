<script setup lang="ts">
import { CopyDocument } from "@element-plus/icons-vue";
import { onBeforeUnmount, ref } from "vue";

const props = withDefaults(defineProps<{
  value?: string | null;
  label?: string;
}>(), {
  value: "",
  label: "只读标识",
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
        text
        circle
        :icon="CopyDocument"
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
  width: 100%;
  min-height: 42px;
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 8px 8px 8px 12px;
  border: 1px solid var(--line-200);
  border-radius: var(--radius-sm);
  background: #f7f9fa;
  color: var(--ink-700);
  transition: border-color 120ms ease, box-shadow 120ms ease;
}
.readonly-identifier:focus-within {
  border-color: var(--brand-500);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--brand-500), transparent 86%);
}
.readonly-identifier code {
  min-width: 0;
  flex: 1;
  padding: 2px 0;
  color: #294054;
  font-family: "Cascadia Mono", "SFMono-Regular", Consolas, "Microsoft YaHei UI", "Microsoft YaHei", monospace;
  font-size: 13px;
  font-variant-ligatures: none;
  letter-spacing: .01em;
  line-height: 1.55;
  overflow-wrap: anywhere;
  user-select: text;
}
.readonly-identifier-copy {
  flex: 0 0 auto;
  color: var(--ink-500);
}
.readonly-identifier-copy:hover,
.readonly-identifier-copy:focus-visible {
  color: var(--brand-500);
  background: var(--brand-050);
}
</style>
