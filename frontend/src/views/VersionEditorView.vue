<script setup lang="ts">
import { toUserMessage } from "../utils/errorMessage";
import { Delete, EditPen, FolderOpened, FullScreen, Hide, MoreFilled, Plus, Rank, RefreshRight, Search, Setting, View } from "@element-plus/icons-vue";
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { onBeforeRouteLeave, useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus/es/components/message/index";
import { ElMessageBox } from "element-plus/es/components/message-box/index";
import { adminApi } from "../api/admin";
import ContentBlockView from "../components/ContentBlockView.vue";
import { zh } from "../shared/presentation";
import { ADMIN_DESKTOP_MEDIA_QUERY } from "../shared/responsive";
import { BLOCK_TYPES, NODE_TYPES, SEMANTIC_ROLES } from "../types/api";
import type { EditorBlock, EditorNode, EditorSnapshot, StructureNode } from "../types/api";
import { editorText, editorTextPlaceholder, parseEditorPayload, previewBlock, previewPayload } from "../utils/editorPreview";
import { createSerializedSaveQueue } from "../utils/serializedSaveQueue";
import { detachedPreviewChannelName, isDetachedPreviewMessage, type DetachedPreviewState } from "../utils/detachedPreviewChannel";
import AdminPageHeader from "../components/AdminPageHeader.vue";

type TreeNode = EditorNode & { children: TreeNode[] };
type NodeForm = Pick<EditorNode, "title" | "nodeType" | "semanticRole">;
type PreviewMode = "block" | "node";
type SaveState = "saved" | "dirty" | "saving" | "error";
type PreviewCommand = "embedded" | "popout";
type EditorMoreCommand = "refresh" | "discard";
type NodeTypeChoice = { value: EditorNode["nodeType"]; label: string; description: string };
type SemanticRoleChoice = { value: EditorNode["semanticRole"]; label: string; description: string };
const DETACHED_PREVIEW_WINDOW_FEATURES = "popup=yes,width=430,height=932,resizable=yes,scrollbars=yes";
const NODE_TYPE_DESCRIPTIONS = {
  PART: "文档中的大型内容分区",
  CHAPTER: "承载一组相关主题的主要章节",
  SECTION: "章节内可独立阅读的主题单元",
  SUBSECTION: "对小节内容做进一步细分",
  QUESTION: "可独立阅读和练习的面试题",
  APPENDIX: "正文之外的补充资料",
  OTHER: "暂时无法归入以上结构"
} satisfies Record<EditorNode["nodeType"], string>;
const SEMANTIC_ROLE_DESCRIPTIONS = {
  QUESTION: "标记需要读者理解或回答的问题内容。",
  ANSWER: "标记针对问题给出的直接答案。",
  EXPLANATION: "标记对答案、机制或现象的展开解释。",
  CONCLUSION: "标记一段内容的总结或最终判断。",
  INTRODUCTION: "标记章节开始前的背景与阅读引导。",
  DIRECTORY: "标记目录、索引或导航性质的内容。",
  PRINCIPLE: "标记底层原理、机制和理论说明。",
  PRACTICE: "标记操作步骤、示例或工程实践。",
  PITFALL: "标记常见误区、风险和易错点。",
  FOLLOW_UP: "标记围绕当前问题继续深入的追问。"
} satisfies Record<NonNullable<EditorNode["semanticRole"]>, string>;
interface BlockSaveSnapshot {
  blockId: string;
  blockType: EditorBlock["blockType"];
  plainText: string;
  language: string | null;
  payloadText: string;
  fallbackPayload: Record<string, unknown>;
  state: string;
}
interface BlockSaveTask { snapshot: BlockSaveSnapshot; quiet: boolean }

const route = useRoute();
const router = useRouter();
const versionId = route.params.versionId as string;
const editor = ref<EditorSnapshot | null>(null);
const treeData = ref<TreeNode[]>([]);
const selectedId = ref<string | null>(null);
const treeFilter = ref("");
const nodeForm = reactive<NodeForm>({ title: "", nodeType: "SECTION", semanticRole: null });
const blocks = ref<EditorBlock[]>([]);
const nextCursor = ref<string | null>(null);
const payloadTexts = reactive<Record<string, string>>({});
const savedBlockStates = reactive<Record<string, string>>({});
const loading = ref(true);
const nodeLoading = ref(false);
const nodeSaving = ref(false);
const nodeDeleting = ref(false);
const structureSaving = ref(false);
const creatingBlock = ref(false);
const deletingBlockId = ref<string | null>(null);
const cleaningEmptyBlocks = ref(false);
const uploadingImage = ref(false);
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
let blockRequestId = 0;
let discardingDraft = false;
let clearPreviewDrag: (() => void) | null = null;
let detachedPreviewChannel: BroadcastChannel | null = null;
let detachedPreviewPublishTimer: number | null = null;
let detachedPreviewWindow: Window | null = null;
let previewPopupCloseWatchTimer: number | null = null;
const blockSaveQueue = createSerializedSaveQueue<BlockSaveTask>(700, ({ snapshot, quiet }) => persistBlockSnapshot(snapshot, quiet));

const selectedNode = computed(() => editor.value?.nodes.find((node) => node.id === selectedId.value) ?? null);
const previewNode = computed<EditorNode | null>(() => {
  const node = selectedNode.value;
  return node && nodePropertiesOpen.value ? { ...node, ...nodeForm } : node;
});
const activeBlock = computed(() => blocks.value.find((block) => block.id === activeBlockId.value) ?? null);
const activeBlockText = computed({
  get: () => {
    const block = activeBlock.value;
    return block ? editorText(block, payloadTexts[block.id]) : "";
  },
  set: (value: string) => {
    if (activeBlock.value) activeBlock.value.plainText = value;
  }
});
const selectedNodeHasChildren = computed(() => !!selectedId.value && editor.value?.nodes.some((node) => node.parentId === selectedId.value));
const selectedNodeChildCount = computed(() => selectedId.value
  ? editor.value?.nodes.filter((node) => node.parentId === selectedId.value).length ?? 0
  : 0);
const canDeleteSelectedNode = computed(() => !!selectedNode.value
  && !nodeLoading.value
  && !nodeDeleting.value
  && blocks.value.length === 0
  && nextCursor.value === null
  && (editor.value?.nodes.length ?? 0) > 1);
const deleteNodeTitle = computed(() => {
  if ((editor.value?.nodes.length ?? 0) <= 1) return "文档至少需要保留一个结构节点";
  if (blocks.value.length > 0 || nextCursor.value !== null) return "请先删除该节点的全部内容块";
  return selectedNodeChildCount.value > 0 ? "删除空节点并将其子节点提升一级" : "删除空节点";
});
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
const nodeFormPath = computed(() => {
  if (!editor.value || !selectedNode.value) return "";
  const byId = new Map(editor.value.nodes.map((node) => [node.id, node]));
  const path: string[] = [];
  let current: EditorNode | undefined = selectedNode.value;
  while (current) {
    path.unshift(current.id === selectedNode.value.id ? nodeForm.title.trim() || "未命名节点" : current.title);
    current = current.parentId ? byId.get(current.parentId) : undefined;
  }
  return path.join(" / ");
});
const nodeFormDirty = computed(() => {
  const node = selectedNode.value;
  return !!node && (
    nodeForm.title !== node.title
    || nodeForm.nodeType !== node.nodeType
    || nodeForm.semanticRole !== node.semanticRole
  );
});
const nodeTitleError = computed(() => nodePropertiesOpen.value && !nodeForm.title.trim() ? "请输入节点标题" : "");
const nodeFormValid = computed(() => !nodeTitleError.value);
const nodePropertyStatusLabel = computed(() => nodeSaving.value
  ? "正在保存"
  : nodeFormDirty.value ? "有未保存修改" : "尚未修改");
const selectedSemanticRoleDescription = computed(() => nodeForm.semanticRole
  ? SEMANTIC_ROLE_DESCRIPTIONS[nodeForm.semanticRole]
  : "不标记内容用途，仅保留该节点的目录结构含义。");
const saveStateLabel = computed(() => ({ saved: "已保存", dirty: "有未保存修改", saving: "保存中…", error: "保存失败" }[saveState.value]));
watch(nodeForm, () => { if (nodePropertiesOpen.value) publishDetachedPreview(); }, { deep: true });
watch(nodePropertiesOpen, (open) => {
  if (open) return;
  resetNodeForm();
  publishDetachedPreview();
});
const blockTypes = BLOCK_TYPES;
const nodeTypeChoices: NodeTypeChoice[] = NODE_TYPES.map((value) => ({
  value,
  label: zh(value),
  description: NODE_TYPE_DESCRIPTIONS[value]
}));
const semanticRoleChoices: SemanticRoleChoice[] = [
  { value: null, label: "无", description: "不标记内容用途，仅保留该节点的目录结构含义。" },
  ...SEMANTIC_ROLES.map((value) => ({
    value,
    label: zh(value),
    description: SEMANTIC_ROLE_DESCRIPTIONS[value]
  }))
];
const imageCaption = computed({
  get: () => {
    const block = activeBlock.value;
    if (!block) return "";
    const payload = parseEditorPayload(payloadTexts[block.id], block.payload) ?? block.payload;
    return typeof payload.caption === "string" ? payload.caption : "";
  },
  set: (value: string) => updateActiveImagePayload({ caption: value })
});
const imageDecorative = computed({
  get: () => {
    const block = activeBlock.value;
    if (!block) return false;
    const payload = parseEditorPayload(payloadTexts[block.id], block.payload) ?? block.payload;
    return payload.decorative === true;
  },
  set: (value: boolean) => updateActiveImagePayload({ decorative: value })
});

onMounted(() => {
  detachedPreviewChannel = new BroadcastChannel(detachedPreviewChannelName(versionId));
  detachedPreviewChannel.onmessage = (event: MessageEvent<unknown>) => {
    if (!isDetachedPreviewMessage(event.data)) return;
    if (event.data.type === "preview-state-request") publishDetachedPreview();
    if (event.data.type === "preview-dismissed") restoreEmbeddedPreview();
  };
  window.addEventListener("beforeunload", warnBeforeUnload);
  void load();
});
onBeforeRouteLeave(async () => discardingDraft || await flushPendingBlockSave());
onBeforeUnmount(() => {
  blockSaveQueue.cancelPending();
  window.removeEventListener("beforeunload", warnBeforeUnload);
  if (detachedPreviewPublishTimer !== null) window.clearTimeout(detachedPreviewPublishTimer);
  detachedPreviewPublishTimer = null;
  stopWatchingPreviewPopup();
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

function openPreviewPopup(): void {
  if (!editor.value || !selectedNode.value) return;
  if (detachedPreviewWindow && !detachedPreviewWindow.closed) {
    previewVisible.value = false;
    detachedPreviewWindow.focus();
    watchPreviewPopupClose();
    publishDetachedPreview();
    return;
  }
  const href = router.resolve({ path: `/admin/versions/${versionId}/preview`, query: { nodeId: selectedNode.value.id } }).href;
  const previewWindow = window.open(href, "interview-reader-editor-preview", DETACHED_PREVIEW_WINDOW_FEATURES);
  if (!previewWindow) {
    ElMessage.warning("浏览器阻止了弹出预览，请允许本站点打开弹窗。");
    return;
  }
  detachedPreviewWindow = previewWindow;
  previewVisible.value = false;
  watchPreviewPopupClose();
  previewWindow.focus();
  if (detachedPreviewPublishTimer !== null) window.clearTimeout(detachedPreviewPublishTimer);
  detachedPreviewPublishTimer = window.setTimeout(() => {
    detachedPreviewPublishTimer = null;
    publishDetachedPreview();
  }, 120);
}

function showEmbeddedPreview(): void {
  detachedPreviewChannel?.postMessage({ type: "preview-close" });
  if (detachedPreviewWindow && !detachedPreviewWindow.closed) detachedPreviewWindow.close();
  restoreEmbeddedPreview();
}

function restoreEmbeddedPreview(): void {
  stopWatchingPreviewPopup();
  detachedPreviewWindow = null;
  previewVisible.value = true;
}

function watchPreviewPopupClose(): void {
  stopWatchingPreviewPopup();
  previewPopupCloseWatchTimer = window.setInterval(() => {
    if (!detachedPreviewWindow || detachedPreviewWindow.closed) restoreEmbeddedPreview();
  }, 500);
}

function stopWatchingPreviewPopup(): void {
  if (previewPopupCloseWatchTimer === null) return;
  window.clearInterval(previewPopupCloseWatchTimer);
  previewPopupCloseWatchTimer = null;
}

function handlePreviewCommand(command: string | number | object): void {
  if (command === ("embedded" satisfies PreviewCommand)) showEmbeddedPreview();
  if (command === ("popout" satisfies PreviewCommand)) openPreviewPopup();
}

function handleEditorMoreCommand(command: string | number | object): void {
  if (command === ("refresh" satisfies EditorMoreCommand)) void load();
  if (command === ("discard" satisfies EditorMoreCommand)) void discard();
}

function backToDocument(): void {
  const target = editor.value ? `/admin/documents/${editor.value.document.id}` : "/admin/documents";
  void router.push(target);
}

async function load(): Promise<void> {
  if (editor.value && !await flushPendingBlockSave()) return;
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
  if (selectedId.value !== node.id && !await flushPendingBlockSave()) return;
  selectedId.value = node.id;
  fillNodeForm(node);
  activeBlockId.value = null;
  await loadBlocks();
  await nextTick();
  if (selectedId.value === node.id) scrollTreeTo(node.id);
}

function fillNodeForm(node: EditorNode): void {
  nodeForm.title = node.title;
  nodeForm.nodeType = node.nodeType;
  nodeForm.semanticRole = node.semanticRole;
}

function resetNodeForm(): void {
  if (selectedNode.value) fillNodeForm(selectedNode.value);
}

function openNodeProperties(): void {
  resetNodeForm();
  nodePropertiesOpen.value = true;
}

async function canCloseNodeProperties(): Promise<boolean> {
  if (nodeSaving.value) return false;
  if (!nodeFormDirty.value) return true;
  try {
    await ElMessageBox.confirm(
      "当前节点属性尚未保存，关闭后本次修改将丢失。",
      "放弃节点修改？",
      {
        confirmButtonText: "放弃修改",
        cancelButtonText: "继续编辑",
        type: "warning"
      }
    );
    return true;
  } catch {
    return false;
  }
}

function beforeCloseNodeProperties(done: () => void): void {
  void canCloseNodeProperties().then((canClose) => {
    if (canClose) done();
  });
}

async function requestCloseNodeProperties(): Promise<void> {
  if (await canCloseNodeProperties()) nodePropertiesOpen.value = false;
}

async function loadBlocks(append = false): Promise<void> {
  const nodeId = selectedId.value;
  if (!nodeId) return;
  const requestId = ++blockRequestId;
  nodeLoading.value = true;
  try {
    const result = await adminApi.nodeBlocks(versionId, nodeId, append ? nextCursor.value ?? undefined : undefined);
    if (requestId !== blockRequestId || selectedId.value !== nodeId) return;
    blocks.value = append ? [...blocks.value, ...result.items.filter((item) => !blocks.value.some((block) => block.id === item.id))] : result.items;
    nextCursor.value = result.nextCursor;
    for (const block of result.items) {
      payloadTexts[block.id] = JSON.stringify(block.payload ?? {}, null, 2);
      savedBlockStates[block.id] = blockState(block);
    }
    const firstBlock = blocks.value[0];
    if (!activeBlockId.value && firstBlock) void activateBlock(firstBlock.id, false);
    saveState.value = "saved";
  } catch (caught) {
    if (requestId === blockRequestId) ElMessage.error(message(caught));
  } finally {
    if (requestId === blockRequestId) {
      nodeLoading.value = false;
      publishDetachedPreview();
    }
  }
}

async function saveNode(): Promise<void> {
  if (!editor.value || !selectedId.value || !nodeFormDirty.value || !nodeFormValid.value || !await flushPendingBlockSave()) return;
  nodeSaving.value = true;
  try {
    const snapshot = await adminApi.updateNode(versionId, selectedId.value, editor.value.version.draftRevision, {
      ...nodeForm,
      title: nodeForm.title.trim()
    });
    applySnapshot(snapshot, selectedId.value);
    nodePropertiesOpen.value = false;
    publishDetachedPreview();
    ElMessage.success("节点属性已保存");
  } catch (caught) { ElMessage.error(message(caught)); }
  finally { nodeSaving.value = false; }
}

function selectionAfterNodeDeletion(node: EditorNode): string | null {
  if (!editor.value) return null;
  const ordered = [...editor.value.nodes].sort((left, right) =>
    left.sortOrder - right.sortOrder || left.title.localeCompare(right.title, "zh-CN"));
  const firstChild = ordered.find((item) => item.parentId === node.id);
  if (firstChild) return firstChild.id;
  if (node.parentId) return node.parentId;
  const siblings = ordered.filter((item) => item.parentId === null);
  const index = siblings.findIndex((item) => item.id === node.id);
  return siblings[index + 1]?.id ?? siblings[index - 1]?.id ?? null;
}

async function deleteSelectedNode(): Promise<void> {
  const node = selectedNode.value;
  if (!editor.value || !node || !canDeleteSelectedNode.value) return;
  const childCount = selectedNodeChildCount.value;
  const detail = childCount > 0
    ? `将删除空节点“${node.title}”，其 ${childCount} 个直接子节点会提升一级，正文内容不会被删除。`
    : `将删除空节点“${node.title}”，此操作无法恢复。`;
  try {
    await ElMessageBox.confirm(detail, "删除结构节点", {
      type: "warning",
      confirmButtonText: "删除节点",
      cancelButtonText: "取消"
    });
    if (!await flushPendingBlockSave()) return;
    nodeDeleting.value = true;
    const nextSelection = selectionAfterNodeDeletion(node);
    const staleBlocks = [...blocks.value];
    const snapshot = await adminApi.deleteNode(versionId, node.id, editor.value.version.draftRevision);
    blockRequestId += 1;
    staleBlocks.forEach((block) => {
      delete payloadTexts[block.id];
      delete savedBlockStates[block.id];
    });
    blocks.value = [];
    nextCursor.value = null;
    activeBlockId.value = null;
    nodePropertiesOpen.value = false;
    applySnapshot(snapshot, nextSelection);
    if (selectedId.value) await loadBlocks();
    ElMessage.success(childCount > 0 ? "节点已删除，子节点已提升一级" : "节点已删除");
  } catch (caught) {
    if (caught !== "cancel" && caught !== "close") ElMessage.error(message(caught));
  } finally {
    nodeDeleting.value = false;
  }
}

async function persistStructure(): Promise<void> {
  if (!editor.value) return;
  if (!await flushPendingBlockSave()) {
    treeData.value = toTree(editor.value.nodes);
    return;
  }
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
  if (!editor.value || !selectedId.value || !await flushPendingBlockSave()) return;
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
  return firstLine.length > 44 ? `${firstLine.slice(0, 44)}…` : firstLine;
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
  event.preventDefault();
  clearPreviewDrag?.();
  const panelRect = panel.getBoundingClientRect();
  const handle = event.currentTarget instanceof HTMLElement ? event.currentTarget : null;
  const startX = event.clientX;
  const startY = event.clientY;
  const originX = previewOffset.x;
  const originY = previewOffset.y;
  let nextX = originX;
  let nextY = originY;
  let animationFrame: number | null = null;
  const desktopSidebarWidth = window.matchMedia(ADMIN_DESKTOP_MEDIA_QUERY).matches ? document.querySelector<HTMLElement>(".admin-sidebar")?.getBoundingClientRect().width ?? 0 : 0;
  const clamp = (value: number, min: number, max: number) => Math.min(Math.max(value, min), Math.max(min, max));
  const minOffsetX = originX + desktopSidebarWidth + 12 - panelRect.left;
  const maxOffsetX = originX + window.innerWidth - panelRect.width - 12 - panelRect.left;
  const minOffsetY = originY + 12 - panelRect.top;
  const maxOffsetY = originY + window.innerHeight - panelRect.height - 12 - panelRect.top;
  const render = () => {
    animationFrame = null;
    panel.style.transform = `translate3d(${nextX}px, ${nextY}px, 0)`;
  };
  const move = (moveEvent: PointerEvent) => {
    if (moveEvent.pointerId !== event.pointerId) return;
    nextX = clamp(originX + moveEvent.clientX - startX, minOffsetX, maxOffsetX);
    nextY = clamp(originY + moveEvent.clientY - startY, minOffsetY, maxOffsetY);
    if (animationFrame === null) animationFrame = window.requestAnimationFrame(render);
  };
  const stop = (stopEvent?: PointerEvent) => {
    if (stopEvent && stopEvent.pointerId !== event.pointerId) return;
    if (animationFrame !== null) window.cancelAnimationFrame(animationFrame);
    animationFrame = null;
    panel.style.transform = `translate3d(${nextX}px, ${nextY}px, 0)`;
    panel.style.removeProperty("will-change");
    panel.classList.remove("is-dragging");
    previewOffset.x = nextX;
    previewOffset.y = nextY;
    window.removeEventListener("pointermove", move);
    window.removeEventListener("pointerup", stop);
    window.removeEventListener("pointercancel", stop);
    if (handle?.hasPointerCapture(event.pointerId)) handle.releasePointerCapture(event.pointerId);
    clearPreviewDrag = null;
  };
  panel.classList.add("is-dragging");
  panel.style.willChange = "transform";
  handle?.setPointerCapture(event.pointerId);
  clearPreviewDrag = stop;
  window.addEventListener("pointermove", move, { passive: true });
  window.addEventListener("pointerup", stop);
  window.addEventListener("pointercancel", stop);
}

function resetPreviewPosition(): void {
  previewOffset.x = 0;
  previewOffset.y = 0;
}

async function activateBlock(blockId: string, scroll = true): Promise<void> {
  if (activeBlockId.value !== blockId && !await flushPendingBlockSave()) return;
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

function captureBlockSave(block: EditorBlock): BlockSaveSnapshot {
  const payloadText = payloadTexts[block.id] ?? JSON.stringify(block.payload ?? {}, null, 2);
  return {
    blockId: block.id,
    blockType: block.blockType,
    plainText: block.plainText,
    language: block.language,
    payloadText,
    fallbackPayload: JSON.parse(JSON.stringify(block.payload ?? {})) as Record<string, unknown>,
    state: blockState(block)
  };
}

function scheduleBlockSave(): void {
  const block = activeBlock.value;
  if (!block) return;
  publishDetachedPreview();
  saveState.value = "dirty";
  blockSaveQueue.schedule({ snapshot: captureBlockSave(block), quiet: true });
}

function updateActiveImagePayload(patch: Record<string, unknown>): void {
  const block = activeBlock.value;
  if (!block || block.blockType !== "image") return;
  const payload = parseEditorPayload(payloadTexts[block.id], block.payload) ?? block.payload;
  payloadTexts[block.id] = JSON.stringify({ ...payload, ...patch }, null, 2);
  scheduleBlockSave();
}

function selectImage(file: { raw?: File }): void {
  if (file.raw) void uploadActiveImage(file.raw);
}

async function uploadActiveImage(file: File): Promise<void> {
  const block = activeBlock.value;
  if (!editor.value || !block || block.blockType !== "image") return;
  if (!["image/png", "image/jpeg", "image/webp"].includes(file.type)) {
    ElMessage.error("仅支持 PNG、JPEG 或 WebP 图片");
    return;
  }
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.error("图片不能超过 10MB");
    return;
  }
  if (!await flushPendingBlockSave()) return;
  uploadingImage.value = true;
  try {
    const result = await adminApi.uploadBlockImage(
      versionId,
      block.id,
      editor.value.version.draftRevision,
      file,
      imageDecorative.value ? "" : block.plainText,
      imageDecorative.value,
      imageCaption.value
    );
    editor.value.version.draftRevision = result.draftRevision;
    const current = blocks.value.find((item) => item.id === block.id);
    if (current) {
      Object.assign(current, result.block);
      payloadTexts[current.id] = JSON.stringify(result.block.payload ?? {}, null, 2);
      savedBlockStates[current.id] = blockState(current);
    }
    saveState.value = "saved";
    publishDetachedPreview();
    ElMessage.success("图片已上传并保存到草稿");
  } catch (caught) {
    ElMessage.error(message(caught));
  } finally {
    uploadingImage.value = false;
  }
}

async function flushPendingBlockSave(): Promise<boolean> {
  return blockSaveQueue.flush();
}

async function saveBlock(block: EditorBlock, quiet = false): Promise<boolean> {
  if (!editor.value || !isBlockDirty(block)) return true;
  blockSaveQueue.cancelPending();
  return blockSaveQueue.submit({ snapshot: captureBlockSave(block), quiet });
}

async function persistBlockSnapshot(snapshot: BlockSaveSnapshot, quiet: boolean): Promise<boolean> {
  if (!editor.value || savedBlockStates[snapshot.blockId] === snapshot.state) return true;
  const parsed = parseEditorPayload(snapshot.payloadText, snapshot.fallbackPayload);
  if (parsed === null) {
    saveState.value = "error";
    if (!quiet) ElMessage.error("扩展数据必须是有效的 JSON");
    return false;
  }
  const requestBlock = {
    id: snapshot.blockId,
    blockType: snapshot.blockType,
    plainText: snapshot.plainText,
    language: snapshot.language
  } as EditorBlock;
  saveState.value = "saving";
  try {
    const updated = await adminApi.updateBlock(versionId, snapshot.blockId, editor.value.version.draftRevision, {
      blockType: snapshot.blockType,
      payload: previewPayload(requestBlock, parsed),
      plainText: snapshot.plainText,
      language: snapshot.language
    });
    editor.value.version.draftRevision++;
    const current = blocks.value.find((block) => block.id === snapshot.blockId);
    savedBlockStates[snapshot.blockId] = snapshot.state;
    // 请求期间若用户继续输入，只更新“已保存基线”，不能用旧响应覆盖新的本地文本。
    if (current && blockState(current) === snapshot.state) {
      Object.assign(current, updated);
      payloadTexts[current.id] = JSON.stringify(updated.payload ?? {}, null, 2);
      savedBlockStates[current.id] = blockState(current);
    }
    publishDetachedPreview();
    saveState.value = blocks.value.some(isBlockDirty) ? "dirty" : "saved";
    if (!quiet) ElMessage.success("内容块已保存");
    return true;
  } catch (caught) {
    saveState.value = "error";
    ElMessage.error(message(caught));
    return false;
  }
}

function warnBeforeUnload(event: BeforeUnloadEvent): void {
  if (!blockSaveQueue.hasWork() && !blocks.value.some(isBlockDirty)) return;
  event.preventDefault();
  event.returnValue = "";
}

async function saveAllBlocks(): Promise<void> {
  if (!await flushPendingBlockSave()) return;
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
    if (!await flushPendingBlockSave()) return;
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
    if (!await flushPendingBlockSave()) return;
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
    blockSaveQueue.cancelPending();
    discardingDraft = true;
    await adminApi.deleteDraft(versionId);
    ElMessage.success("草稿已丢弃");
    await router.push("/admin/documents");
  } catch (caught) { if (caught !== "cancel") ElMessage.error(message(caught)); }
}

function message(value: unknown): string { return toUserMessage(value, "操作失败"); }
</script>

<template>
  <section class="admin-view editor-view" v-loading="loading">
    <AdminPageHeader
      eyebrow="版本修订"
      :title="editor?.document.title || '草稿编辑器'"
      :description="editor ? `v${editor.version.versionNo} · ${zh(editor.version.status)} · 修订 ${editor.version.draftRevision}` : '加载草稿版本信息'"
      back-label="返回版本管理"
      @back="backToDocument"
    >
      <template #status><span class="editor-save-state" :class="`is-${saveState}`"><i />{{ saveStateLabel }}</span></template>
      <template #actions>
        <el-button data-testid="save-editor" type="primary" :loading="saveState === 'saving'" :disabled="dirtyBlockCount === 0" @click="saveAllBlocks">保存</el-button>
        <el-dropdown trigger="click" @command="handlePreviewCommand">
          <el-button :icon="View">预览</el-button>
          <template #dropdown><el-dropdown-menu><el-dropdown-item command="embedded" :icon="View">显示页内预览</el-dropdown-item><el-dropdown-item command="popout" :icon="FullScreen">弹出预览（可拖到第二屏）</el-dropdown-item></el-dropdown-menu></template>
        </el-dropdown>
        <el-dropdown trigger="click" @command="handleEditorMoreCommand">
          <el-button :icon="MoreFilled">更多</el-button>
          <template #dropdown><el-dropdown-menu><el-dropdown-item command="refresh" :icon="RefreshRight">刷新草稿</el-dropdown-item><el-dropdown-item command="discard" :icon="Delete" divided class="danger-command">丢弃草稿</el-dropdown-item></el-dropdown-menu></template>
        </el-dropdown>
      </template>
    </AdminPageHeader>

    <div v-if="editor" class="editor-workbench">
      <aside ref="treePanelRef" class="editor-tree-panel" v-loading="structureSaving">
        <div class="panel-title"><div><strong>文档结构</strong><span>{{ editor.nodes.length }} 个节点</span></div><el-icon><FolderOpened /></el-icon></div>
        <el-input v-model="treeFilter" class="tree-search" name="editor-node-search" autocomplete="off" clearable placeholder="搜索节点…" :prefix-icon="Search" />
        <el-tree :data="filteredTreeData" node-key="id" :props="{ label: 'title', children: 'children' }" highlight-current :default-expanded-keys="defaultExpandedKeys" :draggable="!treeFilter" expand-on-click-node :current-node-key="selectedId" :allow-drop="() => true" @node-click="selectNode" @node-drop="persistStructure">
          <template #default="{ data }"><span class="editor-tree-label" :data-node-id="data.id"><span>{{ data.title }}</span></span></template>
        </el-tree>
      </aside>

      <main class="editor-content-panel">
        <section v-if="selectedNode" class="node-inspector">
          <div class="section-heading"><div><p class="eyebrow">当前节点</p><h2>{{ selectedNode.title }}</h2><span class="node-path">{{ nodePath }}</span></div><div class="node-actions"><el-button data-testid="delete-node" type="danger" plain :icon="Delete" :loading="nodeDeleting" :disabled="!canDeleteSelectedNode" :title="deleteNodeTitle" @click="deleteSelectedNode">删除空节点</el-button><el-button data-testid="node-properties-trigger" :icon="Setting" @click="openNodeProperties">节点属性</el-button></div></div>
        </section>

        <section class="block-editor" v-loading="nodeLoading">
          <div class="section-heading"><div><p class="eyebrow">内容编辑</p><h2>编辑与阅读预览</h2></div><div class="block-heading-actions"><span>{{ blocks.length }} 个块 · {{ dirtyBlockCount ? `${dirtyBlockCount} 个未保存` : saveStateLabel }}</span><el-button v-if="emptyBlockCount" type="warning" plain :icon="Delete" :loading="cleaningEmptyBlocks" @click="cleanupEmptyBlocks">清理空块（{{ emptyBlockCount }}）</el-button><el-button type="primary" plain :icon="Plus" :loading="creatingBlock" @click="addBlock">新增内容块</el-button></div></div>
          <div class="editor-content-workbench">
            <aside ref="blockListRef" class="editor-block-list" aria-label="内容块列表">
              <el-empty v-if="!nodeLoading && !blocks.length" :description="emptyBlockDescription"><el-button type="primary" :icon="Plus" :loading="creatingBlock" @click="addBlock">新增第一段正文</el-button></el-empty>
              <button v-for="block in blocks" :key="block.id" type="button" class="block-list-item" :class="{ active: activeBlockId === block.id, dirty: isBlockDirty(block) }" :aria-current="activeBlockId === block.id ? 'true' : undefined" :data-block-id="block.id" @click="activateBlock(block.id)"><span class="block-list-meta"><el-tag size="small">{{ zh(block.blockType) }}</el-tag><small>块 #{{ block.seq }}</small><i v-if="isBlockDirty(block)">未保存</i><i v-else-if="isBlankBlock(block)" class="block-empty-flag">待填写</i></span><strong>{{ blockSummary(block) }}</strong></button>
              <el-button v-if="nextCursor" plain :loading="nodeLoading" @click="loadBlocks(true)">加载更多内容块</el-button>
            </aside>

            <section class="block-detail-panel">
              <el-empty v-if="!activeBlock" description="从左侧选择一个内容块开始编辑" :image-size="72" />
              <template v-else>
                <header><div><el-tag>{{ zh(activeBlock.blockType) }}</el-tag><span>块 #{{ activeBlock.seq }}<template v-if="activeBlock.sourcePage"> · 来源第 {{ activeBlock.sourcePage }} 页</template></span></div><div class="block-detail-actions"><el-button type="danger" plain :icon="Delete" :loading="deletingBlockId === activeBlock.id" @click="deleteActiveBlock">删除</el-button></div></header>
                <div class="block-edit-controls"><el-select v-model="activeBlock.blockType" aria-label="内容块类型" @change="scheduleBlockSave"><el-option v-for="type in blockTypes" :key="type" :label="zh(type)" :value="type" /></el-select><el-input v-if="activeBlock.blockType === 'code'" v-model="activeBlock.language" name="block-code-language" autocomplete="off" spellcheck="false" clearable placeholder="例如：Java…" @input="scheduleBlockSave" /></div>
                <template v-if="activeBlock.blockType === 'image'">
                  <div class="image-block-editor">
                    <el-upload accept="image/png,image/jpeg,image/webp" :auto-upload="false" :show-file-list="false" @change="selectImage"><el-button type="primary" :loading="uploadingImage">上传图片</el-button></el-upload>
                    <span class="form-help">支持 PNG、JPEG、WebP，最大 10MB。上传后会立即绑定到此草稿图片块。</span>
                    <el-input v-model="activeBlock.plainText" :name="`block-image-alt-${activeBlock.id}`" autocomplete="off" placeholder="图片替代文本（非装饰性图片必填）" :disabled="imageDecorative" @input="scheduleBlockSave" />
                    <el-switch v-model="imageDecorative" active-text="装饰性图片" inactive-text="内容图片" />
                    <el-input v-model="imageCaption" :name="`block-image-caption-${activeBlock.id}`" autocomplete="off" placeholder="图片说明（可选）" @input="scheduleBlockSave" />
                  </div>
                </template>
                <el-input v-else v-model="activeBlockText" :name="`block-content-${activeBlock.id}`" autocomplete="off" class="block-main-editor" type="textarea" :autosize="{ minRows: 12, maxRows: 28 }" resize="vertical" :placeholder="editorTextPlaceholder(activeBlock.blockType)" @input="scheduleBlockSave" />
                <el-collapse v-if="activeBlock.blockType !== 'image'" v-model="expandedPayload" class="payload-collapse"><el-collapse-item :name="activeBlock.id"><template #title>高级数据 <el-icon class="payload-more"><MoreFilled /></el-icon><span v-if="payloadIsInvalid(activeBlock)" class="payload-invalid">JSON 格式待修正</span></template><el-input v-model="payloadTexts[activeBlock.id]" :name="`block-payload-${activeBlock.id}`" autocomplete="off" type="textarea" :rows="10" class="payload-editor" spellcheck="false" @input="scheduleBlockSave" /></el-collapse-item></el-collapse>
              </template>
            </section>
          </div>
        </section>
        <Teleport to="body">
          <aside v-show="previewVisible" ref="previewPanelRef" class="editor-preview-panel" role="region" aria-label="实时预览" :style="{ transform: `translate3d(${previewOffset.x}px, ${previewOffset.y}px, 0)` }">
            <header><div><p class="eyebrow">实时预览</p><strong>{{ previewNode?.title }}</strong></div><div class="preview-header-actions" @pointerdown.stop><el-radio-group v-model="previewMode" size="small"><el-radio-button value="block">当前块</el-radio-button><el-radio-button value="node">当前节点</el-radio-button></el-radio-group><el-button circle :icon="RefreshRight" aria-label="还原实时预览位置" title="还原到右侧" @click="resetPreviewPosition" /><el-button circle :icon="Hide" aria-label="隐藏实时预览" title="隐藏实时预览" @click="previewVisible = false" /></div><button class="preview-drag-handle" type="button" aria-label="拖动实时预览" @pointerdown="startPreviewDrag"><el-icon><Rank /></el-icon></button></header>
            <div ref="previewScrollRef" class="editor-preview-scroll">
              <article class="editor-preview-article"><div v-if="previewNode" class="preview-node-meta"><el-tag effect="plain">{{ zh(previewNode.nodeType) }}</el-tag><el-tag v-if="previewNode.semanticRole" type="success" effect="plain">{{ zh(previewNode.semanticRole) }}</el-tag></div><h1>{{ previewHeading }}</h1><div v-for="block in visiblePreviewBlocks" :key="block.id" class="editor-preview-block" :class="{ active: activeBlockId === block.id }" :data-preview-block-id="block.id" @click="activateBlock(block.id)"><ContentBlockView :block="block" :asset-base-url="`/api/admin/versions/${versionId}/editor/assets`" /></div><el-empty v-if="!visiblePreviewBlocks.length" description="暂无可预览内容" :image-size="72" /></article>
            </div>
          </aside>
        </Teleport>
      </main>
      <el-drawer
        v-model="nodePropertiesOpen"
        class="node-property-drawer"
        title="节点属性"
        direction="rtl"
        size="500px"
        append-to-body
        :before-close="beforeCloseNodeProperties"
        :close-on-click-modal="!nodeSaving"
        :close-on-press-escape="!nodeSaving"
      >
        <section class="node-property-context" aria-label="当前节点位置">
          <div>
            <span class="node-property-context-label">当前位置</span>
            <span v-if="selectedNode" class="node-property-level">第 {{ selectedNode.level }} 级</span>
          </div>
          <p>{{ nodeFormPath }}</p>
        </section>

        <el-form label-position="top" class="node-property-form">
          <section class="node-property-section" aria-labelledby="node-basic-heading">
            <header>
              <div><span class="node-property-step">01</span><h3 id="node-basic-heading">基本信息</h3></div>
              <p>标题会同步显示在目录与阅读页中。</p>
            </header>
            <el-form-item label="标题" :error="nodeTitleError">
              <el-input
                v-model="nodeForm.title"
                name="node-title"
                autocomplete="off"
                autofocus
                clearable
                aria-describedby="node-title-help"
              />
              <span id="node-title-help" class="node-property-help">输入能准确表达当前内容范围的名称。</span>
            </el-form-item>
          </section>

          <fieldset class="node-property-section node-property-fieldset">
            <legend>
              <span><span class="node-property-step">02</span>结构类型</span>
              <small>决定目录层级中的结构定位</small>
            </legend>
            <div class="node-type-grid">
              <label v-for="type in nodeTypeChoices" :key="type.value" class="node-type-choice">
                <input v-model="nodeForm.nodeType" type="radio" name="node-type" :value="type.value" />
                <span class="node-type-choice-content">
                  <i aria-hidden="true" />
                  <span><strong>{{ type.label }}</strong><small>{{ type.description }}</small></span>
                </span>
              </label>
            </div>
          </fieldset>

          <fieldset class="node-property-section node-property-fieldset">
            <legend>
              <span><span class="node-property-step">03</span>内容语义</span>
              <small>可选，描述该节点在阅读内容中的作用</small>
            </legend>
            <div class="semantic-role-grid">
              <label v-for="role in semanticRoleChoices" :key="role.value ?? 'none'" class="semantic-role-choice">
                <input v-model="nodeForm.semanticRole" type="radio" name="semantic-role" :value="role.value" />
                <span>{{ role.label }}</span>
              </label>
            </div>
            <p class="semantic-role-description">{{ selectedSemanticRoleDescription }}</p>
          </fieldset>
        </el-form>

        <template #footer>
          <div class="drawer-footer node-property-footer">
            <span class="node-property-status" :class="{ dirty: nodeFormDirty, saving: nodeSaving }" aria-live="polite">
              <i aria-hidden="true" />{{ nodePropertyStatusLabel }}
            </span>
            <div>
              <el-button data-testid="cancel-node-properties" :disabled="nodeSaving" @click="requestCloseNodeProperties">取消</el-button>
              <el-button
                data-testid="save-node-properties"
                type="primary"
                :icon="EditPen"
                :loading="nodeSaving"
                :disabled="!nodeFormDirty || !nodeFormValid"
                @click="saveNode"
              >保存节点</el-button>
            </div>
          </div>
        </template>
      </el-drawer>
    </div>
  </section>
</template>
