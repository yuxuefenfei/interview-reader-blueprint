<script lang="ts" setup>
import {ref, watch} from "vue";
import {ArrowLeft, QuestionFilled} from "@element-plus/icons-vue";

withDefaults(defineProps<{
  title: string;
  description?: string;
  eyebrow?: string;
  backLabel?: string;
  truncateTitle?: boolean;
}>(), {description: "", eyebrow: "", backLabel: "", truncateTitle: false});

const emit = defineEmits<{ back: [] }>();
const helpOpen = ref(false);

// Help remains dismissible after keyboard focus moves to another header control.
watch(helpOpen, (open, _, onCleanup) => {
  if (!open) return;
  const closeOnEscape = (event: KeyboardEvent) => {
    if (event.key === "Escape") helpOpen.value = false;
  };
  document.addEventListener("keydown", closeOnEscape);
  onCleanup(() => document.removeEventListener("keydown", closeOnEscape));
});
</script>

<template>
  <header :class="{ 'has-dynamic-title': truncateTitle }" class="admin-page-header">
    <div class="admin-page-header-leading">
      <el-button v-if="backLabel" :icon="ArrowLeft" class="admin-page-back" text @click="emit('back')">{{
        backLabel
      }}
      </el-button>
      <div class="admin-page-heading">
        <div class="admin-page-title-line">
          <h1 :title="title">{{ title }}</h1>
          <el-popover
            v-if="description" v-model:visible="helpOpen" :hide-after="0" :width="360"
            placement="bottom-start" popper-class="admin-page-help-popper" trigger="click"
          >
            <template #reference>
              <el-button
                :aria-label="`${title}：查看页面说明`" :icon="QuestionFilled"
                class="admin-page-help-button" text
              />
            </template>
            <p class="admin-page-help-title">{{ title }}</p>
            <p class="admin-page-help-description">{{ description }}</p>
          </el-popover>
          <slot name="status" />
        </div>
        <div v-if="$slots.meta" class="admin-page-meta"><slot name="meta" /></div>
      </div>
    </div>
    <div v-if="$slots.actions" class="admin-page-actions ui-action-row">
      <slot name="actions" />
    </div>
  </header>
</template>
