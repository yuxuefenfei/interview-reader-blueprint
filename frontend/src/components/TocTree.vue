<script setup lang="ts">
import { ArrowRight } from "@element-plus/icons-vue";
import { useId } from "vue";
import type { TocNode } from "../types/api";

const props = withDefaults(defineProps<{
  nodes: TocNode[];
  activeNodeId: string | null;
  expandedNodeIds?: string[];
  pendingNodeId?: string | null;
  failedNodeId?: string | null;
  depth?: number;
  treeId?: string;
}>(), {
  expandedNodeIds: () => [],
  pendingNodeId: null,
  failedNodeId: null,
  depth: 0,
  treeId: undefined,
});

const emit = defineEmits<{
  select: [node: TocNode];
  toggle: [nodeId: string];
}>();
const treeInstanceId = props.treeId ?? useId();

function isExpanded(nodeId: string): boolean {
  return props.expandedNodeIds.includes(nodeId);
}

function visualDepth(): number {
  return Math.min(props.depth, 3);
}

function childListId(nodeId: string): string {
  return `toc-${treeInstanceId}-${nodeId.replace(/[^a-zA-Z0-9_-]/g, "-")}`;
}

function expandFromKeyboard(node: TocNode): void {
  if (node.children.length > 0 && !isExpanded(node.id)) emit("toggle", node.id);
}

function collapseFromKeyboard(node: TocNode): void {
  if (node.children.length > 0 && isExpanded(node.id)) emit("toggle", node.id);
}
</script>

<template>
  <ol class="toc-tree" :class="{ 'toc-tree-root': depth === 0 }">
    <li v-for="node in nodes" :key="node.id" class="toc-item">
      <div
        class="toc-row"
        :class="{
          active: node.id === activeNodeId,
          pending: node.id === pendingNodeId,
          failed: node.id === failedNodeId,
        }"
        :style="{ '--toc-depth': visualDepth(), '--toc-indent': `${visualDepth() * 16}px` }"
      >
        <button
          class="toc-node"
          type="button"
          :class="{ 'has-children': node.children.length > 0 }"
          :aria-current="node.id === activeNodeId ? 'location' : undefined"
          :aria-label="node.title"
          :data-toc-node-id="node.id"
          :title="node.title"
          @click="emit('select', node)"
          @keydown.right.prevent="expandFromKeyboard(node)"
          @keydown.left.prevent="collapseFromKeyboard(node)"
        >
          <span v-if="depth > 0" class="toc-guide" aria-hidden="true"></span>
          <span class="toc-title">{{ node.title }}</span>
          <span v-if="node.id === pendingNodeId" class="toc-node-status loading" role="status">加载中</span>
          <span v-else-if="node.id === failedNodeId" class="toc-node-status failed">失败，重试</span>
          <span v-else-if="node.id === activeNodeId" class="toc-node-status current">当前</span>
        </button>
        <button
          v-if="node.children.length"
          class="toc-toggle"
          type="button"
          :aria-label="`${isExpanded(node.id) ? '收起' : '展开'}“${node.title}”`"
          :aria-expanded="isExpanded(node.id)"
          :aria-controls="childListId(node.id)"
          @click="emit('toggle', node.id)"
        >
          <ArrowRight class="toc-toggle-icon" aria-hidden="true" />
        </button>
      </div>
      <TocTree
        v-if="node.children.length && isExpanded(node.id)"
        :id="childListId(node.id)"
        :nodes="node.children"
        :active-node-id="activeNodeId"
        :expanded-node-ids="expandedNodeIds"
        :pending-node-id="pendingNodeId"
        :failed-node-id="failedNodeId"
        :depth="depth + 1"
        :tree-id="treeInstanceId"
        @select="emit('select', $event)"
        @toggle="emit('toggle', $event)"
      />
    </li>
  </ol>
</template>
