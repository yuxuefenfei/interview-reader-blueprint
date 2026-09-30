<script lang="ts" setup>
import {toUserMessage} from "../utils/errorMessage";
import {RefreshRight} from "@element-plus/icons-vue";
import {computed, onBeforeUnmount, onMounted, ref} from "vue";
import {useRoute} from "vue-router";
import {adminApi} from "../api/admin";
import ContentBlockView from "../components/ContentBlockView.vue";
import {zh} from "../shared/presentation";
import type {EditorBlock, EditorDocument, EditorNode} from "../types/api";
import {
  detachedPreviewChannelName,
  type DetachedPreviewState,
  isDetachedPreviewMessage
} from "../utils/detachedPreviewChannel";

const route = useRoute();
const versionId = route.params.versionId as string;
const document = ref<Pick<EditorDocument, "title"> | null>(null);
const node = ref<EditorNode | null>(null);
const blocks = ref<EditorBlock[]>([]);
const activeBlockId = ref<string | null>(null);
const loading = ref(true);
const error = ref("");
const connected = ref(false);
let channel: BroadcastChannel | null = null;
let receivedLiveState = false;
let closeNotified = false;

const activeBlock = computed(() => blocks.value.find((block) => block.id === activeBlockId.value) ?? null);
const statusLabel = computed(() => connected.value ? "正在同步编辑器…" : "显示已保存草稿");

onMounted(() => {
  channel = new BroadcastChannel(detachedPreviewChannelName(versionId));
  channel.onmessage = (event: MessageEvent<unknown>) => {
    if (!isDetachedPreviewMessage(event.data)) return;
    if (event.data.type === "preview-close") {
      connected.value = false;
      node.value = null;
      blocks.value = [];
      activeBlockId.value = null;
      error.value = "预览已切换回编辑器。";
      window.close();
      return;
    }
    if (event.data.type !== "preview-state") return;
    applyState(event.data.state);
  };
  channel.postMessage({type: "preview-state-request"});
  window.addEventListener("pagehide", notifyPreviewClosed);
  void loadSavedPreview();
});

onBeforeUnmount(() => {
  notifyPreviewClosed();
  window.removeEventListener("pagehide", notifyPreviewClosed);
  channel?.close();
});

function notifyPreviewClosed(): void {
  if (closeNotified) return;
  closeNotified = true;
  channel?.postMessage({type: "preview-dismissed"});
}

function applyState(state: DetachedPreviewState): void {
  if (state.versionId !== versionId) return;
  receivedLiveState = true;
  connected.value = true;
  document.value = state.document;
  node.value = state.node;
  blocks.value = state.blocks;
  activeBlockId.value = state.activeBlockId;
  loading.value = false;
  error.value = "";
}

async function loadSavedPreview(force = false): Promise<void> {
  loading.value = true;
  error.value = "";
  try {
    const snapshot = await adminApi.editor(versionId);
    const requestedNodeId = typeof route.query.nodeId === "string" ? route.query.nodeId : null;
    const fallbackNode = snapshot.nodes.find((item) => item.id === requestedNodeId) ?? snapshot.nodes[0] ?? null;
    if (!fallbackNode) {
      if (!receivedLiveState || force) error.value = "草稿中没有可预览的节点。";
      return;
    }
    const page = await adminApi.nodeBlocks(versionId, fallbackNode.id);
    // 初次打开时优先保留编辑器刚广播的实时状态；用户明确点击刷新时必须以服务端已保存数据为准。
    if (receivedLiveState && !force) return;
    document.value = snapshot.document;
    node.value = fallbackNode;
    blocks.value = page.items;
    activeBlockId.value = page.items[0]?.id ?? null;
    connected.value = false;
  } catch (caught) {
    if (!receivedLiveState || force) error.value = toUserMessage(caught, "预览加载失败");
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <main class="detached-preview-page">
    <header class="detached-preview-header">
      <div><p class="eyebrow">弹出预览</p><strong>{{ document?.title || "草稿预览" }}</strong></div>
      <div class="detached-preview-actions ui-action-row ui-action-row--nowrap"><span
          :class="{ live: connected }">{{ statusLabel }}</span>
        <el-button :icon="RefreshRight" aria-label="刷新已保存草稿" circle title="刷新已保存草稿"
                   @click="loadSavedPreview(true)"/>
      </div>
    </header>
    <section v-loading="loading" class="detached-preview-content">
      <el-alert v-if="error" :closable="false" :title="error" show-icon type="error"/>
      <template v-else-if="node">
        <article class="detached-preview-article">
          <div class="preview-node-meta">
            <el-tag effect="plain">{{ zh(node.nodeType) }}</el-tag>
            <el-tag v-if="node.semanticRole" effect="plain" type="success">{{ zh(node.semanticRole) }}</el-tag>
          </div>
          <h1>{{ node.title }}</h1>
          <div v-for="block in blocks" :key="block.id" :class="{ active: activeBlock?.id === block.id }"
               class="detached-preview-block">
            <ContentBlockView :asset-base-url="`/api/admin/versions/${versionId}/editor/assets`" :block="block"/>
          </div>
          <el-empty v-if="!blocks.length" :image-size="72" description="当前节点暂无内容"/>
        </article>
      </template>
    </section>
  </main>
</template>
