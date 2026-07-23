<script setup lang="ts">
import { toUserMessage } from "../utils/errorMessage";
import { ArrowDown, ArrowLeft, ArrowRight, Close, Moon, Reading, Search, Sunny, Tickets } from "@element-plus/icons-vue";
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus/es/components/message/index";
import { readerApi } from "../api/reader";
import ContentBlockView from "../components/ContentBlockView.vue";
import TocTree from "../components/TocTree.vue";
import { cacheNodeContent, getCachedNodeContent } from "../offline/contentCache";
import { enqueueReadingProgress, flushReadingProgressQueue, shouldQueueReadingProgress } from "../offline/progressQueue";
import type { DocumentSummary, NodeContent, ReadingProgress, TocNode } from "../types/api";
import { getOrCreateReadingDeviceId } from "../utils/readingDevice";
import {
  COLUMN_WIDTH_OPTIONS,
  comfortStyle,
  FONT_SIZE_OPTIONS,
  LINE_HEIGHT_OPTIONS,
  loadReaderComfort,
  loadReaderTheme,
  persistReaderComfort,
  readerThemeColor,
  type ReaderTheme
} from "../utils/readingComfort";
import { firstReadableNode, flattenToc, isQuestionNode } from "../utils/toc";

defineProps<{ username?: string | null }>();
const emit = defineEmits<{ logout: [] }>();
const route = useRoute();
const router = useRouter();
const documents = ref<DocumentSummary[]>([]);
const selected = ref<DocumentSummary | null>(null);
const toc = ref<TocNode[]>([]);
const activeNode = ref<TocNode | null>(null);
const content = ref<NodeContent | null>(null);
const loading = ref(false);
const error = ref("");
const drawer = ref(false);
const searchOpen = ref(false);
const comfortOpen = ref(false);
const query = ref("");
const searchHits = ref<Awaited<ReturnType<typeof readerApi.search>>>([]);
const searchInput = ref<{ focus: () => void } | null>(null);
const readingArea = ref<HTMLElement | null>(null);
const chapterProgress = ref(0);
const theme = ref<ReaderTheme>(loadReaderTheme());
const comfort = reactive(loadReaderComfort());
const loadingMore = ref(false);
const deviceId = getOrCreateReadingDeviceId();
let saveTimer: number | null = null;
let contentRequestId = 0;
const completedNodes = new Set<string>();

const themeOptions = [
  { value: "light" as const, label: "浅色", icon: Sunny },
  { value: "sepia" as const, label: "护眼", icon: Reading },
  { value: "dark" as const, label: "深色", icon: Moon },
];

const readable = computed(() => flattenToc(toc.value).filter((node) => isQuestionNode(node) || node.children.length === 0));
const activeIndex = computed(() => readable.value.findIndex((node) => node.id === activeNode.value?.id));
const previousNode = computed(() => activeIndex.value > 0 ? readable.value[activeIndex.value - 1] : null);
const nextNode = computed(() => activeIndex.value >= 0 && activeIndex.value < readable.value.length - 1 ? readable.value[activeIndex.value + 1] : null);
const mobileProgressStyle = computed(() => ({ width: `${Math.round(chapterProgress.value * 100)}%` }));
const desktopProgressStyle = computed(() => ({ width: `${Math.round(chapterProgress.value * 100)}%` }));
const readerComfortStyle = computed(() => comfortStyle(comfort));
const chapterPosition = computed(() => activeIndex.value >= 0 ? `${activeIndex.value + 1} / ${readable.value.length}` : `0 / ${readable.value.length}`);
const progressPercent = computed(() => Math.round(chapterProgress.value * 100));
const currentTheme = computed(() => themeOptions.find((option) => option.value === theme.value) ?? themeOptions[0]);
const searchShortcut = navigator.platform.toLowerCase().includes("mac") ? "⌘ K" : "Ctrl K";

watch(theme, (value) => {
  localStorage.setItem("reader.theme", value);
  updateThemeColor(value);
});
watch(comfort, (value) => persistReaderComfort(value), { deep: true });
watch(() => route.params.documentId, () => { void openFromRoute(); });
onMounted(async () => {
  window.addEventListener("online", flushOfflineProgress);
  window.addEventListener("keydown", handleGlobalShortcut);
  updateThemeColor(theme.value);
  void flushOfflineProgress();
  await loadDocuments();
  await openFromRoute();
});
onBeforeUnmount(() => {
  if (saveTimer !== null) window.clearTimeout(saveTimer);
  window.removeEventListener("online", flushOfflineProgress);
  window.removeEventListener("keydown", handleGlobalShortcut);
  document.querySelector<HTMLMetaElement>('meta[name="theme-color"]')?.setAttribute("content", "#0f766e");
});

async function loadDocuments(): Promise<void> {
  try {
    documents.value = (await readerApi.documents()).items;
  } catch (caught) { error.value = message(caught); }
}

async function openFromRoute(): Promise<void> {
  const documentId = typeof route.params.documentId === "string" ? route.params.documentId : documents.value[0]?.id;
  if (!documentId) return;
  const document = documents.value.find((item) => item.id === documentId) || await readerApi.document(documentId);
  if (!document.currentVersionId) return;
  selected.value = document;
  loading.value = true;
  error.value = "";
  try {
    toc.value = await readerApi.toc(document.currentVersionId);
    const saved = await readerApi.progress(document.id);
    const initial = flattenToc(toc.value).find((node) => node.id === saved?.sectionId) || firstReadableNode(toc.value);
    if (initial) await selectNode(initial, false);
  } catch (caught) { error.value = message(caught); }
  finally { loading.value = false; }
}

async function selectDocument(document: DocumentSummary): Promise<void> {
  drawer.value = false;
  if (route.params.documentId !== document.id) await router.push(`/reader/documents/${document.id}`);
}

async function selectNode(node: TocNode, shouldScroll = true): Promise<void> {
  const versionId = selected.value?.currentVersionId;
  if (!versionId) return;
  const requestId = ++contentRequestId;
  activeNode.value = node;
  error.value = "";
  try {
    let nextContent: NodeContent;
    try {
      nextContent = await readerApi.content(versionId, node.id);
      void cacheNodeContent(selected.value!.id, versionId, node.id, 100, nextContent).catch(() => undefined);
    } catch (caught) {
      const cached = await getCachedNodeContent(versionId, node.id, 100);
      if (!cached) throw caught;
      nextContent = cached;
    }
    if (requestId !== contentRequestId) return;
    content.value = nextContent;
    drawer.value = false;
    if (shouldScroll) await nextTick(() => readingArea.value?.scrollTo({ top: 0, behavior: "smooth" }));
    chapterProgress.value = 0;
    scheduleProgress();
  } catch (caught) {
    if (requestId === contentRequestId) {
      content.value = null;
      error.value = message(caught);
    }
  }
}

async function loadMoreContent(): Promise<void> {
  const versionId = selected.value?.currentVersionId;
  const node = activeNode.value;
  const current = content.value;
  if (!versionId || !node || !current?.nextAfterSeq || loadingMore.value) return;
  loadingMore.value = true;
  try {
    const page = await readerApi.content(versionId, node.id, current.nextAfterSeq);
    if (activeNode.value?.id !== node.id || content.value !== current) return;
    content.value = {
      node: current.node,
      blocks: [...current.blocks, ...page.blocks],
      nextAfterSeq: page.nextAfterSeq
    };
    void cacheNodeContent(selected.value!.id, versionId, node.id, 100, content.value).catch(() => undefined);
  } catch (caught) {
    error.value = message(caught);
  } finally {
    loadingMore.value = false;
  }
}

function onReadingScroll(): void {
  const area = readingArea.value;
  if (!area) return;
  const distance = Math.max(1, area.scrollHeight - area.clientHeight);
  chapterProgress.value = Math.min(1, Math.max(0, area.scrollTop / distance));
  const nodeId = activeNode.value?.id;
  if (nodeId && chapterProgress.value >= .995 && !completedNodes.has(nodeId)) {
    completedNodes.add(nodeId);
    ElMessage.success({ message: "本节已读完", duration: 1600, showClose: false });
  }
  scheduleProgress();
}

function scheduleProgress(): void {
  if (!selected.value || !activeNode.value || !selected.value.currentVersionId) return;
  if (saveTimer !== null) window.clearTimeout(saveTimer);
  saveTimer = window.setTimeout(() => {
    if (!selected.value || !activeNode.value || !selected.value.currentVersionId) return;
    const firstBlock = content.value?.blocks[0] ?? null;
    const progress: ReadingProgress = {
      versionId: selected.value.currentVersionId,
      sectionId: activeNode.value.id,
      blockId: firstBlock?.id ?? null,
      charOffset: 0,
      blockViewportOffset: 0,
      progressRatio: chapterProgress.value,
      clientUpdatedAt: new Date().toISOString(),
      deviceId,
      revision: 0
    };
    void saveProgressOfflineAware(selected.value.id, progress);
  }, 700);
}

async function saveProgressOfflineAware(documentId: string, progress: ReadingProgress): Promise<void> {
  try {
    await readerApi.saveProgress(documentId, progress);
  } catch (caught) {
    if (shouldQueueReadingProgress(caught)) {
      await enqueueReadingProgress(documentId, progress).catch(() => {
        error.value = "阅读进度暂时无法保存";
      });
      return;
    }
    error.value = message(caught);
  }
}

function flushOfflineProgress(): void {
  void flushReadingProgressQueue(readerApi.saveProgress).catch(() => undefined);
}

async function search(): Promise<void> {
  if (!query.value.trim()) { searchHits.value = []; return; }
  try { searchHits.value = await readerApi.search(query.value.trim(), selected.value?.id); }
  catch (caught) { error.value = message(caught); }
}

async function jump(hit: { documentId: string; nodeId: string }): Promise<void> {
  if (hit.documentId !== selected.value?.id) await router.push(`/reader/documents/${hit.documentId}`);
  const node = flattenToc(toc.value).find((item) => item.id === hit.nodeId);
  if (node) await selectNode(node);
  searchOpen.value = false;
}

function openSearch(): void {
  searchOpen.value = true;
  void nextTick(() => searchInput.value?.focus());
}

function handleGlobalShortcut(event: KeyboardEvent): void {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") {
    event.preventDefault();
    openSearch();
  }
}

function updateThemeColor(value: ReaderTheme): void {
  document.querySelector<HTMLMetaElement>('meta[name="theme-color"]')?.setAttribute("content", readerThemeColor(value));
}

function chooseTheme(command: string | number | object): void {
  if (command === "light" || command === "sepia" || command === "dark") setTheme(command);
}

function setTheme(value: ReaderTheme): void { theme.value = value; }
function resetComfort(): void {
  comfort.fontSize = 18;
  comfort.lineHeight = 1.85;
  comfort.columnWidth = 740;
}
function message(value: unknown): string { return toUserMessage(value, "加载失败"); }
</script>

<template>
  <div class="reader-page" :class="`theme-${theme}`" :style="readerComfortStyle">
    <header class="reader-header">
      <button class="reader-menu-button" type="button" aria-label="打开目录" :aria-expanded="drawer" @click="drawer = true">
        <el-icon><Tickets /></el-icon>
      </button>
      <div class="reader-heading">
        <strong :title="activeNode?.title || selected?.title || '阅读器'">{{ activeNode?.title || selected?.title || "阅读器" }}</strong>
        <span :title="selected?.title">{{ selected?.title }}</span>
      </div>
      <div class="reader-header-actions">
        <button
          class="reader-header-search-trigger"
          type="button"
          aria-label="搜索文档内容"
          title="搜索文档内容"
          @click="openSearch"
        >
          <span class="reader-search-placeholder">搜索文档内容</span>
          <kbd>{{ searchShortcut }}</kbd>
          <el-icon><Search /></el-icon>
        </button>
        <el-popover v-model:visible="comfortOpen" placement="bottom-end" :width="340" trigger="click" popper-class="reader-comfort-popper">
          <template #reference>
            <button
              class="reader-comfort-button"
              type="button"
              aria-label="阅读设置"
              title="阅读设置"
              :aria-expanded="comfortOpen"
            >
              <el-icon><Reading /></el-icon>
              <span>阅读设置</span>
            </button>
          </template>
          <section class="reader-comfort-panel" aria-label="阅读舒适度设置">
            <header>
              <div><strong>阅读舒适度</strong><span>设置会自动保存在当前设备</span></div>
              <button type="button" @click="resetComfort">恢复默认</button>
            </header>
            <fieldset>
              <legend>阅读主题</legend>
              <div class="comfort-option-grid theme-options">
                <button type="button" :class="{ active: theme === 'light' }" :aria-pressed="theme === 'light'" @click="setTheme('light')">浅色</button>
                <button type="button" :class="{ active: theme === 'sepia' }" :aria-pressed="theme === 'sepia'" @click="setTheme('sepia')">护眼</button>
                <button type="button" :class="{ active: theme === 'dark' }" :aria-pressed="theme === 'dark'" @click="setTheme('dark')">深色</button>
              </div>
            </fieldset>
            <fieldset>
              <legend>正文字号 <output>{{ comfort.fontSize }}px</output></legend>
              <div class="comfort-option-grid font-options">
                <button v-for="value in FONT_SIZE_OPTIONS" :key="value" type="button" :class="{ active: comfort.fontSize === value }" :aria-pressed="comfort.fontSize === value" @click="comfort.fontSize = value">{{ value }}</button>
              </div>
            </fieldset>
            <fieldset>
              <legend>行距</legend>
              <div class="comfort-option-grid">
                <button v-for="option in LINE_HEIGHT_OPTIONS" :key="option.value" type="button" :class="{ active: comfort.lineHeight === option.value }" :aria-pressed="comfort.lineHeight === option.value" @click="comfort.lineHeight = option.value">{{ option.label.replace(/\s[\d.]+$/, '') }}</button>
              </div>
            </fieldset>
            <fieldset>
              <legend>正文栏宽</legend>
              <div class="comfort-option-grid">
                <button v-for="option in COLUMN_WIDTH_OPTIONS" :key="option.value" type="button" :class="{ active: comfort.columnWidth === option.value }" :aria-pressed="comfort.columnWidth === option.value" @click="comfort.columnWidth = option.value">{{ option.label.replace(/\s\d+$/, '') }}</button>
              </div>
            </fieldset>
          </section>
        </el-popover>
        <el-dropdown trigger="click" placement="bottom-end" @command="chooseTheme">
          <button class="reader-theme-trigger" type="button" aria-label="切换阅读主题" :title="`当前主题：${currentTheme.label}`">
            <el-icon><component :is="currentTheme.icon" /></el-icon>
            <span>{{ currentTheme.label }}</span>
            <el-icon class="reader-theme-chevron"><ArrowDown /></el-icon>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-for="option in themeOptions" :key="option.value" :command="option.value" :class="{ 'is-active': theme === option.value }">
                <el-icon><component :is="option.icon" /></el-icon>{{ option.label }}
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-button class="reader-admin-link" text @click="router.push('/admin')">管理后台</el-button>
        <el-button text @click="emit('logout')">退出</el-button>
      </div>
      <!-- 桌面阅读进度条 -->
      <div class="reader-header-progress" aria-hidden="true">
        <span :style="desktopProgressStyle"></span>
      </div>
      <!-- 移动端章节进度条 -->
      <div class="mobile-chapter-progress" aria-label="当前章节阅读进度">
        <span :style="mobileProgressStyle"></span>
      </div>
      <output class="mobile-progress-label" aria-live="polite">{{ progressPercent }}%</output>
    </header>

    <aside class="reader-desktop-nav">
      <div class="reader-documents">
        <button
          v-for="document in documents"
          :key="document.id"
          :class="{ active: document.id === selected?.id }"
          type="button"
          @click="selectDocument(document)"
        >{{ document.title }}</button>
      </div>
      <TocTree :nodes="toc" :active-node-id="activeNode?.id || null" @select="selectNode" />
    </aside>

    <main ref="readingArea" class="reader-content" @scroll.passive="onReadingScroll">
      <div v-if="loading" class="reader-state">正在加载章节</div>
      <el-alert v-else-if="error" :title="error" type="error" show-icon :closable="false" />
      <template v-else-if="content">
        <article class="reader-article">
          <h1>{{ content.node.title }}</h1>
          <ContentBlockView v-for="block in content.blocks" :key="block.id" :block="block" />
          <div v-if="content.nextAfterSeq" class="reader-load-more">
            <el-button :loading="loadingMore" @click="loadMoreContent">加载更多内容</el-button>
          </div>
        </article>
        <nav class="chapter-pagination" aria-label="章节翻页">
          <el-button :disabled="!previousNode" :icon="ArrowLeft" @click="previousNode && selectNode(previousNode)">上一节</el-button>
          <span class="chapter-position" aria-live="polite">{{ chapterPosition }}</span>
          <el-button type="primary" :disabled="!nextNode" @click="nextNode && selectNode(nextNode)">下一节<el-icon><ArrowRight /></el-icon></el-button>
        </nav>
      </template>
      <div v-else class="reader-state">选择一篇文档开始阅读</div>
    </main>

    <!-- 移动端目录抽屉 -->
    <el-drawer v-model="drawer" direction="ltr" size="min(88vw, 360px)" :with-header="false">
      <section class="reader-drawer">
        <header>
          <strong>文档目录</strong>
          <el-button circle :icon="Close" aria-label="关闭目录" @click="drawer = false" />
        </header>
        <div class="reader-drawer-documents">
          <button v-for="document in documents" :key="document.id" :class="{ active: document.id === selected?.id }" type="button" @click="selectDocument(document)">{{ document.title }}</button>
        </div>
        <TocTree :nodes="toc" :active-node-id="activeNode?.id || null" @select="selectNode" />
      </section>
    </el-drawer>

    <!-- 搜索面板 -->
    <el-drawer v-model="searchOpen" direction="btt" size="min(68vh, 520px)" :with-header="false">
      <section class="reader-search-sheet">
        <header>
          <strong>搜索当前文档</strong>
          <el-button circle :icon="Close" aria-label="关闭搜索" @click="searchOpen = false" />
        </header>
        <el-input ref="searchInput" v-model="query" aria-label="搜索标题或正文" placeholder="搜索标题或正文" clearable @keyup.enter="search">
          <template #append><el-button :icon="Search" aria-label="搜索" @click="search" /></template>
        </el-input>
        <button v-for="hit in searchHits" :key="hit.blockId" class="reader-search-hit" type="button" @click="jump(hit)">
          <strong>{{ hit.title }}</strong>
          <span>{{ hit.snippet }}</span>
        </button>
      </section>
    </el-drawer>
  </div>
</template>
