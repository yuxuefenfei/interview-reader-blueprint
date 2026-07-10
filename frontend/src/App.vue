<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from "vue";
import {
  commitImportJob,
  createNote,
  getDocument,
  getImportIssues,
  getImportJob,
  getNodeContent,
  getNormalizedPackage,
  getReadingProgress,
  getReviewQueue,
  getToc,
  listDocuments,
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
  ImportIssue,
  ImportJob,
  Mastery,
  NodeContent,
  NormalizedPackage,
  ReviewQueueItem,
  SearchHit,
  SourceBbox,
  SourceType,
  StagedBlock,
  StagedSection,
  TocNode
} from "./types/api";
import { pageForBbox, sourceOverlayLabel, sourceOverlayStyle } from "./utils/sourceBbox";
import { firstReadableNode, flattenToc, isQuestionNode, progressRatioForNode, questionAnswerNodes } from "./utils/toc";

const documents = ref<DocumentSummary[]>([]);
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
const deviceId = localStorage.getItem("reader.deviceId") || crypto.randomUUID();

localStorage.setItem("reader.deviceId", deviceId);

const allNodes = computed(() => flattenToc(toc.value));
const activeIndex = computed(() => allNodes.value.findIndex((node) => node.id === activeNode.value?.id));
const previousNode = computed(() => (activeIndex.value > 0 ? allNodes.value[activeIndex.value - 1] : null));
const nextNode = computed(() =>
  activeIndex.value >= 0 && activeIndex.value < allNodes.value.length - 1 ? allNodes.value[activeIndex.value + 1] : null
);
const currentBlock = computed(() => content.value?.blocks[0] ?? null);
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
  void refreshDocuments();
  void flushQueuedProgress();
  window.addEventListener("online", flushQueuedProgress);
});

async function refreshDocuments(): Promise<void> {
  await run("正在加载文档", async () => {
    const response = await listDocuments(query.value);
    documents.value = response.items;
    if (!selectedDocument.value && response.items.length > 0) {
      await selectDocument(response.items[0]);
    }
  });
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
    const resumeNode =
      progress?.sectionId ? allNodes.value.find((node) => node.id === progress.sectionId) ?? null : null;
    await selectNode(resumeNode ?? firstReadableNode(toc.value));
  });
}

function clearImportReview(): void {
  importReviewJob.value = null;
  importIssues.value = [];
  normalizedPackage.value = null;
  selectedSourcePage.value = null;
  selectedSourceBbox.value = null;
}

async function selectNode(node: TocNode | null): Promise<void> {
  if (!node || !selectedDocument.value?.currentVersionId) {
    return;
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
    await nextTick();
    document.querySelector("[data-reader-main]")?.scrollTo({ top: 0, behavior: "smooth" });
    await saveProgress(content.value.blocks[0] ?? null);
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

async function saveProgress(block: ContentBlock | null): Promise<void> {
  if (!selectedDocument.value?.currentVersionId || !activeNode.value) {
    return;
  }
  const progress = {
    versionId: selectedDocument.value.currentVersionId,
    sectionId: activeNode.value.id,
    blockId: block?.id ?? null,
    charOffset: 0,
    blockViewportOffset: 0,
    progressRatio: progressRatioForNode(toc.value, activeNode.value.id),
    clientUpdatedAt: new Date().toISOString(),
    deviceId,
    revision: 0
  };
  await saveReadingProgress(selectedDocument.value.id, progress)
    .then(() => flushQueuedProgress())
    .catch(() => enqueueReadingProgress(selectedDocument.value!.id, progress));
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

function progressPercent(value: number | null | undefined): number {
  const ratio = Number(value ?? 0);
  return Number.isFinite(ratio) ? Math.round(Math.min(1, Math.max(0, ratio)) * 100) : 0;
}

function documentProgressStyle(document: DocumentSummary): Record<string, string> {
  return {
    "--doc-progress": `${progressPercent(document.progressRatio)}%`
  };
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
  <div class="app-shell" :class="`theme-${theme}`" :style="readerStyle">
    <aside class="library-panel">
      <div class="library-header">
        <h1>Interview Reader</h1>
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
          :style="documentProgressStyle(document)"
          type="button"
          @click="selectDocument(document)"
        >
          <strong>{{ document.title }}</strong>
          <span>{{ progressPercent(document.progressRatio) }}%</span>
        </button>
      </nav>
    </aside>

    <section class="reader-area">
      <header class="reader-toolbar">
        <IconButton label="目录" @click="tocOpen = !tocOpen">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 6h16M4 12h16M4 18h16" /></svg>
        </IconButton>
        <div class="reader-title">
          <strong>{{ importReviewJob ? "导入复核" : selectedDocument?.title || "尚未选择文档" }}</strong>
          <span v-if="activeNode">{{ activeNode.title }}</span>
          <span v-else-if="importReviewJob">{{ importReviewJob.status }}</span>
        </div>
        <div class="toolbar-actions">
          <IconButton label="上一节" :disabled="!previousNode" @click="selectNode(previousNode)">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 18-6-6 6-6" /></svg>
          </IconButton>
          <IconButton label="下一节" :disabled="!nextNode" @click="selectNode(nextNode)">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 18 6-6-6-6" /></svg>
          </IconButton>
          <IconButton label="主题" @click="theme = theme === 'light' ? 'sepia' : theme === 'sepia' ? 'dark' : 'light'">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3a9 9 0 1 0 9 9 7 7 0 0 1-9-9Z" /></svg>
          </IconButton>
        </div>
      </header>

      <div class="reader-body">
        <aside class="toc-panel" :class="{ open: tocOpen }">
          <TocTree :nodes="toc" :active-node-id="activeNode?.id ?? null" @select="selectNode" />
        </aside>

        <main class="content-panel" data-reader-main>
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
            <ContentBlockView v-for="block in content.blocks" :key="block.id" :block="block" />
            <section v-if="isQuestionMode && answerNodes.length > 0" class="answer-fold">
              <button class="answer-toggle" type="button" @click="toggleAnswer">
                {{ answerExpanded ? "收起答案" : "展开答案" }}
              </button>
              <div v-if="answerExpanded" class="answer-content">
                <section v-for="answerNode in answerNodes" :key="answerNode.id" class="answer-section">
                  <h3>{{ answerNode.title }}</h3>
                  <ContentBlockView
                    v-for="block in answerContents[answerNode.id]?.blocks ?? []"
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
  </div>
</template>
