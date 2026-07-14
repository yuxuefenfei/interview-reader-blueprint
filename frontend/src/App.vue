<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import {
  commitImportJob,
  createNote,
  exportDocument,
  getAuthSession,
  getDocument,
  getImportIssues,
  getImportJob,
  getNodeContent,
  getNormalizedPackage,
  getReadingProgress,
  getReviewQueue,
  getToc,
  listDocuments,
  login,
  logout,
  publishVersion,
  saveBookmark,
  saveReadingProgress,
  saveReviewState,
  searchContent,
  sourceFileUrl,
  reviseStagedBlock,
  reviseStagedSection,
  uploadSourceFile
} from "./api/client";
import ContentBlockView from "./components/ContentBlockView.vue";
import IconButton from "./components/IconButton.vue";
import TocTree from "./components/TocTree.vue";
import { cacheNodeContent, clearNodeContentCache, getCachedNodeContent } from "./offline/contentCache";
import { enqueueReadingProgress, flushReadingProgressQueue } from "./offline/progressQueue";
import type {
  ContentBlock,
  DocumentSummary,
  ExportFormat,
  ImportIssue,
  ImportJob,
  Mastery,
  NodeContent,
  NormalizedPackage,
  ReadingProgress,
  ReviewQueueItem,
  SearchHit,
  SourceBbox,
  SourceType,
  StagedBlock,
  StagedSection,
  TocNode
} from "./types/api";
import { pageForBbox, sourceOverlayLabel, sourceOverlayStyle } from "./utils/sourceBbox";
import { blockAtViewportAnchor, scrollTopForBlockOffset } from "./utils/readingPosition";
import { normalizeReaderBlocks } from "./utils/contentBlocks";
import { firstReadableNode, flattenToc, isQuestionNode, progressRatioForNode, questionAnswerNodes } from "./utils/toc";

const authReady = ref(false);
const authSession = ref({ authenticated: false, username: null as string | null });
const loginForm = ref({ username: "", password: "" });
const loginBusy = ref(false);
const loginError = ref("");
const documents = ref<DocumentSummary[]>([]);
const documentNextCursor = ref<string | null>(null);
const loadingMoreDocuments = ref(false);
const selectedDocument = ref<DocumentSummary | null>(null);
const toc = ref<TocNode[]>([]);
const activeNode = ref<TocNode | null>(null);
const content = ref<NodeContent | null>(null);
const loading = ref(false);
const busyMessage = ref("");
const error = ref("");
const query = ref("");
const searchHits = ref<SearchHit[]>([]);
const searchStatus = ref("");
const exportingFormat = ref<ExportFormat | null>(null);
const libraryOpen = ref(false);
const mobileToolsOpen = ref(false);
const mobileSearchOpen = ref(false);
const theme = ref(localStorage.getItem("reader.theme") || "light");
const fontScale = ref(Number(localStorage.getItem("reader.fontScale") || "18"));
const lineHeight = ref(Number(localStorage.getItem("reader.lineHeight") || "1.82"));
const tocOpen = ref(false);
const noteBody = ref("");
const interactionMessage = ref("");
const selectedMastery = ref<Mastery>("UNKNOWN");
const reviewQueue = ref<ReviewQueueItem[]>([]);
const answerExpanded = ref(false);
const answerContents = ref<Record<string, NodeContent>>({});
const importReviewJob = ref<ImportJob | null>(null);
const importIssues = ref<ImportIssue[]>([]);
const normalizedPackage = ref<NormalizedPackage | null>(null);
const selectedSourcePage = ref<number | null>(null);
const selectedSourceBbox = ref<SourceBbox | null>(null);
const readerMain = ref<HTMLElement | null>(null);
const currentReadingBlock = ref<ContentBlock | null>(null);
const currentBlockViewportOffset = ref(0);
const currentProgressRevision = ref(0);
const deviceId = localStorage.getItem("reader.deviceId") || crypto.randomUUID();
let onlineListenerAttached = false;
let progressListenersAttached = false;
let progressSaveTimer: number | null = null;
let readingPositionFrame: number | null = null;
let lastReadingPositionCheck = 0;
let lastPersistedProgressSignature = "";
let readerTouchStart: { x: number; y: number; target: EventTarget | null } | null = null;

localStorage.setItem("reader.deviceId", deviceId);

const allNodes = computed(() => flattenToc(toc.value));
const readableNodes = computed(() => allNodes.value.filter((node) => isQuestionNode(node) || node.children.length === 0));
const activeIndex = computed(() => readableNodes.value.findIndex((node) => node.id === activeNode.value?.id));
const previousNode = computed(() => (activeIndex.value > 0 ? readableNodes.value[activeIndex.value - 1] : null));
const nextNode = computed(() =>
  activeIndex.value >= 0 && activeIndex.value < readableNodes.value.length - 1 ? readableNodes.value[activeIndex.value + 1] : null
);
const currentBlock = computed(() => currentReadingBlock.value ?? content.value?.blocks[0] ?? null);
const readerBlocks = computed(() => normalizeReaderBlocks(content.value?.blocks ?? []));
const isQuestionMode = computed(() => isQuestionNode(activeNode.value));
const answerNodes = computed(() => questionAnswerNodes(activeNode.value));
const stagedSections = computed(() => normalizedPackage.value?.sections.slice(0, 12) ?? []);
const stagedBlocks = computed(() => normalizedPackage.value?.blocks.slice(0, 12) ?? []);
const importSourceUrl = computed(() =>
  importReviewJob.value ? sourceFileUrl(importReviewJob.value.id, selectedSourcePage.value) : ""
);
const isReviewPdf = computed(() => String(normalizedPackage.value?.version.sourceType ?? "").toUpperCase() === "PDF");
const selectedSourceOverlay = computed(() => sourceOverlayStyle(selectedSourceBbox.value));
const selectedSourceLabel = computed(() => sourceOverlayLabel(selectedSourceBbox.value, selectedSourcePage.value));
const currentProgressPercent = computed(() => activeNode.value ? Math.round(progressRatioForNode(toc.value, activeNode.value.id) * 100) : 0);
const exportFormats: Array<{ format: ExportFormat; label: string }> = [
  { format: "JSON_PACKAGE", label: "JSON" },
  { format: "EXCEL", label: "Excel" },
  { format: "MARKDOWN", label: "Markdown" },
  { format: "STATIC_HTML", label: "HTML" }
];
const terminalImportStatuses = new Set(["READY", "REVIEW_REQUIRED", "IMPORTED", "FAILED"]);

const readerStyle = computed(() => ({
  "--reader-font-size": `${fontScale.value}px`,
  "--reader-line-height": String(lineHeight.value)
}));

watch(theme, (value) => {
  localStorage.setItem("reader.theme", value);
});

watch(fontScale, (value) => {
  localStorage.setItem("reader.fontScale", String(value));
});

watch(lineHeight, (value) => {
  localStorage.setItem("reader.lineHeight", String(value));
});

onMounted(() => {
  void initializeSession();
});

onBeforeUnmount(() => {
  if (onlineListenerAttached) {
    window.removeEventListener("online", flushQueuedProgress);
  }
  if (progressListenersAttached) {
    document.removeEventListener("visibilitychange", saveProgressWhenHidden);
    window.removeEventListener("pagehide", saveProgressWhenHidden);
  }
  if (progressSaveTimer !== null) {
    window.clearTimeout(progressSaveTimer);
  }
  if (readingPositionFrame !== null) {
    window.cancelAnimationFrame(readingPositionFrame);
  }
});

async function initializeSession(): Promise<void> {
  try {
    authSession.value = await getAuthSession();
  } catch {
    authSession.value = { authenticated: false, username: null };
  } finally {
    authReady.value = true;
  }
  if (authSession.value.authenticated) {
    startAuthenticatedApp();
    await refreshDocuments();
  }
}

async function submitLogin(): Promise<void> {
  loginBusy.value = true;
  loginError.value = "";
  try {
    authSession.value = await login(loginForm.value);
    loginForm.value.password = "";
    startAuthenticatedApp();
    await refreshDocuments();
  } catch (caught) {
    loginError.value = caught instanceof Error ? caught.message : "登录失败";
  } finally {
    loginBusy.value = false;
  }
}

async function signOut(): Promise<void> {
  await logout().catch(() => undefined);
  authSession.value = { authenticated: false, username: null };
  loginForm.value.password = "";
  resetWorkspace();
}

function startAuthenticatedApp(): void {
  flushQueuedProgress();
  if (!onlineListenerAttached) {
    window.addEventListener("online", flushQueuedProgress);
    onlineListenerAttached = true;
  }
  if (!progressListenersAttached) {
    document.addEventListener("visibilitychange", saveProgressWhenHidden);
    window.addEventListener("pagehide", saveProgressWhenHidden);
    progressListenersAttached = true;
  }
  if (readingPositionFrame === null) {
    readingPositionFrame = window.requestAnimationFrame(pollReadingPosition);
  }
}

function resetWorkspace(): void {
  documents.value = [];
  documentNextCursor.value = null;
  loadingMoreDocuments.value = false;
  selectedDocument.value = null;
  toc.value = [];
  activeNode.value = null;
  content.value = null;
  currentReadingBlock.value = null;
  currentBlockViewportOffset.value = 0;
  currentProgressRevision.value = 0;
  lastPersistedProgressSignature = "";
  reviewQueue.value = [];
  searchHits.value = [];
  searchStatus.value = "";
  error.value = "";
  clearImportReview();
}

async function refreshDocuments(): Promise<void> {
  await run("正在加载文档", async () => {
    const response = await listDocuments(query.value, null);
    documents.value = response.items;
    documentNextCursor.value = response.nextCursor;
    if (!selectedDocument.value && response.items.length > 0) {
      await selectDocument(response.items[0]);
    }
  });
}

async function loadMoreDocuments(): Promise<void> {
  if (!documentNextCursor.value || loadingMoreDocuments.value) {
    return;
  }
  loadingMoreDocuments.value = true;
  try {
    const response = await listDocuments(query.value, documentNextCursor.value);
    const existingIds = new Set(documents.value.map((document) => document.id));
    documents.value = [...documents.value, ...response.items.filter((document) => !existingIds.has(document.id))];
    documentNextCursor.value = response.nextCursor;
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : "加载更多文档失败";
  } finally {
    loadingMoreDocuments.value = false;
  }
}

async function searchLibrary(): Promise<void> {
  await refreshDocuments();
  await searchReadableContent();
}

async function searchReadableContent(): Promise<void> {
  const term = query.value.trim();
  searchHits.value = [];
  searchStatus.value = "";
  if (!term) {
    return;
  }
  try {
    searchHits.value = await searchContent(term, selectedDocument.value?.id ?? null, 8);
    searchStatus.value = searchHits.value.length === 0 ? "没有正文命中" : `${searchHits.value.length} 条正文命中`;
  } catch (caught) {
    searchStatus.value = caught instanceof Error ? caught.message : "搜索失败";
  }
}

async function selectDocument(document: DocumentSummary): Promise<void> {
  clearImportReview();
  libraryOpen.value = false;
  if (!document.currentVersionId) {
    selectedDocument.value = document;
    reviewQueue.value = [];
    toc.value = [];
    content.value = null;
    activeNode.value = null;
    return;
  }
  const versionId = document.currentVersionId;
  await run("正在打开文档", async () => {
    selectedDocument.value = document;
    reviewQueue.value = [];
    toc.value = await getToc(versionId);
    const progress = await getReadingProgress(document.id).catch(() => null);
    currentProgressRevision.value = progress?.revision ?? 0;
    const resumeNode =
      progress?.sectionId ? allNodes.value.find((node) => node.id === progress.sectionId) ?? null : null;
    await selectNode(resumeNode ?? firstReadableNode(toc.value), progress?.versionId === versionId ? progress : null);
  });
}

function clearImportReview(): void {
  importReviewJob.value = null;
  importIssues.value = [];
  normalizedPackage.value = null;
  selectedSourcePage.value = null;
  selectedSourceBbox.value = null;
}

async function selectNode(node: TocNode | null, restoreProgress: ReadingProgress | null = null): Promise<void> {
  if (!node || !selectedDocument.value?.currentVersionId) {
    return;
  }
  if (activeNode.value && activeNode.value.id !== node.id) {
    await saveProgress();
  }
  await run("正在加载正文", async () => {
    activeNode.value = node;
    content.value = await loadNodeContent(selectedDocument.value!.currentVersionId!, node.id, 50);
    noteBody.value = "";
    interactionMessage.value = "";
    selectedMastery.value = "UNKNOWN";
    answerExpanded.value = false;
    answerContents.value = {};
    tocOpen.value = false;
    mobileToolsOpen.value = false;
    await nextTick();
    restoreReadingPosition(restoreProgress);
    if (!restoreProgress) {
      await saveProgress();
    }
  });
}

async function loadNodeContent(versionId: string, nodeId: string, limit: number): Promise<NodeContent> {
  try {
    const loaded = await getNodeContent(versionId, nodeId, limit);
    void cacheNodeContent(versionId, nodeId, limit, loaded).catch(() => undefined);
    return loaded;
  } catch (caught) {
    const cached = await getCachedNodeContent(versionId, nodeId, limit);
    if (cached) {
      interactionMessage.value = "已显示离线缓存内容";
      return cached;
    }
    throw caught;
  }
}

function restoreReadingPosition(progress: ReadingProgress | null): void {
  const main = readerMain.value;
  const fallbackBlock = content.value?.blocks[0] ?? null;
  const restoredBlock = progress?.blockId
    ? content.value?.blocks.find((block) => block.id === progress.blockId) ?? fallbackBlock
    : fallbackBlock;
  const hasSavedBlock = progress?.blockId === restoredBlock?.id;
  currentReadingBlock.value = restoredBlock;
  currentBlockViewportOffset.value = hasSavedBlock ? progress?.blockViewportOffset ?? 0 : 0;
  if (progress) {
    lastPersistedProgressSignature = progressSignature(progress);
  }
  if (!main || !restoredBlock) {
    return;
  }
  const target = Array.from(main.querySelectorAll<HTMLElement>("[data-block-id]"))
    .find((element) => element.dataset.blockId === restoredBlock.id);
  if (!target) {
    main.scrollTo({ top: 0, behavior: "auto" });
    return;
  }
  const mainRect = main.getBoundingClientRect();
  const targetRect = target.getBoundingClientRect();
  const savedOffset = hasSavedBlock ? progress?.blockViewportOffset ?? 0 : 0;
  main.scrollTo({
    top: scrollTopForBlockOffset(main.scrollTop, targetRect.top - mainRect.top, savedOffset),
    behavior: "auto"
  });
}

function captureReadingPosition(): boolean {
  const main = readerMain.value;
  const blocks = content.value?.blocks;
  if (!main || !blocks?.length) {
    return false;
  }
  const mainRect = main.getBoundingClientRect();
  const elements = Array.from(main.querySelectorAll<HTMLElement>("[data-block-id]"));
  const anchorY = mainRect.top + Math.min(40, Math.max(20, main.clientHeight * 0.1));
  const visibleBlock = blockAtViewportAnchor(
    elements.map((element) => {
      const rect = element.getBoundingClientRect();
      return { id: element.dataset.blockId ?? "", top: rect.top, bottom: rect.bottom };
    }),
    anchorY
  );
  const nextBlock = visibleBlock ? blocks.find((block) => block.id === visibleBlock.id) ?? null : null;
  if (!nextBlock) {
    return false;
  }
  const nextOffset = Math.round(visibleBlock!.top - mainRect.top);
  const changed = currentReadingBlock.value?.id !== nextBlock.id || currentBlockViewportOffset.value !== nextOffset;
  currentReadingBlock.value = nextBlock;
  currentBlockViewportOffset.value = nextOffset;
  return changed;
}

function onReaderScroll(): void {
  if (!captureReadingPosition()) {
    return;
  }
  scheduleProgressSave();
}

function openPrimaryNavigation(): void {
  if (window.matchMedia("(max-width: 640px)").matches) {
    libraryOpen.value = true;
    return;
  }
  tocOpen.value = !tocOpen.value;
}

function startReaderTouch(event: TouchEvent): void {
  const touch = event.changedTouches[0];
  if (!touch) {
    return;
  }
  readerTouchStart = { x: touch.clientX, y: touch.clientY, target: event.target };
}

function endReaderTouch(event: TouchEvent): void {
  const start = readerTouchStart;
  readerTouchStart = null;
  const touch = event.changedTouches[0];
  if (!start || !touch || Math.abs(touch.clientX - start.x) < 72 || Math.abs(touch.clientX - start.x) <= Math.abs(touch.clientY - start.y)) {
    return;
  }
  if (start.target instanceof Element && start.target.closest("pre, code, .table-wrap, input, textarea, button, a")) {
    return;
  }
  void selectNode(touch.clientX < start.x ? nextNode.value : previousNode.value);
}

function pollReadingPosition(timestamp: number): void {
  if (document.visibilityState !== "hidden" && timestamp - lastReadingPositionCheck >= 250) {
    lastReadingPositionCheck = timestamp;
    if (captureReadingPosition()) {
      void saveProgress();
    }
  }
  readingPositionFrame = window.requestAnimationFrame(pollReadingPosition);
}

function scheduleProgressSave(): void {
  if (progressSaveTimer !== null) {
    window.clearTimeout(progressSaveTimer);
  }
  progressSaveTimer = window.setTimeout(() => {
    progressSaveTimer = null;
    void saveProgress();
  }, 2_000);
}

function currentProgress(): ReadingProgress | null {
  if (!selectedDocument.value?.currentVersionId || !activeNode.value) {
    return null;
  }
  return {
    versionId: selectedDocument.value.currentVersionId,
    sectionId: activeNode.value.id,
    blockId: currentReadingBlock.value?.id ?? null,
    charOffset: 0,
    blockViewportOffset: currentBlockViewportOffset.value,
    progressRatio: progressRatioForNode(toc.value, activeNode.value.id),
    clientUpdatedAt: new Date().toISOString(),
    deviceId,
    revision: currentProgressRevision.value
  };
}

async function saveProgress(): Promise<void> {
  const progress = currentProgress();
  if (!progress || !selectedDocument.value) {
    return;
  }
  const signature = progressSignature(progress);
  if (signature === lastPersistedProgressSignature) {
    return;
  }
  try {
    const saved = await saveReadingProgress(selectedDocument.value.id, progress);
    currentProgressRevision.value = saved.revision;
    lastPersistedProgressSignature = progressSignature(saved);
    flushQueuedProgress();
  } catch {
    lastPersistedProgressSignature = signature;
    await enqueueReadingProgress(selectedDocument.value.id, progress);
  }
}

function progressSignature(progress: ReadingProgress): string {
  return [
    progress.versionId,
    progress.sectionId ?? "",
    progress.blockId ?? "",
    progress.charOffset,
    progress.blockViewportOffset,
    progress.progressRatio
  ].join("|");
}

function saveProgressWhenHidden(event: Event): void {
  if (document.visibilityState !== "hidden" && event.type !== "pagehide") {
    return;
  }
  const progress = currentProgress();
  if (!progress || !selectedDocument.value) {
    return;
  }
  const body = new Blob([JSON.stringify(progress)], { type: "application/json" });
  if (!navigator.sendBeacon?.(`/api/reading-progress/${selectedDocument.value.id}`, body)) {
    void enqueueReadingProgress(selectedDocument.value.id, progress);
  }
}

function flushQueuedProgress(): void {
  void flushReadingProgressQueue(saveReadingProgress).catch(() => undefined);
}

async function bookmarkCurrentBlock(): Promise<void> {
  const target = interactionTarget();
  if (!target) {
    return;
  }
  await runInteraction("正在收藏", async () => {
    await saveBookmark({
      documentId: target.documentId,
      versionId: target.versionId,
      sectionId: target.sectionId,
      blockId: target.blockId,
      title: activeNode.value?.title ?? null
    });
    interactionMessage.value = "已收藏当前内容";
  });
}

async function saveCurrentNote(): Promise<void> {
  const target = interactionTarget();
  if (!target || !noteBody.value.trim()) {
    return;
  }
  await runInteraction("正在保存笔记", async () => {
    await createNote({
      documentId: target.documentId,
      versionId: target.versionId,
      sectionId: target.sectionId,
      blockId: target.blockId,
      selectedText: currentBlock.value?.plainText.slice(0, 80) ?? null,
      body: noteBody.value.trim()
    });
    noteBody.value = "";
    interactionMessage.value = "笔记已保存";
  });
}

async function markMastery(mastery: Mastery): Promise<void> {
  if (!selectedDocument.value || !activeNode.value) {
    return;
  }
  await runInteraction("正在更新掌握度", async () => {
    const state = await saveReviewState(activeNode.value!.id, selectedDocument.value!.id, mastery);
    selectedMastery.value = state.mastery;
    interactionMessage.value = `掌握度已标记为 ${masteryLabel(state.mastery)}`;
  });
}

async function loadReviewQueue(dueOnly: boolean): Promise<void> {
  if (!selectedDocument.value) {
    interactionMessage.value = "请先选择文档";
    return;
  }
  await runInteraction(dueOnly ? "正在加载待复习题" : "正在随机抽题", async () => {
    reviewQueue.value = await getReviewQueue(selectedDocument.value!.id, 5, dueOnly);
    interactionMessage.value = reviewQueue.value.length === 0 ? "当前没有待复习题" : `已载入 ${reviewQueue.value.length} 题`;
  });
}

async function clearOfflineCache(): Promise<void> {
  await runInteraction("正在清理离线内容", async () => {
    await clearNodeContentCache();
    interactionMessage.value = "离线内容已清理";
  });
}

async function exportCurrentDocument(format: ExportFormat): Promise<void> {
  if (!selectedDocument.value?.currentVersionId) {
    interactionMessage.value = "请先选择已发布文档";
    return;
  }
  exportingFormat.value = format;
  await runInteraction("正在导出", async () => {
    const blob = await exportDocument(selectedDocument.value!.id, selectedDocument.value!.currentVersionId!, format);
    saveBlob(blob, exportFileName(selectedDocument.value!.title, format));
    interactionMessage.value = `已导出 ${exportLabel(format)}`;
  });
  exportingFormat.value = null;
}

function saveBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = fileName;
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}

function exportFileName(title: string, format: ExportFormat): string {
  const safeTitle = title.trim().replace(/[\\/:*?"<>|]+/g, "-").slice(0, 80) || "interview-reader";
  return `${safeTitle}.${exportExtension(format)}`;
}

function exportExtension(format: ExportFormat): string {
  return {
    JSON_PACKAGE: "json",
    EXCEL: "xlsx",
    MARKDOWN: "md",
    STATIC_HTML: "html"
  }[format];
}

function exportLabel(format: ExportFormat): string {
  return exportFormats.find((item) => item.format === format)?.label ?? format;
}

async function openReviewItem(item: ReviewQueueItem): Promise<void> {
  if (item.documentId !== selectedDocument.value?.id) {
    return;
  }
  const node = allNodes.value.find((candidate) => candidate.id === item.nodeId);
  if (!node) {
    interactionMessage.value = "题目不在当前目录中";
    return;
  }
  await selectNode(node);
  selectedMastery.value = item.mastery;
}

async function openSearchHit(hit: SearchHit): Promise<void> {
  await runInteraction("正在打开搜索结果", async () => {
    let targetDocument = documents.value.find((candidate) => candidate.id === hit.documentId) ?? null;
    if (!targetDocument) {
      targetDocument = await getDocument(hit.documentId);
      documents.value = [
        targetDocument,
        ...documents.value.filter((candidate) => candidate.id !== targetDocument!.id)
      ];
    }
    if (selectedDocument.value?.id !== targetDocument.id) {
      await selectDocument(targetDocument);
    }
    const node = allNodes.value.find((candidate) => candidate.id === hit.nodeId);
    if (!node) {
      interactionMessage.value = "命中章节暂不可用";
      return;
    }
    await selectNode(node);
    interactionMessage.value = `已定位：${hit.title}`;
  });
}

function toggleMobileTools(): void {
  mobileToolsOpen.value = !mobileToolsOpen.value;
}

function toggleMobileSearch(): void {
  mobileSearchOpen.value = !mobileSearchOpen.value;
  mobileToolsOpen.value = false;
}

function openMobileToc(): void {
  tocOpen.value = true;
  mobileToolsOpen.value = false;
}

async function toggleAnswer(): Promise<void> {
  if (!selectedDocument.value?.currentVersionId || answerNodes.value.length === 0) {
    return;
  }
  if (answerExpanded.value) {
    answerExpanded.value = false;
    return;
  }
  await runInteraction("正在加载答案", async () => {
    const versionId = selectedDocument.value!.currentVersionId!;
    const missingNodes = answerNodes.value.filter((node) => !answerContents.value[node.id]);
    const loaded = await Promise.all(missingNodes.map((node) => getNodeContent(versionId, node.id)));
    answerContents.value = {
      ...answerContents.value,
      ...Object.fromEntries(loaded.map((nodeContent) => [nodeContent.node.id, nodeContent]))
    };
    answerExpanded.value = true;
    interactionMessage.value = "答案已展开";
  });
}

function interactionTarget(): { documentId: string; versionId: string; sectionId: string; blockId: string } | null {
  if (!selectedDocument.value?.currentVersionId || !activeNode.value || !currentBlock.value) {
    interactionMessage.value = "当前章节暂无可操作内容";
    return null;
  }
  return {
    documentId: selectedDocument.value.id,
    versionId: selectedDocument.value.currentVersionId,
    sectionId: activeNode.value.id,
    blockId: currentBlock.value.id
  };
}

async function runInteraction(message: string, action: () => Promise<void>): Promise<void> {
  interactionMessage.value = message;
  try {
    await action();
  } catch (caught) {
    interactionMessage.value = caught instanceof Error ? caught.message : "操作失败";
  }
}

function masteryLabel(mastery: Mastery): string {
  return {
    UNKNOWN: "未标记",
    HARD: "不会",
    FUZZY: "模糊",
    KNOWN: "会"
  }[mastery];
}

async function importSourceFile(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = "";
  if (!file) {
    return;
  }
  const sourceType = sourceTypeForFile(file);
  await run(importMessage(sourceType), async () => {
    const uploadedJob = await uploadSourceFile(file, sourceType);
    const job = await waitForTerminalImportJob(uploadedJob);
    if (job.status === "FAILED") {
      throw new Error(job.errorMessage || "导入失败");
    }
    if (job.status === "REVIEW_REQUIRED") {
      await loadImportReview(job);
      return;
    }
    if (job.status !== "READY" && job.status !== "IMPORTED") {
      throw new Error(`导入状态暂不可提交：${job.status}`);
    }
    const version = await commitImportJob(job.id);
    await publishVersion(version.documentId, version.id);
    selectedDocument.value = null;
    await refreshDocuments();
  });
}

async function waitForTerminalImportJob(job: ImportJob): Promise<ImportJob> {
  var current = job;
  for (var attempt = 0; attempt < 60 && !terminalImportStatuses.has(current.status); attempt++) {
    busyMessage.value = `正在处理导入：${current.currentStage ?? current.status}`;
    await delay(1000);
    current = await getImportJob(current.id);
  }
  if (!terminalImportStatuses.has(current.status)) {
    throw new Error("导入任务仍在处理中，请稍后重试");
  }
  return current;
}

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, milliseconds));
}

async function loadImportReview(job: ImportJob): Promise<void> {
  importReviewJob.value = job;
  selectedDocument.value = null;
  toc.value = [];
  activeNode.value = null;
  content.value = null;
  normalizedPackage.value = await getNormalizedPackage(job.id);
  importIssues.value = await getImportIssues(job.id);
  selectedSourcePage.value = firstReviewSourcePage();
  selectedSourceBbox.value = firstReviewSourceBbox();
}

async function refreshImportReview(jobId: string, normalized?: NormalizedPackage): Promise<void> {
  normalizedPackage.value = normalized ?? await getNormalizedPackage(jobId);
  importIssues.value = await getImportIssues(jobId);
  importReviewJob.value = await getImportJob(jobId);
  selectedSourcePage.value = selectedSourcePage.value ?? firstReviewSourcePage();
  selectedSourceBbox.value = selectedSourceBbox.value ?? firstReviewSourceBbox();
}

function firstReviewSourcePage(): number | null {
  for (const issue of importIssues.value) {
    const page = sourcePageForIssue(issue);
    if (page) {
      return page;
    }
  }
  return stagedSections.value.find((section) => section.sourcePageStart)?.sourcePageStart
    ?? stagedBlocks.value.find((block) => block.sourcePage)?.sourcePage
    ?? null;
}

function firstReviewSourceBbox(): SourceBbox | null {
  for (const issue of importIssues.value) {
    const bbox = bboxForIssue(issue);
    if (bbox) {
      return bbox;
    }
  }
  return stagedBlocks.value.find((block) => block.sourceBbox)?.sourceBbox
    ?? stagedSections.value.find((section) => section.sourceBbox)?.sourceBbox
    ?? null;
}

function sourcePageForIssue(issue: ImportIssue): number | null {
  if (issue.sourcePage) {
    return issue.sourcePage;
  }
  if (issue.blockKey) {
    const block = stagedBlocks.value.find((candidate) => candidate.blockKey === issue.blockKey);
    return block?.sourcePage ?? pageForBbox(block?.sourceBbox) ?? null;
  }
  if (issue.sectionKey) {
    const section = stagedSections.value.find((candidate) => candidate.sectionKey === issue.sectionKey);
    return section?.sourcePageStart ?? pageForBbox(section?.sourceBbox) ?? null;
  }
  return null;
}

function bboxForIssue(issue: ImportIssue): SourceBbox | null {
  if (issue.blockKey) {
    return stagedBlocks.value.find((block) => block.blockKey === issue.blockKey)?.sourceBbox ?? null;
  }
  if (issue.sectionKey) {
    return stagedSections.value.find((section) => section.sectionKey === issue.sectionKey)?.sourceBbox ?? null;
  }
  return null;
}

function focusIssueSource(issue: ImportIssue): void {
  selectedSourceBbox.value = bboxForIssue(issue);
  selectedSourcePage.value = sourcePageForIssue(issue) ?? selectedSourcePage.value;
}

function focusSectionSource(section: StagedSection): void {
  selectedSourceBbox.value = section.sourceBbox;
  selectedSourcePage.value = section.sourcePageStart ?? pageForBbox(section.sourceBbox) ?? selectedSourcePage.value;
}

function focusBlockSource(block: StagedBlock): void {
  selectedSourceBbox.value = block.sourceBbox;
  selectedSourcePage.value = block.sourcePage ?? pageForBbox(block.sourceBbox) ?? selectedSourcePage.value;
}

function bboxLabel(block: StagedBlock): string {
  const bbox = block.sourceBbox;
  if (!bbox || bbox.width == null || bbox.height == null) {
    return "";
  }
  return `bbox ${Math.round(Number(bbox.width))}x${Math.round(Number(bbox.height))}`;
}

async function saveStagedSection(section: StagedSection): Promise<void> {
  if (!importReviewJob.value) {
    return;
  }
  await run("正在保存章节修订", async () => {
    const normalized = await reviseStagedSection(importReviewJob.value!.id, section.sectionKey, {
      parentSectionKey: section.parentSectionKey || null,
      level: Number(section.level),
      title: section.title,
      nodeType: section.nodeType,
      semanticRole: section.semanticRole || null,
      sortOrder: Number(section.sortOrder),
      anchor: section.anchor
    });
    await refreshImportReview(importReviewJob.value!.id, normalized);
  });
}

async function saveStagedBlock(block: StagedBlock): Promise<void> {
  if (!importReviewJob.value) {
    return;
  }
  await run("正在保存内容修订", async () => {
    const payload =
      typeof block.payload.text === "string" ? { ...block.payload, text: block.plainText } : block.payload;
    const normalized = await reviseStagedBlock(importReviewJob.value!.id, block.blockKey, {
      sectionKey: block.sectionKey,
      seq: Number(block.seq),
      blockType: block.blockType,
      payload,
      plainText: block.plainText,
      language: block.language || null
    });
    await refreshImportReview(importReviewJob.value!.id, normalized);
  });
}

async function commitReviewedImport(): Promise<void> {
  if (!importReviewJob.value) {
    return;
  }
  await run("正在提交复核结果", async () => {
    const version = await commitImportJob(importReviewJob.value!.id);
    await publishVersion(version.documentId, version.id);
    clearImportReview();
    selectedDocument.value = null;
    await refreshDocuments();
  });
}

function sourceTypeForFile(file: File): SourceType {
  const name = file.name.toLowerCase();
  if (name.endsWith(".pdf")) {
    return "PDF";
  }
  if (name.endsWith(".xlsx")) {
    return "EXCEL";
  }
  if (name.endsWith(".md") || name.endsWith(".markdown")) {
    return "MARKDOWN";
  }
  return "JSON_PACKAGE";
}

function importMessage(sourceType: SourceType): string {
  return {
    JSON_PACKAGE: "正在导入 JSON Package",
    EXCEL: "正在导入 Excel Package",
    MARKDOWN: "正在导入 Markdown",
    PDF: "正在导入 PDF"
  }[sourceType];
}

async function run(message: string, action: () => Promise<void>): Promise<void> {
  loading.value = true;
  busyMessage.value = message;
  error.value = "";
  try {
    await action();
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : "操作失败";
  } finally {
    loading.value = false;
    busyMessage.value = "";
  }
}
</script>

<template>
  <div class="app-shell" :class="[`theme-${theme}`, { 'auth-mode': !authSession.authenticated }]" :style="readerStyle">
    <section v-if="!authReady" class="login-shell" aria-live="polite">
      <div class="login-panel">
        <span class="login-mark" aria-hidden="true"></span>
        <h1>Interview Reader</h1>
        <p>正在检查登录状态</p>
      </div>
    </section>

    <section v-else-if="!authSession.authenticated" class="login-shell">
      <form class="login-panel" @submit.prevent="submitLogin">
        <span class="login-mark" aria-hidden="true"></span>
        <h1>Interview Reader</h1>
        <p>登录后才能导入、阅读、搜索、导出和保存进度。</p>
        <label>
          用户名
          <input v-model.trim="loginForm.username" type="text" autocomplete="username" required />
        </label>
        <label>
          密码
          <input v-model="loginForm.password" type="password" autocomplete="current-password" required />
        </label>
        <button class="primary-action" type="submit" :disabled="loginBusy">
          {{ loginBusy ? "登录中" : "登录" }}
        </button>
        <span v-if="loginError" class="login-error">{{ loginError }}</span>
      </form>
    </section>

    <template v-else>
    <aside class="library-panel" :class="{ open: libraryOpen }">
      <div class="library-header">
        <h1>Interview Reader</h1>
        <button class="mobile-library-close" type="button" aria-label="关闭文档库" @click="libraryOpen = false">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M18 6 6 18M6 6l12 12" /></svg>
        </button>
        <label class="import-button">
          <input
            type="file"
            accept="application/json,.json,text/markdown,.md,.markdown,application/pdf,.pdf,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,.xlsx"
            @change="importSourceFile"
          />
          <span>导入</span>
        </label>
      </div>

      <input
        v-model="query"
        class="search-input"
        type="search"
        placeholder="搜索文档或正文"
        @keyup.enter="searchLibrary"
      />

      <section v-if="searchHits.length > 0 || searchStatus" class="search-results" aria-label="正文搜索结果">
        <header>
          <h2>正文命中</h2>
          <span>{{ searchStatus }}</span>
        </header>
        <button
          v-for="hit in searchHits"
          :key="`${hit.versionId}-${hit.blockId}`"
          class="search-hit"
          type="button"
          @click="openSearchHit(hit)"
        >
          <strong>{{ hit.title }}</strong>
          <span>{{ hit.snippet }}</span>
        </button>
      </section>

      <nav class="document-list" aria-label="文档列表">
        <button
          v-for="document in documents"
          :key="document.id"
          class="document-item"
          :class="{ active: document.id === selectedDocument?.id }"
          type="button"
          @click="selectDocument(document)"
        >
          <strong>{{ document.title }}</strong>
        </button>
      </nav>
      <button
        v-if="documentNextCursor"
        class="load-more-documents"
        type="button"
        :disabled="loadingMoreDocuments"
        @click="loadMoreDocuments"
      >
        {{ loadingMoreDocuments ? "加载中" : "加载更多" }}
      </button>
    </aside>

    <section class="reader-area">
      <header class="reader-toolbar">
        <IconButton label="文档库" @click="openPrimaryNavigation">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 6h16M4 12h16M4 18h16" /></svg>
        </IconButton>
        <div class="reader-title">
          <strong class="reader-document-label">{{ importReviewJob ? "导入复核" : selectedDocument?.title || "尚未选择文档" }}</strong>
          <span class="reader-section-label" v-if="activeNode">{{ activeNode.title }}</span>
          <span v-else-if="importReviewJob">{{ importReviewJob.status }}</span>
        </div>
        <div class="toolbar-actions">
          <IconButton class="desktop-nav-action" label="上一节" :disabled="!previousNode" @click="selectNode(previousNode)">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 18-6-6 6-6" /></svg>
          </IconButton>
          <IconButton class="desktop-nav-action" label="下一节" :disabled="!nextNode" @click="selectNode(nextNode)">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 18 6-6-6-6" /></svg>
          </IconButton>
          <IconButton label="主题" @click="theme = theme === 'light' ? 'sepia' : theme === 'sepia' ? 'dark' : 'light'">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3a9 9 0 1 0 9 9 7 7 0 0 1-9-9Z" /></svg>
          </IconButton>
          <IconButton class="logout-action" label="退出登录" @click="signOut">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M10 17l5-5-5-5M15 12H3M21 19V5a2 2 0 0 0-2-2h-5" /></svg>
          </IconButton>
        </div>
      </header>

      <div class="reader-body">
        <aside class="toc-panel" :class="{ open: tocOpen }">
          <button class="toc-close" type="button" aria-label="关闭目录" @click="tocOpen = false">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M18 6 6 18M6 6l12 12" /></svg>
          </button>
          <TocTree :nodes="toc" :active-node-id="activeNode?.id ?? null" @select="selectNode" />
        </aside>

        <main
          ref="readerMain"
          class="content-panel"
          data-reader-main
          @scroll.passive="onReaderScroll"
          @touchstart.passive="startReaderTouch"
          @touchend.passive="endReaderTouch"
        >
          <div v-if="loading" class="state-line">{{ busyMessage }}</div>
          <div v-else-if="error" class="state-line error">{{ error }}</div>
          <section v-else-if="importReviewJob && normalizedPackage" class="import-review">
            <header class="review-header">
              <div>
                <h2>{{ String(normalizedPackage.document.title ?? "待复核文档") }}</h2>
                <span>{{ importReviewJob.status }} · {{ importIssues.length }} issues</span>
              </div>
              <button
                class="primary-action"
                type="button"
                :disabled="importReviewJob.status !== 'READY'"
                @click="commitReviewedImport"
              >
                提交并发布
              </button>
            </header>

            <div class="review-split">
              <section class="source-preview" aria-label="源文件预览">
                <div v-if="isReviewPdf" class="source-frame">
                  <object :data="importSourceUrl" type="application/pdf">
                    <a :href="importSourceUrl" target="_blank" rel="noreferrer">打开源文件</a>
                  </object>
                  <div
                    v-if="selectedSourceOverlay"
                    class="source-bbox-overlay"
                    :style="selectedSourceOverlay"
                    aria-hidden="true"
                  />
                  <div v-if="selectedSourceLabel" class="source-bbox-label">{{ selectedSourceLabel }}</div>
                </div>
                <div v-else class="source-download">
                  <a :href="importSourceUrl" target="_blank" rel="noreferrer">打开源文件</a>
                </div>
              </section>

              <div class="review-workbench">
                <section class="review-issues" aria-label="导入问题">
                  <article
                    v-for="issue in importIssues"
                    :key="`${issue.issueCode}-${issue.sectionKey}-${issue.blockKey}`"
                    :class="{ active: sourcePageForIssue(issue) === selectedSourcePage }"
                    @click="focusIssueSource(issue)"
                  >
                    <strong>{{ issue.issueCode }}</strong>
                    <span>{{ issue.message }}</span>
                    <small>
                      <template v-if="issue.sectionKey">{{ issue.sectionKey }}</template>
                      <template v-if="issue.blockKey"> · {{ issue.blockKey }}</template>
                      <template v-if="issue.cellRef"> · {{ issue.cellRef }}</template>
                    </small>
                  </article>
                </section>

                <section class="review-editor" aria-label="章节复核">
                  <h3>Sections</h3>
                  <article
                    v-for="section in stagedSections"
                    :key="section.sectionKey"
                    class="review-row"
                    :class="{ active: section.sourcePageStart === selectedSourcePage }"
                    @focusin="focusSectionSource(section)"
                  >
                    <label>
                      key
                      <input :value="section.sectionKey" type="text" disabled />
                    </label>
                    <label>
                      title
                      <input v-model="section.title" type="text" />
                    </label>
                    <label>
                      parent
                      <input v-model="section.parentSectionKey" type="text" />
                    </label>
                    <label>
                      level
                      <input v-model.number="section.level" type="number" min="1" max="32" />
                    </label>
                    <label>
                      role
                      <input v-model="section.semanticRole" type="text" />
                    </label>
                    <button type="button" @click="saveStagedSection(section)">保存</button>
                  </article>
                </section>

                <section class="review-editor" aria-label="内容复核">
                  <h3>Blocks</h3>
                  <article
                    v-for="block in stagedBlocks"
                    :key="block.blockKey"
                    class="review-row block-row"
                    :class="{ active: block.sourcePage === selectedSourcePage }"
                    @focusin="focusBlockSource(block)"
                  >
                    <label>
                      key
                      <input :value="block.blockKey" type="text" disabled />
                    </label>
                    <label>
                      section
                      <input v-model="block.sectionKey" type="text" />
                    </label>
                    <label>
                      seq
                      <input v-model.number="block.seq" type="number" min="1" />
                    </label>
                    <label>
                      type
                      <input v-model="block.blockType" type="text" />
                    </label>
                    <label>
                      source
                      <button class="source-jump" type="button" @click="focusBlockSource(block)">
                        <span v-if="block.sourcePage">p{{ block.sourcePage }}</span>
                        <span v-else>n/a</span>
                        <small v-if="bboxLabel(block)">{{ bboxLabel(block) }}</small>
                      </button>
                    </label>
                    <label class="wide-field">
                      text
                      <textarea v-model="block.plainText" rows="3" />
                    </label>
                    <button type="button" @click="saveStagedBlock(block)">保存</button>
                  </article>
                </section>
              </div>
            </div>
          </section>
          <div v-else-if="!selectedDocument" class="empty-state">
            <h2>导入或选择一份文档</h2>
            <p>支持 docs 中定义的 PDF、JSON Package、Excel Package 与 Markdown。导入后会自动提交草稿并发布为当前阅读版本。</p>
          </div>
          <article v-else-if="content" class="reader-document">
            <h2>{{ content.node.title }}</h2>
            <ContentBlockView v-for="block in readerBlocks" :key="block.id" :block="block" />
            <section v-if="isQuestionMode && answerNodes.length > 0" class="answer-fold">
              <button class="answer-toggle" type="button" @click="toggleAnswer">
                {{ answerExpanded ? "收起答案" : "展开答案" }}
              </button>
              <div v-if="answerExpanded" class="answer-content">
                <section v-for="answerNode in answerNodes" :key="answerNode.id" class="answer-section">
                  <h3>{{ answerNode.title }}</h3>
                  <ContentBlockView
                    v-for="block in normalizeReaderBlocks(answerContents[answerNode.id]?.blocks ?? [])"
                    :key="block.id"
                    :block="block"
                  />
                </section>
              </div>
            </section>
          </article>
        </main>

        <aside class="settings-panel">
          <label>
            字号
            <input v-model.number="fontScale" type="range" min="15" max="22" step="1" />
          </label>
          <label>
            行高
            <input v-model.number="lineHeight" type="range" min="1.55" max="2" step="0.05" />
          </label>
          <div class="progress-box">
            <span>当前进度</span>
            <strong>{{ activeNode ? Math.round(progressRatioForNode(toc, activeNode.id) * 100) : 0 }}%</strong>
          </div>
          <section class="assist-section">
            <h2>导出</h2>
            <div class="export-actions" role="group" aria-label="导出当前文档">
              <button
                v-for="item in exportFormats"
                :key="item.format"
                type="button"
                :disabled="!selectedDocument?.currentVersionId || exportingFormat !== null"
                @click="exportCurrentDocument(item.format)"
              >
                {{ exportingFormat === item.format ? "..." : item.label }}
              </button>
            </div>
          </section>
          <section class="assist-section">
            <h2>复习</h2>
            <div class="review-actions">
              <button type="button" :disabled="!selectedDocument" @click="loadReviewQueue(false)">
                随机 5 题
              </button>
              <button type="button" :disabled="!selectedDocument" @click="loadReviewQueue(true)">
                待复习
              </button>
            </div>
            <div v-if="reviewQueue.length > 0" class="review-queue" aria-label="复习题目">
              <button
                v-for="item in reviewQueue"
                :key="item.nodeId"
                class="review-item"
                type="button"
                :class="{ active: item.nodeId === activeNode?.id }"
                @click="openReviewItem(item)"
              >
                <span>{{ item.title }}</span>
                <small>
                  {{ masteryLabel(item.mastery) }}
                  <template v-if="item.sourcePageStart"> · p{{ item.sourcePageStart }}</template>
                </small>
              </button>
            </div>
            <button class="assist-button" type="button" :disabled="!currentBlock" @click="bookmarkCurrentBlock">
              收藏当前块
            </button>
            <div class="mastery-control" role="group" aria-label="掌握度">
              <button
                type="button"
                :class="{ active: selectedMastery === 'HARD' }"
                :disabled="!activeNode"
                @click="markMastery('HARD')"
              >
                不会
              </button>
              <button
                type="button"
                :class="{ active: selectedMastery === 'FUZZY' }"
                :disabled="!activeNode"
                @click="markMastery('FUZZY')"
              >
                模糊
              </button>
              <button
                type="button"
                :class="{ active: selectedMastery === 'KNOWN' }"
                :disabled="!activeNode"
                @click="markMastery('KNOWN')"
              >
                会
              </button>
            </div>
            <label>
              笔记
              <textarea v-model="noteBody" rows="5" placeholder="记录本节要点" />
            </label>
            <button class="assist-button" type="button" :disabled="!currentBlock || !noteBody.trim()" @click="saveCurrentNote">
              保存笔记
            </button>
            <button class="assist-button" type="button" @click="clearOfflineCache">
              清理离线内容
            </button>
            <p v-if="interactionMessage" class="assist-status">{{ interactionMessage }}</p>
          </section>
        </aside>
      </div>
    </section>

    <div class="mobile-toolbox" :class="{ open: mobileToolsOpen }">
      <div class="mobile-tool-actions" aria-label="移动端工具箱">
        <label class="mobile-tool-action import" title="导入">
          <input
            type="file"
            accept="application/json,.json,text/markdown,.md,.markdown,application/pdf,.pdf,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,.xlsx"
            @change="importSourceFile"
          />
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v12M7 8l5-5 5 5M5 21h14" /></svg>
        </label>
        <button class="mobile-tool-action search" type="button" aria-label="搜索" @click="toggleMobileSearch">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m21 21-4.3-4.3M10.5 18a7.5 7.5 0 1 1 0-15 7.5 7.5 0 0 1 0 15Z" /></svg>
        </button>
        <button class="mobile-tool-action previous" type="button" :disabled="!previousNode" aria-label="上一节" @click="selectNode(previousNode)">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 18-6-6 6-6" /></svg>
        </button>
        <button class="mobile-tool-action next" type="button" :disabled="!nextNode" aria-label="下一节" @click="selectNode(nextNode)">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 18 6-6-6-6" /></svg>
        </button>
        <button class="mobile-tool-action toc" type="button" aria-label="目录" @click="openMobileToc">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 6h16M4 12h16M4 18h16" /></svg>
        </button>
      </div>
      <div v-if="mobileSearchOpen" class="mobile-search-panel">
        <input
          v-model="query"
          class="search-input"
          type="search"
          placeholder="搜索文档或正文"
          @keyup.enter="searchLibrary"
        />
        <section v-if="searchHits.length > 0 || searchStatus" class="search-results" aria-label="移动端正文搜索结果">
          <header>
            <h2>正文命中</h2>
            <span>{{ searchStatus }}</span>
          </header>
          <button
            v-for="hit in searchHits"
            :key="`mobile-${hit.versionId}-${hit.blockId}`"
            class="search-hit"
            type="button"
            @click="openSearchHit(hit)"
          >
            <strong>{{ hit.title }}</strong>
            <span>{{ hit.snippet }}</span>
          </button>
        </section>
      </div>
      <div
        class="mobile-toolbox-progress"
        role="progressbar"
        aria-label="当前阅读进度"
        aria-valuemin="0"
        aria-valuemax="100"
        :aria-valuenow="currentProgressPercent"
      >
        <span :style="{ width: `${currentProgressPercent}%` }"></span>
      </div>
      <button
        class="mobile-tool-toggle"
        type="button"
        :aria-label="mobileToolsOpen ? '关闭工具箱' : '打开工具箱'"
        :aria-expanded="mobileToolsOpen"
        @click="toggleMobileTools"
      >
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v18M3 12h18M5.6 5.6l12.8 12.8M18.4 5.6 5.6 18.4" /></svg>
      </button>
    </div>
    </template>
  </div>
</template>
