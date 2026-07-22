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
        :class="{ active: node.id === activeNodeId }"
        :aria-current="node.id === activeNodeId ? 'location' : undefined"
        :style="{ paddingLeft: `${Math.max(0, node.level - 1) * 14 + 10}px` }"
        @click="emit('select', node)"
      >
        <span class="toc-title">{{ node.title }}</span>
        <span v-if="node.sourcePageStart" class="toc-page">P{{ node.sourcePageStart }}</span>
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
