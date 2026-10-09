<script lang="ts" setup>
import {computed, type CSSProperties, nextTick, onBeforeUnmount, ref, watch} from "vue";

const props = defineProps<{
  columns: string[];
  rows: string[][];
  previewEnabled?: boolean;
}>();
const emit = defineEmits<{ "update:previewOpen": [value: boolean] }>();
const previewOpen = ref(false);
const previewStyle = ref<CSSProperties>({});
const previewClose = ref<HTMLButtonElement | null>(null);
const columnCount = computed(() => props.rows.reduce((count, row) => Math.max(count, row.length), Math.max(props.columns.length, 1)));
const tableStyle = computed(() => ({minWidth: `${columnCount.value * 10}em`}));

function openPreview(event: MouseEvent): void {
  const trigger = event.currentTarget as HTMLButtonElement;
  const style = getComputedStyle(trigger.closest(".content-block") ?? trigger);
  previewStyle.value = Object.fromEntries([
    "--reader-bg", "--reader-surface", "--reader-text", "--reader-muted", "--reader-line", "--reader-accent", "--reader-code-bg",
  ].map((name) => [name, style.getPropertyValue(name)]));
  Object.assign(previewStyle.value, {
    fontFamily: style.fontFamily,
    fontSize: style.fontSize,
    lineHeight: style.lineHeight,
  });
  trigger.focus();
  previewOpen.value = true;
}

async function focusPreviewClose(): Promise<void> {
  // The dialog schedules its default focus after emitting open-auto-focus.
  await nextTick();
  await nextTick();
  previewClose.value?.focus();
}

function handlePreviewKeydown(event: KeyboardEvent): void {
  // Keep the reader's document search shortcut from opening behind the dialog.
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") {
    event.preventDefault();
    event.stopPropagation();
  }
}

watch(previewOpen, (open) => emit("update:previewOpen", open));
watch(() => props.previewEnabled, (enabled) => {
  if (!enabled) previewOpen.value = false;
});
onBeforeUnmount(() => {
  if (previewOpen.value) emit("update:previewOpen", false);
});
</script>

<template>
  <div :class="{ 'table-block--reader': previewEnabled }" class="table-block">
    <div v-if="previewEnabled" class="table-toolbar">
      <button aria-haspopup="dialog" class="table-preview-trigger" type="button" @click.stop="openPreview">放大查看</button>
    </div>
    <div :tabindex="previewEnabled ? 0 : undefined" :aria-label="previewEnabled ? '表格，可左右滚动' : undefined" class="table-wrap">
      <table :style="previewEnabled ? tableStyle : undefined">
        <thead v-if="columns.length">
          <tr>
            <th v-for="(column, index) in columns" :key="index" scope="col">{{ column }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, rowIndex) in rows" :key="rowIndex">
            <td v-for="(cell, cellIndex) in row" :key="cellIndex">{{ cell }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <el-dialog
      v-if="previewEnabled" v-model="previewOpen" :show-close="false" :style="previewStyle" :z-index="3000"
      align-center append-to-body class="table-preview-dialog" destroy-on-close title="表格预览"
      width="calc(100% - 32px)" @keydown="handlePreviewKeydown" @open-auto-focus="focusPreviewClose"
    >
      <template #header>
        <div class="table-preview-header">
          <span>表格预览</span>
          <button
            ref="previewClose" aria-label="关闭表格预览" class="table-preview-trigger" type="button"
            @click="previewOpen = false"
          >关闭</button>
        </div>
      </template>
      <div aria-label="完整表格，可滚动查看" class="table-wrap table-preview-scroll" tabindex="0">
        <table :style="tableStyle">
          <thead v-if="columns.length">
            <tr>
              <th v-for="(column, index) in columns" :key="index" scope="col">{{ column }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, rowIndex) in rows" :key="rowIndex">
              <td v-for="(cell, cellIndex) in row" :key="cellIndex">{{ cell }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.table-block--reader {
  margin: 20px 0;
}

.table-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 8px;
}

.table-preview-trigger {
  min-height: 44px;
  padding: 6px 12px;
  border: 1px solid var(--reader-line, #dce4e9);
  border-radius: var(--radius-sm);
  background: var(--reader-surface, #fff);
  color: var(--reader-accent, #0f766e);
  font: inherit;
  font-size: 14px;
  cursor: pointer;
}

.table-block--reader > .table-wrap,
.table-preview-scroll {
  margin: 0;
  overscroll-behavior: contain;
}

.table-block--reader th,
.table-block--reader td,
.table-preview-scroll th,
.table-preview-scroll td {
  min-width: 10em;
  max-width: 28em;
  vertical-align: top;
  overflow-wrap: anywhere;
}

.table-block--reader th,
.table-preview-scroll th {
  background: var(--reader-code-bg, #fbfcfe);
}

:global(.table-preview-dialog) {
  --el-dialog-bg-color: var(--reader-surface, #fff);
  --el-text-color-primary: var(--reader-text, #182230);
  --el-text-color-regular: var(--reader-text, #182230);
  max-height: calc(100dvh - 32px);
  display: flex;
  flex-direction: column;
  color: var(--reader-text, #182230);
}

:global(.table-preview-dialog .el-dialog__header) {
  padding-bottom: 12px;
}

:global(.table-preview-dialog .el-dialog__body) {
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  font: inherit;
}

.table-preview-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.table-preview-scroll {
  min-height: 0;
  overflow: auto;
  -webkit-overflow-scrolling: touch;
}

.table-preview-scroll th {
  position: sticky;
  top: 0;
  z-index: 1;
}

@media (max-width: 760px) {
  :global(.table-preview-dialog) {
    width: calc(100% - 16px);
    max-height: calc(100dvh - 16px - env(safe-area-inset-top) - env(safe-area-inset-bottom));
    padding: 12px;
  }
}
</style>
