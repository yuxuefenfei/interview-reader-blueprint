<script lang="ts" setup>
import {ArrowRight} from "@element-plus/icons-vue";
import {useId} from "vue";
import type {TocNode} from "../types/api";

const props = withDefaults(defineProps<{
  nodes: TocNode[];
  activeNodeId: string | null;
  expandedNodeIds?: string[];
  pendingNodeId?: string | null;
  failedNodeId?: string | null;
  compactGroups?: boolean;
  depth?: number;
  treeId?: string;
}>(), {
  expandedNodeIds: () => [],
  pendingNodeId: null,
  failedNodeId: null,
  compactGroups: false,
  depth: 0,
  treeId: undefined,
});

const emit = defineEmits<{
  select: [node: TocNode];
  toggle: [nodeId: string];
  prefetch: [node: TocNode];
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
  <ol :class="{ 'toc-tree-root': depth === 0, 'toc-tree-compact-groups': compactGroups }" class="toc-tree">
    <li v-for="node in nodes" :key="node.id" class="toc-item">
      <div
          :class="{
          active: node.id === activeNodeId,
          pending: node.id === pendingNodeId,
          failed: node.id === failedNodeId,
        }"
          :style="{ '--toc-depth': visualDepth(), '--toc-indent': `${visualDepth() * 16}px` }"
          class="toc-row"
      >
        <div
            v-if="compactGroups && node.children.length"
            :title="node.title"
            class="toc-node toc-group-label has-children"
        >
          <span v-if="depth > 0" aria-hidden="true" class="toc-guide"></span>
          <span class="toc-title">{{ node.title }}</span>
        </div>
        <button
            v-else
            :aria-current="node.id === activeNodeId ? 'location' : undefined"
            :aria-label="node.title"
            :class="{ 'has-children': node.children.length > 0 }"
            :data-toc-node-id="node.id"
            :title="node.title"
            class="toc-node"
            type="button"
            @click="emit('select', node)"
            @focus="emit('prefetch', node)"
            @pointerenter="emit('prefetch', node)"
            @keydown.right.prevent="expandFromKeyboard(node)"
            @keydown.left.prevent="collapseFromKeyboard(node)"
        >
          <span v-if="depth > 0" aria-hidden="true" class="toc-guide"></span>
          <span class="toc-title">{{ node.title }}</span>
          <span v-if="node.id === pendingNodeId" class="toc-node-status loading" role="status">加载中</span>
          <span v-else-if="node.id === failedNodeId" class="toc-node-status failed">失败，重试</span>
          <span v-else-if="node.id === activeNodeId" class="toc-node-status current">当前</span>
        </button>
        <button
            v-if="node.children.length"
            :aria-controls="childListId(node.id)"
            :aria-expanded="isExpanded(node.id)"
            :aria-label="`${isExpanded(node.id) ? '收起' : '展开'}“${node.title}”`"
            class="toc-toggle"
            type="button"
            @click="emit('toggle', node.id)"
        >
          <ArrowRight aria-hidden="true" class="toc-toggle-icon"/>
        </button>
      </div>
      <TocTree
          v-if="node.children.length && isExpanded(node.id)"
          :id="childListId(node.id)"
          :active-node-id="activeNodeId"
          :compact-groups="compactGroups"
          :depth="depth + 1"
          :expanded-node-ids="expandedNodeIds"
          :failed-node-id="failedNodeId"
          :nodes="node.children"
          :pending-node-id="pendingNodeId"
          :tree-id="treeInstanceId"
          @prefetch="emit('prefetch', $event)"
          @select="emit('select', $event)"
          @toggle="emit('toggle', $event)"
      />
    </li>
  </ol>
</template>
