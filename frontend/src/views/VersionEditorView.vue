<script setup lang="ts">
import { ArrowLeft, Delete, EditPen, FolderOpened, MoreFilled, RefreshRight } from "@element-plus/icons-vue";
import { computed, onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { adminApi } from "../api/admin";
import { zh } from "../shared/presentation";
import type { EditorBlock, EditorNode, EditorSnapshot, StructureNode } from "../types/api";

type TreeNode = EditorNode & { children: TreeNode[] };
type NodeForm = Pick<EditorNode, "title" | "nodeType" | "semanticRole" | "anchor">;

const route = useRoute();
const router = useRouter();
const versionId = route.params.versionId as string;
const editor = ref<EditorSnapshot | null>(null);
const treeData = ref<TreeNode[]>([]);
const selectedId = ref<string | null>(null);
const nodeForm = reactive<NodeForm>({ title: "", nodeType: "SECTION", semanticRole: null, anchor: "" });
const blocks = ref<EditorBlock[]>([]);
const nextCursor = ref<string | null>(null);
const payloadTexts = reactive<Record<string, string>>({});
const loading = ref(true);
const nodeLoading = ref(false);
const nodeSaving = ref(false);
const structureSaving = ref(false);
const savingBlockId = ref<string | null>(null);
const expandedPayload = ref<string[]>([]);

const selectedNode = computed(() => editor.value?.nodes.find(node => node.id === selectedId.value) ?? null);
const blockTypes = ["paragraph", "heading_note", "unordered_list", "ordered_list", "code", "table", "quote", "callout", "formula", "image", "divider", "table_snapshot"];

onMounted(() => { void load(); });
async function load(): Promise<void> {
  loading.value = true;
  try {
    const snapshot = await adminApi.editor(versionId);
    applySnapshot(snapshot);
    const first = snapshot.nodes[0];
    if (first) await selectNode(first);
  } catch (caught) { ElMessage.error(message(caught)); }
  finally { loading.value = false; }
}
function applySnapshot(snapshot: EditorSnapshot, select?: string | null): void {
  editor.value = snapshot;
  treeData.value = toTree(snapshot.nodes);
  const active = select === undefined ? selectedId.value : select;
  selectedId.value = active && snapshot.nodes.some(node => node.id === active) ? active : snapshot.nodes[0]?.id ?? null;
  const node = snapshot.nodes.find(item => item.id === selectedId.value);
  if (node) fillNodeForm(node);
}
function toTree(nodes: EditorNode[]): TreeNode[] {
  const byId = new Map(nodes.map(node => [node.id, { ...node, children: [] } satisfies TreeNode]));
  const roots: TreeNode[] = [];
  for (const node of byId.values()) {
    const parent = node.parentId ? byId.get(node.parentId) : undefined;
    (parent ? parent.children : roots).push(node);
  }
  const sort = (items: TreeNode[]): TreeNode[] => items.sort((left, right) => left.sortOrder - right.sortOrder || left.title.localeCompare(right.title, "zh-CN")).map(item => ({ ...item, children: sort(item.children) }));
  return sort(roots);
}
async function selectNode(node: EditorNode): Promise<void> {
  selectedId.value = node.id;
  fillNodeForm(node);
  await loadBlocks();
}
function fillNodeForm(node: EditorNode): void {
  nodeForm.title = node.title;
  nodeForm.nodeType = node.nodeType;
  nodeForm.semanticRole = node.semanticRole;
  nodeForm.anchor = node.anchor;
}
async function loadBlocks(append = false): Promise<void> {
  if (!selectedId.value) return;
  nodeLoading.value = true;
  try {
    const result = await adminApi.nodeBlocks(versionId, selectedId.value, append ? nextCursor.value ?? undefined : undefined);
    blocks.value = append ? [...blocks.value, ...result.items] : result.items;
    nextCursor.value = result.nextCursor;
    for (const block of result.items) payloadTexts[block.id] = JSON.stringify(block.payload ?? {}, null, 2);
  } catch (caught) { ElMessage.error(message(caught)); }
  finally { nodeLoading.value = false; }
}
async function saveNode(): Promise<void> {
  if (!editor.value || !selectedId.value) return;
  nodeSaving.value = true;
  try {
    const snapshot = await adminApi.updateNode(versionId, selectedId.value, editor.value.version.draftRevision, nodeForm);
    applySnapshot(snapshot, selectedId.value);
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
async function saveBlock(block: EditorBlock): Promise<void> {
  if (!editor.value) return;
  let payload: Record<string, unknown>;
  try { payload = JSON.parse(payloadTexts[block.id] || "{}") as Record<string, unknown>; }
  catch { ElMessage.error("扩展数据必须是有效的 JSON"); return; }
  savingBlockId.value = block.id;
  try {
    const updated = await adminApi.updateBlock(versionId, block.id, editor.value.version.draftRevision, { blockType: block.blockType, payload, plainText: block.plainText, language: block.language });
    Object.assign(block, updated);
    payloadTexts[block.id] = JSON.stringify(updated.payload ?? {}, null, 2);
    editor.value.version.draftRevision++;
    ElMessage.success("内容块已保存");
  } catch (caught) { ElMessage.error(message(caught)); }
  finally { savingBlockId.value = null; }
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
      <div><el-button text :icon="ArrowLeft" @click="router.push('/admin/documents')">返回文档管理</el-button><p class="eyebrow">版本修订</p><h1>{{ editor?.document.title || '草稿编辑器' }}</h1><span v-if="editor">v{{ editor.version.versionNo }} · {{ zh(editor.version.status) }} · 修订 {{ editor.version.draftRevision }} · 修改按节点即时保存</span></div>
      <div class="header-buttons"><el-button :icon="RefreshRight" @click="load">刷新</el-button><el-button type="danger" plain :icon="Delete" @click="discard">丢弃草稿</el-button></div>
    </header>

    <div v-if="editor" class="editor-workbench">
      <aside class="editor-tree-panel" v-loading="structureSaving">
        <div class="panel-title"><div><strong>文档结构</strong><span>{{ editor.nodes.length }} 个节点</span></div><el-icon><FolderOpened /></el-icon></div>
        <p class="tree-helper">拖动节点可调整层级和顺序，松开后自动保存。</p>
        <el-tree :data="treeData" node-key="id" :props="{ label: 'title', children: 'children' }" highlight-current default-expand-all draggable expand-on-click-node :current-node-key="selectedId" :allow-drop="() => true" @node-click="selectNode" @node-drop="persistStructure">
          <template #default="{ data }"><span class="editor-tree-label"><span>{{ data.title }}</span><small>{{ data.level }}</small></span></template>
        </el-tree>
      </aside>

      <main class="editor-content-panel">
        <section v-if="selectedNode" class="node-inspector">
          <div class="section-heading"><div><p class="eyebrow">节点属性</p><h2>{{ selectedNode.title }}</h2></div><el-button type="primary" :icon="EditPen" :loading="nodeSaving" @click="saveNode">保存节点</el-button></div>
          <el-form label-position="top" class="node-form"><el-form-item label="标题"><el-input v-model="nodeForm.title" /></el-form-item><el-form-item label="节点类型"><el-select v-model="nodeForm.nodeType"><el-option label="章节" value="SECTION" /><el-option label="目录" value="TOC" /><el-option label="说明" value="INTRO" /></el-select></el-form-item><el-form-item label="语义角色"><el-input v-model="nodeForm.semanticRole" placeholder="例如：QUESTION、ANSWER、EXPLANATION" /></el-form-item><el-form-item label="阅读锚点"><el-input v-model="nodeForm.anchor" /></el-form-item></el-form>
        </section>

        <section class="block-editor" v-loading="nodeLoading">
          <div class="section-heading"><div><p class="eyebrow">内容块</p><h2>当前节点内容</h2></div><span>{{ blocks.length }} 个已加载</span></div>
          <el-empty v-if="!nodeLoading && !blocks.length" description="该节点暂无内容块" />
          <article v-for="block in blocks" :key="block.id" class="block-edit-card">
            <header><div><el-tag size="small">{{ zh(block.blockType) }}</el-tag><span>块 #{{ block.seq }}</span></div><el-button type="primary" text :loading="savingBlockId === block.id" @click="saveBlock(block)">保存内容</el-button></header>
            <div class="block-edit-controls"><el-select v-model="block.blockType" aria-label="内容块类型"><el-option v-for="type in blockTypes" :key="type" :label="zh(type)" :value="type" /></el-select><el-input v-model="block.language" clearable placeholder="代码语言（仅代码块）" /></div>
            <el-input v-model="block.plainText" type="textarea" :autosize="{ minRows: 5, maxRows: 16 }" resize="vertical" />
            <el-collapse v-model="expandedPayload" class="payload-collapse"><el-collapse-item :name="block.id"><template #title>扩展数据 <el-icon class="payload-more"><MoreFilled /></el-icon></template><el-input v-model="payloadTexts[block.id]" type="textarea" :rows="7" class="payload-editor" spellcheck="false" /></el-collapse-item></el-collapse>
          </article>
          <el-button v-if="nextCursor" plain :loading="nodeLoading" @click="loadBlocks(true)">加载更多内容块</el-button>
        </section>
      </main>
    </div>
  </section>
</template>