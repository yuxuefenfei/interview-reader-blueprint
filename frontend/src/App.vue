<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from "vue";
import {
  commitImportJob,
  createNote,
  getNodeContent,
  getReadingProgress,
  getToc,
  listDocuments,
  publishVersion,
  saveBookmark,
  saveReadingProgress,
  saveReviewState,
  uploadSourceFile
} from "./api/client";
import ContentBlockView from "./components/ContentBlockView.vue";
import IconButton from "./components/IconButton.vue";
import TocTree from "./components/TocTree.vue";
import { enqueueReadingProgress, flushReadingProgressQueue } from "./offline/progressQueue";
import type { ContentBlock, DocumentSummary, Mastery, NodeContent, SourceType, TocNode } from "./types/api";
import { firstReadableNode, flattenToc, progressRatioForNode } from "./utils/toc";

const documents = ref<DocumentSummary[]>([]);
const selectedDocument = ref<DocumentSummary | null>(null);
const toc = ref<TocNode[]>([]);
const activeNode = ref<TocNode | null>(null);
const content = ref<NodeContent | null>(null);
const loading = ref(false);
const busyMessage = ref("");
const error = ref("");
const query = ref("");
const theme = ref(localStorage.getItem("reader.theme") || "light");
const fontScale = ref(Number(localStorage.getItem("reader.fontScale") || "18"));
const lineHeight = ref(Number(localStorage.getItem("reader.lineHeight") || "1.82"));
const tocOpen = ref(false);
const noteBody = ref("");
const interactionMessage = ref("");
const selectedMastery = ref<Mastery>("UNKNOWN");
const deviceId = localStorage.getItem("reader.deviceId") || crypto.randomUUID();

localStorage.setItem("reader.deviceId", deviceId);

const allNodes = computed(() => flattenToc(toc.value));
const activeIndex = computed(() => allNodes.value.findIndex((node) => node.id === activeNode.value?.id));
const previousNode = computed(() => (activeIndex.value > 0 ? allNodes.value[activeIndex.value - 1] : null));
const nextNode = computed(() =>
  activeIndex.value >= 0 && activeIndex.value < allNodes.value.length - 1 ? allNodes.value[activeIndex.value + 1] : null
);
const currentBlock = computed(() => content.value?.blocks[0] ?? null);

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

async function selectDocument(document: DocumentSummary): Promise<void> {
  if (!document.currentVersionId) {
    selectedDocument.value = document;
    toc.value = [];
    content.value = null;
    activeNode.value = null;
    return;
  }
  const versionId = document.currentVersionId;
  await run("正在打开文档", async () => {
    selectedDocument.value = document;
    toc.value = await getToc(versionId);
    const progress = await getReadingProgress(document.id).catch(() => null);
    const resumeNode =
      progress?.sectionId ? allNodes.value.find((node) => node.id === progress.sectionId) ?? null : null;
    await selectNode(resumeNode ?? firstReadableNode(toc.value));
  });
}

async function selectNode(node: TocNode | null): Promise<void> {
  if (!node || !selectedDocument.value?.currentVersionId) {
    return;
  }
  await run("正在加载正文", async () => {
    activeNode.value = node;
    content.value = await getNodeContent(selectedDocument.value!.currentVersionId!, node.id);
    noteBody.value = "";
    interactionMessage.value = "";
    selectedMastery.value = "UNKNOWN";
    tocOpen.value = false;
    await nextTick();
    document.querySelector("[data-reader-main]")?.scrollTo({ top: 0, behavior: "smooth" });
    await saveProgress(content.value.blocks[0] ?? null);
  });
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
    const job = await uploadSourceFile(file, sourceType);
    if (job.status !== "READY" && job.status !== "IMPORTED") {
      throw new Error(`导入需要复核：${job.status}`);
    }
    const version = await commitImportJob(job.id);
    await publishVersion(version.documentId, version.id);
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
        placeholder="搜索文档"
        @keyup.enter="refreshDocuments"
      />

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
          <span>{{ Math.round(Number(document.progressRatio ?? 0) * 100) }}%</span>
        </button>
      </nav>
    </aside>

    <section class="reader-area">
      <header class="reader-toolbar">
        <IconButton label="目录" @click="tocOpen = !tocOpen">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 6h16M4 12h16M4 18h16" /></svg>
        </IconButton>
        <div class="reader-title">
          <strong>{{ selectedDocument?.title || "尚未选择文档" }}</strong>
          <span v-if="activeNode">{{ activeNode.title }}</span>
        </div>
        <IconButton label="上一节" :disabled="!previousNode" @click="selectNode(previousNode)">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 18-6-6 6-6" /></svg>
        </IconButton>
        <IconButton label="下一节" :disabled="!nextNode" @click="selectNode(nextNode)">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 18 6-6-6-6" /></svg>
        </IconButton>
        <IconButton label="主题" @click="theme = theme === 'light' ? 'sepia' : theme === 'sepia' ? 'dark' : 'light'">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3a9 9 0 1 0 9 9 7 7 0 0 1-9-9Z" /></svg>
        </IconButton>
      </header>

      <div class="reader-body">
        <aside class="toc-panel" :class="{ open: tocOpen }">
          <TocTree :nodes="toc" :active-node-id="activeNode?.id ?? null" @select="selectNode" />
        </aside>

        <main class="content-panel" data-reader-main>
          <div v-if="loading" class="state-line">{{ busyMessage }}</div>
          <div v-else-if="error" class="state-line error">{{ error }}</div>
          <div v-else-if="!selectedDocument" class="empty-state">
            <h2>导入或选择一份文档</h2>
            <p>支持 docs 中定义的 PDF、JSON Package、Excel Package 与 Markdown。导入后会自动提交草稿并发布为当前阅读版本。</p>
          </div>
          <article v-else-if="content" class="reader-document">
            <h2>{{ content.node.title }}</h2>
            <ContentBlockView v-for="block in content.blocks" :key="block.id" :block="block" />
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
            <p v-if="interactionMessage" class="assist-status">{{ interactionMessage }}</p>
          </section>
        </aside>
      </div>
    </section>
  </div>
</template>
