<script setup lang="ts">
import type { TocNode } from "../types/api";

defineProps<{
  nodes: TocNode[];
  activeNodeId: string | null;
}>();

const emit = defineEmits<{
  select: [node: TocNode];
}>();
</script>

<template>
  <ol class="toc-tree">
    <li v-for="node in nodes" :key="node.id">
      <button
        class="toc-node"
        type="button"
        :class="{ active: node.id === activeNodeId, 'has-children': node.children.length > 0 }"
        :aria-current="node.id === activeNodeId ? 'location' : undefined"
        @click="emit('select', node)"
      >
        <span class="toc-title">{{ node.title }}</span>
      </button>
      <TocTree
        v-if="node.children.length"
        :nodes="node.children"
        :active-node-id="activeNodeId"
        @select="emit('select', $event)"
      />
    </li>
  </ol>
</template>
