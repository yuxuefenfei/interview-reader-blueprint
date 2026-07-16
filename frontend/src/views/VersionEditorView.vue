<script setup lang="ts">
import { ArrowLeft, Delete, EditPen, FolderOpened, FullScreen, Hide, MoreFilled, Plus, Rank, RefreshRight, Search, Setting, View } from "@element-plus/icons-vue";
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { adminApi } from "../api/admin";
import ContentBlockView from "../components/ContentBlockView.vue";
import { zh } from "../shared/presentation";
import type { EditorBlock, EditorNode, EditorSnapshot, StructureNode } from "../types/api";
import { editorTextPlaceholder, parseEditorPayload, previewBlock, previewPayload } from "../utils/editorPreview";
import { detachedPreviewChannelName, isDetachedPreviewMessage, type DetachedPreviewState } from "../utils/detachedPreviewChannel";

type TreeNode = EditorNode & { children: TreeNode[] };
type NodeForm = Pick<EditorNode, "title" | "nodeType" | "semanticRole" | "anchor">;
type PreviewMode = "block" | "node";
type SaveState = "saved" | "dirty" | "saving" | "error";

const route = useRoute();
const router = useRouter();
const versionId = route.params.versionId as string;
const editor = ref<EditorSnapshot | null>(null);
const treeData = ref<TreeNode[]>([]);
const selectedId = ref<string | null>(null);
const treeFilter = ref("");
const nodeForm = reactive<NodeForm>({ title: "", nodeType: "SECTION", semanticRole: null, anchor: "" });
const blocks = ref<EditorBlock[]>([]);
const nextCursor = ref<string | null>(null);
const payloadTexts = reactive<Record<string, string>>({});
const savedBlockStates = reactive<Record<string, string>>({});
const loading = ref(true);
const nodeLoading = ref(false);
const nodeSaving = ref(false);
const structureSaving = ref(false);
const savingBlockId = ref<string | null>(null);
const creatingBlock = ref(false);
const deletingBlockId = ref<string | null>(null);
const cleaningEmptyBlocks = ref(false);
const activeBlockId = ref<string | null>(null);
const expandedPayload = ref<string[]>([]);
const previewMode = ref<PreviewMode>("block");
const previewVisible = ref(true);
const previewOffset = reactive({ x: 0, y: 0 });
const saveState = ref<SaveState>("saved");
const nodePropertiesOpen = ref(false);
const treePanelRef = ref<HTMLElement>();
const blockListRef = ref<HTMLElement>();
const previewScrollRef = ref<HTMLElement>();
const previewPanelRef = ref<HTMLElement>();
let autoSaveTimer: number | null = null;
let clearPreviewDrag: (() => void) | null = null;
let detachedPreviewChannel: BroadcastChannel | null = null;
let detachedPreviewPublishTimer: number | null = null;

const selectedNode = computed(() => editor.value?.nodes.find((node) => node.id === selectedId.value) ?? null);
const previewNode = computed<EditorNode | null>(() => {
  const node = selectedNode.value;
  return node && nodePropertiesOpen.value ? { ...node, ...nodeForm } : node;
});
const activeBlock = computed(() => blocks.value.find((block) => block.id === activeBlockId.value) ?? null);
const selectedNodeHasChildren = computed(() => !!selectedId.value && editor.value?.nodes.some((node) => node.parentId === selectedId.value));
const emptyBlockDescription = computed(() => selectedNodeHasChildren.value ? "该结构节点没有直接内容块。请选择子节点编辑正文，或在此新增内容。" : "该节点暂无内容块，可直接新增正文。");
const previewBlocks = computed(() => blocks.value.map((block) => previewBlock(block, payloadTexts[block.id])));
const visiblePreviewBlocks = computed(() => previewMode.value === "node" ? previewBlocks.value : previewBlocks.value.filter((block) => block.id === activeBlockId.value));
const dirtyBlockCount = computed(() => blocks.value.filter(isBlockDirty).length);
const emptyBlockCount = computed(() => blocks.value.filter(isBlankBlock).length);
const defaultExpandedKeys = computed(() => treeData.value.slice(0, 2).map((node) => node.id));
const filteredTreeData = computed(() => filterTree(treeData.value, treeFilter.value));
const previewHeading = computed(() => previewMode.value === "node"
  ? previewNode.value?.title ?? "当前节点"
  : activeBlock.value ? `块 #${activeBlock.value.seq} · ${zh(activeBlock.value.blockType)}` : "当前内容块");
const nodePath = computed(() => {
  if (!editor.value || !selectedNode.value) return "";
  const byId = new Map(editor.value.nodes.map((node) => [node.id, node]));
  const path: string[] = [];
  let current: EditorNode | undefined = selectedNode.value;
  while (current) {
    path.unshift(current.title);
    current = current.parentId ? byId.get(current.parentId) : undefined;
  }
  return path.join(" / ");
});
const saveStateLabel = computed(() => ({ saved: "已保存", dirty: "有未保存修改", saving: "保存中", error: "保存失败" }[saveState.value]));
watch(nodeForm, () => { if (nodePropertiesOpen.value) publishDetachedPreview(); }, { deep: true });
const blockTypes = ["paragraph", "heading_note", "unordered_list", "ordered_list", "code", "table", "quote", "callout", "formula", "image", "divider", "table_snapshot"];
const nodeTypes = [
  { value: "PART", label: "篇章" }, { value: "CHAPTER", label: "章节" }, { value: "SECTION", label: "小节" },
  { value: "SUBSECTION", label: "子节" }, { value: "QUESTION", label: "面试问题" }, { value: "APPENDIX", label: "附录" }, { value: "OTHER", label: "其他" }
];
const semanticRoles = [
  { value: "QUESTION", label: "面试问题" }, { value: "ANSWER", label: "答案" }, { value: "EXPLANATION", label: "解析" },
  { value: "CONCLUSION", label: "结论" }, { value: "INTRODUCTION", label: "导读" }, { value: "DIRECTORY", label: "目录" }
];

onMounted(() => {
  detachedPreviewChannel = new BroadcastChannel(detachedPreviewChannelName(versionId));
  detachedPreviewChannel.onmessage = (event: MessageEvent<unknown>) => {
    if (isDetachedPreviewMessage(event.data) && event.data.type === "preview-state-request") publishDetachedPreview();
  };
  void load();
});
onBeforeUnmount(() => {
  if (autoSaveTimer !== null) window.clearTimeout(autoSaveTimer);
  if (detachedPreviewPublishTimer !== null) window.clearTimeout(detachedPreviewPublishTimer);
  detachedPreviewPublishTimer = null;
  clearPreviewDrag?.();
  detachedPreviewChannel?.close();
  detachedPreviewChannel = null;
});

function publishDetachedPreview(): void {
  const currentNode = previewNode.value;
  const channel = detachedPreviewChannel;
  if (!editor.value || !currentNode || !channel) return;

  try {
    const state: DetachedPreviewState = {
      versionId,
      document: { title: editor.value.document.title },
      node: { ...currentNode },
      blocks: previewBlocks.value.map((block) => ({
        ...block,
        payload: JSON.parse(JSON.stringify(block.payload)) as Record<string, unknown>,
        sourceBbox: block.sourceBbox ? JSON.parse(JSON.stringify(block.sourceBbox)) : null
      })),
      activeBlockId: activeBlockId.value,
      updatedAt: Date.now()
    };
    channel.postMessage({ type: "preview-state", state });
  } catch {
    if (detachedPreviewChannel === channel) detachedPreviewChannel = null;
  }
}

function openDetachedPreview(): void {
  if (!editor.value || !selectedNode.value) return;
  const href = router.resolve({ path: `/admin/versions/${versionId}/preview`, query: { nodeId: selectedNode.value.id } }).href;
  const previewWindow = window.open(href, "interview-reader-editor-preview");
  if (!previewWindow) {
    ElMessage.warning("浏览器阻止了独立预览窗口，请允许本站点打开新标签页。");
    return;
  }
  previewWindow.focus();
  if (detachedPreviewPublishTimer !== null) window.clearTimeout(detachedPreviewPublishTimer);
  detachedPreviewPublishTimer = window.setTimeout(() => {
    detachedPreviewPublishTimer = null;
    publishDetachedPreview();
  }, 120);
}

async function load(): Promise<void> {
  loading.value = true;
  try {
    const snapshot = await adminApi.editor(versionId);
    applySnapshot(snapshot);
    const current = snapshot.nodes.find((node) => node.id === selectedId.value) ?? snapshot.nodes[0];
    if (current) await selectNode(current);
  } catch (caught) { ElMessage.error(message(caught)); }
  finally { loading.value = false; }
}

function applySnapshot(snapshot: EditorSnapshot, select?: string | null): void {
  editor.value = snapshot;
  treeData.value = toTree(snapshot.nodes);
  const active = select === undefined ? selectedId.value : select;
  selectedId.value = active && snapshot.nodes.some((node) => node.id === active) ? active : snapshot.nodes[0]?.id ?? null;
  const node = snapshot.nodes.find((item) => item.id === selectedId.value);
  if (node) fillNodeForm(node);
}

function filterTree(nodes: TreeNode[], keyword: string): TreeNode[] {
  const normalized = keyword.trim().toLocaleLowerCase("zh-CN");
  if (!normalized) return nodes;
  return nodes.flatMap((node) => {
    const children = filterTree(node.children, keyword);
    return node.title.toLocaleLowerCase("zh-CN").includes(normalized) || children.length
      ? [{ ...node, children }]
      : [];
  });
}

function toTree(nodes: EditorNode[]): TreeNode[] {
  const byId = new Map(nodes.map((node) => [node.id, { ...node, children: [] } satisfies TreeNode]));
  const roots: TreeNode[] = [];
  for (const node of byId.values()) {
    const parent = node.parentId ? byId.get(node.parentId) : undefined;
    (parent ? parent.children : roots).push(node);
  }
  const sort = (items: TreeNode[]): TreeNode[] => items
    .sort((left, right) => left.sortOrder - right.sortOrder || left.title.localeCompare(right.title, "zh-CN"))
    .map((item) => ({ ...item, children: sort(item.children) }));
  return sort(roots);
}

async function selectNode(node: EditorNode): Promise<void> {
  selectedId.value = node.id;
  fillNodeForm(node);
  activeBlockId.value = null;
  await loadBlocks();
  await nextTick();
  scrollTreeTo(node.id);
}

function fillNodeForm(node: EditorNode): void {
  nodeForm.title = node.title;
  nodeForm.nodeType = node.nodeType;
  nodeForm.semanticRole = node.semanticRole;
  nodeForm.anchor = node.anchor;
}

function resetNodeForm(): void {
  if (selectedNode.value) fillNodeForm(selectedNode.value);
}

async function loadBlocks(append = false): Promise<void> {
  if (!selectedId.value) return;
  nodeLoading.value = true;
  try {
    const result = await adminApi.nodeBlocks(versionId, selectedId.value, append ? nextCursor.value ?? undefined : undefined);
    blocks.value = append ? [...blocks.value, ...result.items.filter((item) => !blocks.value.some((block) => block.id === item.id))] : result.items;
    nextCursor.value = result.nextCursor;
    for (const block of result.items) {
      payloadTexts[block.id] = JSON.stringify(block.payload ?? {}, null, 2);
      savedBlockStates[block.id] = blockState(block);
    }
    const firstBlock = blocks.value[0];
    if (!activeBlockId.value && firstBlock) activateBlock(firstBlock.id, false);
    saveState.value = "saved";
  } catch (caught) { ElMessage.error(message(caught)); }
  finally {
    nodeLoading.value = false;
    publishDetachedPreview();
  }
}

async function saveNode(): Promise<void> {
  if (!editor.value || !selectedId.value) return;
  nodeSaving.value = true;
  try {
    const snapshot = await adminApi.updateNode(versionId, selectedId.value, editor.value.version.draftRevision, nodeForm);
    applySnapshot(snapshot, selectedId.value);
    nodePropertiesOpen.value = false;
    publishDetachedPreview();
    ElMessage.success("节点属性已保存");
  } catch (caught) { ElMessage.error(message(caught)); }
  finally { nodeSaving.value = false; }
}

async function persistStructure(): Promise<void> {
  if (!editor.value) return;
  const nodes: StructureNode[] = [];
  const visit = (items: TreeNode[], parentId: string | null): void => items.forEach((item, index) => {
    nodes.push({ id: item.id, parentId, sortOrder: (index + 1) * 10 });
    visit(item.children, item.id);
  });
  visit(treeData.value, null);
  structureSaving.value = true;
  try {
    const snapshot = await adminApi.updateStructure(versionId, editor.value.version.draftRevision, nodes);
    applySnapshot(snapshot, selectedId.value);
    ElMessage.success("文档结构已重排");
  } catch (caught) {
    ElMessage.error(message(caught));
    if (editor.value) treeData.value = toTree(editor.value.nodes);
  } finally { structureSaving.value = false; }
}

async function addBlock(): Promise<void> {
  if (!editor.value || !selectedId.value) return;
  creatingBlock.value = true;
  try {
    const block = await adminApi.createBlock(versionId, selectedId.value, editor.value.version.draftRevision, {
      blockType: "paragraph", payload: { text: "" }, plainText: "", language: null
    });
    blocks.value.push(block);
    payloadTexts[block.id] = JSON.stringify(block.payload ?? {}, null, 2);
    savedBlockStates[block.id] = blockState(block);
    editor.value.version.draftRevision++;
    activateBlock(block.id);
    ElMessage.success("已新增内容块");
  } catch (caught) { ElMessage.error(message(caught)); }
  finally { creatingBlock.value = false; }
}

function blockState(block: EditorBlock): string {
  return JSON.stringify({ blockType: block.blockType, plainText: block.plainText, language: block.language, payload: payloadTexts[block.id] ?? block.payload });
}

function isBlockDirty(block: EditorBlock): boolean {
  return savedBlockStates[block.id] !== blockState(block);
}

function payloadIsInvalid(block: EditorBlock): boolean {
  return parseEditorPayload(payloadTexts[block.id], block.payload) === null;
}

function isBlankBlock(block: EditorBlock): boolean {
  if (block.blockType === "divider" || block.plainText.trim()) return false;
  const payload = parseEditorPayload(payloadTexts[block.id], block.payload);
  if (!payload) return false;
  if (block.blockType === "image") return !["assetKey", "src", "url"].some((key) => typeof payload[key] === "string" && payload[key].trim());
  if (block.blockType === "unordered_list" || block.blockType === "ordered_list") return !Array.isArray(payload.items) || !payload.items.some((item) => String(item ?? "").trim());
  return !["text", "latex"].some((key) => typeof payload[key] === "string" && payload[key].trim());
}

function blockSummary(block: EditorBlock): string {
  const firstLine = block.plainText.split(/\r?\n/).find((line) => line.trim())?.trim() || "待填写内容";
  return firstLine.length > 44 ? `${firstLine.slice(0, 44)}...` : firstLine;
}

function centerInScrollContainer(container: HTMLElement | undefined, selector: string, smooth: boolean): void {
  const target = container?.querySelector<HTMLElement>(selector);
  if (!container || !target) return;
  const containerRect = container.getBoundingClientRect();
  const targetRect = target.getBoundingClientRect();
  const top = container.scrollTop + targetRect.top - containerRect.top - (container.clientHeight - target.clientHeight) / 2;
  container.scrollTo({ top: Math.max(0, top), behavior: smooth ? "smooth" : "auto" });
}
function startPreviewDrag(event: PointerEvent): void {
  if (event.button !== 0) return;
  const panel = previewPanelRef.value;
  if (!panel) return;
  clearPreviewDrag?.();
  const panelRect = panel.getBoundingClientRect();
  const startX = event.clientX;
  const startY = event.clientY;
  const originX = previewOffset.x;
  const originY = previewOffset.y;
  const desktopSidebarWidth = window.matchMedia("(min-width: 761px)").matches ? document.querySelector<HTMLElement>(".admin-sidebar")?.getBoundingClientRect().width ?? 0 : 0;
  const clamp = (value: number, min: number, max: number) => Math.min(Math.max(value, min), Math.max(min, max));
  const minOffsetX = originX + desktopSidebarWidth + 12 - panelRect.left;
  const maxOffsetX = originX + window.innerWidth - panelRect.width - 12 - panelRect.left;
  const minOffsetY = originY + 12 - panelRect.top;
  const maxOffsetY = originY + window.innerHeight - panelRect.height - 12 - panelRect.top;
  const move = (moveEvent: PointerEvent) => {
    previewOffset.x = clamp(originX + moveEvent.clientX - startX, minOffsetX, maxOffsetX);
    previewOffset.y = clamp(originY + moveEvent.clientY - startY, minOffsetY, maxOffsetY);
  };
  const stop = () => { window.removeEventListener("pointermove", move); window.removeEventListener("pointerup", stop); clearPreviewDrag = null; };
  clearPreviewDrag = stop;
  window.addEventListener("pointermove", move);
  window.addEventListener("pointerup", stop, { once: true });
}

function resetPreviewPosition(): void {
  previewOffset.x = 0;
  previewOffset.y = 0;
}

function activateBlock(blockId: string, scroll = true): void {
  activeBlockId.value = blockId;
  void nextTick(() => {
    centerInScrollContainer(blockListRef.value, `[data-block-id="${blockId}"]`, scroll);
    centerInScrollContainer(previewScrollRef.value, `[data-preview-block-id="${blockId}"]`, scroll);
  });
  publishDetachedPreview();
}

function scrollTreeTo(nodeId: string): void {
  centerInScrollContainer(treePanelRef.value, `[data-node-id="${nodeId}"]`, true);
}

function scheduleBlockSave(): void {
  const editedBlockId = activeBlock.value?.id;
  if (!editedBlockId) return;
  publishDetachedPreview();
  if (autoSaveTimer !== null) window.clearTimeout(autoSaveTimer);
  saveState.value = "dirty";
  autoSaveTimer = window.setTimeout(() => {
    const editedBlock = blocks.value.find((block) => block.id === editedBlockId);
    if (editedBlock) void saveBlock(editedBlock, true);
  }, 700);
}

async function saveBlock(block: EditorBlock, quiet = false): Promise<boolean> {
  if (!editor.value || savingBlockId.value || !isBlockDirty(block)) return true;
  const parsed = parseEditorPayload(payloadTexts[block.id], block.payload);
  if (parsed === null) {
    saveState.value = "error";
    if (!quiet) ElMessage.error("扩展数据必须是有效的 JSON");
    return false;
  }
  savingBlockId.value = block.id;
  saveState.value = "saving";
  try {
    const updated = await adminApi.updateBlock(versionId, block.id, editor.value.version.draftRevision, {
      blockType: block.blockType,
      payload: previewPayload(block, parsed),
      plainText: block.plainText,
      language: block.language
    });
    Object.assign(block, updated);
    payloadTexts[block.id] = JSON.stringify(updated.payload ?? {}, null, 2);
    publishDetachedPreview();
    savedBlockStates[block.id] = blockState(block);
    editor.value.version.draftRevision++;
    saveState.value = "saved";
    if (!quiet) ElMessage.success("内容块已保存");
    return true;
  } catch (caught) {
    saveState.value = "error";
    ElMessage.error(message(caught));
    return false;
  } finally { savingBlockId.value = null; }
}

async function saveAllBlocks(): Promise<void> {
  for (const block of blocks.value.filter(isBlockDirty)) {
    const saved = await saveBlock(block, true);
    if (!saved) return;
  }
  ElMessage.success("当前节点内容已保存");
}

async function deleteActiveBlock(): Promise<void> {
  const block = activeBlock.value;
  if (!editor.value || !block) return;
  try {
    await ElMessageBox.confirm(`将删除“${blockSummary(block)}”，无法恢复。`, "删除内容块", { type: "warning", confirmButtonText: "删除", cancelButtonText: "取消" });
    deletingBlockId.value = block.id;
    const result = await adminApi.deleteBlock(versionId, block.id, editor.value.version.draftRevision);
    editor.value.version.draftRevision = result.draftRevision;
    activeBlockId.value = null;
    await loadBlocks();
    ElMessage.success("内容块已删除");
  } catch (caught) { if (caught !== "cancel") ElMessage.error(message(caught)); }
  finally { deletingBlockId.value = null; }
}

async function cleanupEmptyBlocks(): Promise<void> {
  if (!editor.value) return;
  try {
    await ElMessageBox.confirm("将删除当前草稿中所有没有有效内容的块，并重新排列块序号。", "清理空内容块", { type: "warning", confirmButtonText: "清理", cancelButtonText: "取消" });
    cleaningEmptyBlocks.value = true;
    const result = await adminApi.cleanupEmptyBlocks(versionId, editor.value.version.draftRevision);
    editor.value.version.draftRevision = result.draftRevision;
    activeBlockId.value = null;
    await loadBlocks();
    ElMessage.success(result.removedCount ? `已清理 ${result.removedCount} 个空内容块` : "未发现需要清理的空内容块");
  } catch (caught) { if (caught !== "cancel") ElMessage.error(message(caught)); }
  finally { cleaningEmptyBlocks.value = false; }
}
async function discard(): Promise<void> {
  try {
    await ElMessageBox.confirm("将永久删除这份草稿，无法恢复。", "丢弃草稿", { type: "warning", confirmButtonText: "丢弃", cancelButtonText: "取消" });
    await adminApi.deleteDraft(versionId);
    ElMessage.success("草稿已丢弃");
    await router.push("/admin/documents");
  } catch (caught) { if (caught !== "cancel") ElMessage.error(message(caught)); }
}

function message(value: unknown): string { return value instanceof Error ? value.message : "操作失败"; }
</script>

<template>
  <section class="admin-view editor-view" v-loading="loading">
    <header class="admin-view-header editor-header">
      <div><el-button text :icon="ArrowLeft" @click="router.push('/admin/documents')">返回文档管理</el-button><p class="eyebrow">版本修订</p><h1>{{ editor?.document.title || "草稿编辑器" }}</h1><span v-if="editor">v{{ editor.version.versionNo }} · {{ zh(editor.version.status) }} · 修订 {{ editor.version.draftRevision }}</span></div>
      <div class="header-buttons"><el-button :icon="RefreshRight" @click="load">刷新</el-button><el-button type="danger" plain :icon="Delete" @click="discard">丢弃草稿</el-button></div>
    </header>

    <div v-if="editor" class="editor-workbench">
      <aside ref="treePanelRef" class="editor-tree-panel" v-loading="structureSaving">
        <div class="panel-title"><div><strong>文档结构</strong><span>{{ editor.nodes.length }} 个节点</span></div><el-icon><FolderOpened /></el-icon></div>
        <el-input v-model="treeFilter" class="tree-search" clearable placeholder="搜索节点" :prefix-icon="Search" />
        <el-tree :data="filteredTreeData" node-key="id" :props="{ label: 'title', children: 'children' }" highlight-current :default-expanded-keys="defaultExpandedKeys" :draggable="!treeFilter" expand-on-click-node :current-node-key="selectedId" :allow-drop="() => true" @node-click="selectNode" @node-drop="persistStructure">
          <template #default="{ data }"><span class="editor-tree-label" :data-node-id="data.id"><span>{{ data.title }}</span></span></template>
        </el-tree>
      </aside>

      <main class="editor-content-panel">
        <section v-if="selectedNode" class="node-inspector">
          <div class="section-heading"><div><p class="eyebrow">当前节点</p><h2>{{ selectedNode.title }}</h2><span class="node-path">{{ nodePath }}</span></div><div class="node-actions"><el-button :icon="Setting" @click="nodePropertiesOpen = true">节点属性</el-button></div></div>
        </section>

        <section class="block-editor" v-loading="nodeLoading">
          <div class="section-heading"><div><p class="eyebrow">内容编辑</p><h2>编辑与阅读预览</h2></div><div class="block-heading-actions"><el-button v-if="!previewVisible" :icon="View" @click="previewVisible = true">显示预览</el-button><el-button :icon="FullScreen" @click="openDetachedPreview">独立预览</el-button><span>{{ blocks.length }} 个块 · {{ dirtyBlockCount ? `${dirtyBlockCount} 个未保存` : saveStateLabel }}</span><el-button v-if="emptyBlockCount" type="warning" plain :icon="Delete" :loading="cleaningEmptyBlocks" @click="cleanupEmptyBlocks">清理空块（{{ emptyBlockCount }}）</el-button><el-button v-if="dirtyBlockCount" type="primary" :loading="saveState === 'saving'" @click="saveAllBlocks">保存当前节点</el-button><el-button type="primary" plain :icon="Plus" :loading="creatingBlock" @click="addBlock">新增内容块</el-button></div></div>
          <div class="editor-content-workbench">
            <aside ref="blockListRef" class="editor-block-list" aria-label="内容块列表">
              <el-empty v-if="!nodeLoading && !blocks.length" :description="emptyBlockDescription"><el-button type="primary" :icon="Plus" :loading="creatingBlock" @click="addBlock">新增第一段正文</el-button></el-empty>
              <button v-for="block in blocks" :key="block.id" type="button" class="block-list-item" :class="{ active: activeBlockId === block.id, dirty: isBlockDirty(block) }" :aria-current="activeBlockId === block.id ? 'true' : undefined" :data-block-id="block.id" @click="activateBlock(block.id)"><span class="block-list-meta"><el-tag size="small">{{ zh(block.blockType) }}</el-tag><small>块 #{{ block.seq }}</small><i v-if="isBlockDirty(block)">未保存</i><i v-else-if="isBlankBlock(block)" class="block-empty-flag">待填写</i></span><strong>{{ blockSummary(block) }}</strong></button>
              <el-button v-if="nextCursor" plain :loading="nodeLoading" @click="loadBlocks(true)">加载更多内容块</el-button>
            </aside>

            <section class="block-detail-panel">
              <el-empty v-if="!activeBlock" description="从左侧选择一个内容块开始编辑" :image-size="72" />
              <template v-else>
                <header><div><el-tag>{{ zh(activeBlock.blockType) }}</el-tag><span>块 #{{ activeBlock.seq }}<template v-if="activeBlock.sourcePage"> · 来源第 {{ activeBlock.sourcePage }} 页</template></span></div><div class="block-detail-actions"><el-button type="danger" plain :icon="Delete" :loading="deletingBlockId === activeBlock.id" @click="deleteActiveBlock">删除</el-button><el-button type="primary" :loading="savingBlockId === activeBlock.id" @click="saveBlock(activeBlock)">保存</el-button></div></header>
                <div class="block-edit-controls"><el-select v-model="activeBlock.blockType" aria-label="内容块类型" @change="scheduleBlockSave"><el-option v-for="type in blockTypes" :key="type" :label="zh(type)" :value="type" /></el-select><el-input v-if="activeBlock.blockType === 'code'" v-model="activeBlock.language" clearable placeholder="代码语言" @input="scheduleBlockSave" /></div>
                <el-input v-model="activeBlock.plainText" class="block-main-editor" type="textarea" :autosize="{ minRows: 12, maxRows: 28 }" resize="vertical" :placeholder="editorTextPlaceholder(activeBlock.blockType)" @input="scheduleBlockSave" />
                <el-collapse v-model="expandedPayload" class="payload-collapse"><el-collapse-item :name="activeBlock.id"><template #title>高级数据 <el-icon class="payload-more"><MoreFilled /></el-icon><span v-if="payloadIsInvalid(activeBlock)" class="payload-invalid">JSON 格式待修正</span></template><el-input v-model="payloadTexts[activeBlock.id]" type="textarea" :rows="10" class="payload-editor" spellcheck="false" @input="scheduleBlockSave" /></el-collapse-item></el-collapse>
              </template>
            </section>
          </div>
        </section>
        <Teleport to="body">
          <aside v-show="previewVisible" ref="previewPanelRef" class="editor-preview-panel" :style="{ transform: `translate(${previewOffset.x}px, ${previewOffset.y}px)` }">
            <header><div><p class="eyebrow">实时预览</p><strong>{{ previewNode?.title }}</strong></div><div class="preview-header-actions" @pointerdown.stop><el-radio-group v-model="previewMode" size="small"><el-radio-button value="block">当前块</el-radio-button><el-radio-button value="node">当前节点</el-radio-button></el-radio-group><el-button circle :icon="RefreshRight" aria-label="还原实时预览位置" title="还原到右侧" @click="resetPreviewPosition" /><el-button circle :icon="Hide" aria-label="隐藏实时预览" title="隐藏实时预览" @click="previewVisible = false" /></div><button class="preview-drag-handle" type="button" aria-label="拖动实时预览" @pointerdown="startPreviewDrag"><el-icon><Rank /></el-icon></button></header>
            <div ref="previewScrollRef" class="editor-preview-scroll">
              <article class="editor-preview-article"><div v-if="previewNode" class="preview-node-meta"><el-tag effect="plain">{{ zh(previewNode.nodeType) }}</el-tag><el-tag v-if="previewNode.semanticRole" type="success" effect="plain">{{ zh(previewNode.semanticRole) }}</el-tag></div><h1>{{ previewHeading }}</h1><div v-for="block in visiblePreviewBlocks" :key="block.id" class="editor-preview-block" :class="{ active: activeBlockId === block.id }" :data-preview-block-id="block.id" @click="activateBlock(block.id)"><ContentBlockView :block="block" /></div><el-empty v-if="!visiblePreviewBlocks.length" description="暂无可预览内容" :image-size="72" /></article>
            </div>
          </aside>
        </Teleport>
      </main>
      <el-drawer v-model="nodePropertiesOpen" class="node-property-drawer" title="节点属性" direction="rtl" size="420px" append-to-body @closed="resetNodeForm">
        <p class="drawer-description">修改节点名称、层级类型与阅读定位信息。</p>
        <el-form label-position="top" class="node-form"><el-form-item label="标题"><el-input v-model="nodeForm.title" /></el-form-item><el-form-item label="节点类型"><el-select v-model="nodeForm.nodeType"><el-option v-for="type in nodeTypes" :key="type.value" :label="type.label" :value="type.value" /></el-select></el-form-item><el-form-item label="语义角色"><el-select v-model="nodeForm.semanticRole" clearable filterable allow-create default-first-option placeholder="选择或输入语义角色"><el-option v-for="role in semanticRoles" :key="role.value" :label="role.label" :value="role.value" /></el-select></el-form-item><el-form-item label="阅读锚点"><el-input v-model="nodeForm.anchor" /></el-form-item></el-form>
        <template #footer><div class="drawer-footer"><el-button @click="nodePropertiesOpen = false">取消</el-button><el-button type="primary" :icon="EditPen" :loading="nodeSaving" @click="saveNode">保存节点</el-button></div></template>
      </el-drawer>
    </div>
  </section>
</template>
