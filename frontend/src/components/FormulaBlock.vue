<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";

const props = defineProps<{
  latex: string;
}>();

const renderedFormula = ref("");
const rendering = ref(false);
const renderFailed = ref(false);
let renderRequestId = 0;

const normalizedLatex = computed(() => props.latex.trim());

watch(normalizedLatex, async (latex) => {
  const requestId = ++renderRequestId;
  renderedFormula.value = "";
  renderFailed.value = false;
  if (!latex) return;

  rendering.value = true;
  try {
    const [{ default: katex }] = await Promise.all([
      import("katex"),
      import("katex/dist/katex.min.css"),
    ]);
    const html = katex.renderToString(latex, {
      displayMode: true,
      output: "htmlAndMathml",
      strict: false,
      throwOnError: true,
      trust: false,
    });
    if (requestId === renderRequestId) renderedFormula.value = html;
  } catch {
    if (requestId === renderRequestId) renderFailed.value = true;
  } finally {
    if (requestId === renderRequestId) rendering.value = false;
  }
}, { immediate: true });

onBeforeUnmount(() => {
  renderRequestId += 1;
});
</script>

<template>
  <div class="formula" :aria-busy="rendering">
    <div v-if="renderedFormula" class="formula-rendered" v-html="renderedFormula"></div>
    <p v-else-if="renderFailed" class="formula-fallback" role="status">
      <span>公式暂时无法渲染</span>
      <code>{{ normalizedLatex }}</code>
    </p>
    <p v-else-if="!normalizedLatex" class="formula-fallback">公式内容为空</p>
    <span v-else class="formula-loading" aria-label="正在渲染公式"></span>
  </div>
</template>

<style scoped>
.formula {
  min-width: 0;
  overflow-x: auto;
  margin: 16px 0;
  padding: 14px 16px;
  border: 1px solid var(--reader-line);
  border-radius: var(--radius-sm);
  background: color-mix(in srgb, var(--reader-surface) 88%, var(--reader-accent) 12%);
}

.formula-rendered {
  width: max-content;
  min-width: 100%;
  text-align: center;
}

.formula-fallback {
  display: grid;
  gap: 6px;
  margin: 0;
  color: var(--reader-muted);
  font-size: 13px;
}

.formula-fallback code {
  overflow-wrap: anywhere;
  color: var(--reader-text);
}

.formula-loading {
  display: block;
  width: 72px;
  height: 18px;
  margin: 2px auto;
  border-radius: 999px;
  background: linear-gradient(90deg, transparent, color-mix(in srgb, var(--reader-accent) 32%, transparent), transparent);
  background-size: 200% 100%;
  animation: formula-loading 1.1s linear infinite;
}

@keyframes formula-loading {
  to {
    background-position: -200% 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .formula-loading {
    animation: none;
  }
}
</style>
