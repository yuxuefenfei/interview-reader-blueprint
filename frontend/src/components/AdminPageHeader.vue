<script setup lang="ts">
import { ArrowLeft } from "@element-plus/icons-vue";

withDefaults(defineProps<{
  title: string;
  description?: string;
  eyebrow?: string;
  backLabel?: string;
}>(), {
  description: "",
  eyebrow: "",
  backLabel: "",
});

const emit = defineEmits<{ back: [] }>();
</script>

<template>
  <header class="admin-page-header">
    <div class="admin-page-header-leading">
      <el-button v-if="backLabel" class="admin-page-back" text :icon="ArrowLeft" @click="emit('back')">{{ backLabel }}</el-button>
      <div class="admin-page-heading">
        <p v-if="eyebrow" class="eyebrow">{{ eyebrow }}</p>
        <div class="admin-page-title-line">
          <h1 :title="title">{{ title }}</h1>
          <slot name="status" />
        </div>
        <p v-if="description" class="admin-page-description" :title="description">{{ description }}</p>
        <slot name="meta" />
      </div>
    </div>
    <div v-if="$slots.actions" class="admin-page-actions">
      <slot name="actions" />
    </div>
  </header>
</template>
