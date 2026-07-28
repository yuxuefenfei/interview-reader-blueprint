<script setup lang="ts">
import { toUserMessage } from "../utils/errorMessage";
import { ArrowDown, ArrowLeft, ArrowRight, Close, Expand, Fold, Moon, Reading, Search, Sunny, Tickets } from "@element-plus/icons-vue";
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus/es/components/message/index";
import { readerApi } from "../api/reader";
import ContentBlockView from "../components/ContentBlockView.vue";
import ReaderDocumentList from "../components/ReaderDocumentList.vue";
import ReaderDocumentSelector from "../components/ReaderDocumentSelector.vue";
import TocTree from "../components/TocTree.vue";
import { cacheNodeContent, getCachedNodeContent } from "../offline/contentCache";
import {
  enqueueReadingProgress,
  flushReadingProgressQueue,
  shouldDiscardReadingProgress,
  shouldQueueReadingProgress
} from "../offline/progressQueue";
import type { DocumentSummary, NodeContent, ReadingProgress, TocNode } from "../types/api";
import { getOrCreateReadingDeviceId } from "../utils/readingDevice";
import { clampProgressRatio, documentReadingPositionRatio } from "../utils/readingProgress";
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
const drawerView = ref<"toc" | "documents">("toc");
const desktopNavView = ref<"toc" | "documents">("toc");
const desktopNavCollapsed = ref(loadDesktopNavCollapsed());
const expandedTocNodeIds = ref<string[]>([]);
const failedNodeId = ref<string | null>(null);
const documentQuery = ref("");
const pendingDocumentId = ref<string | null>(null);
const documentSwitchError = ref("");
const searchOpen = ref(false);
const comfortOpen = ref(false);
const query = ref("");
const searchHits = ref<Awaited<ReturnType<typeof readerApi.search>>>([]);
const searchInput = ref<{ focus: () => void } | null>(null);
const readingArea = ref<HTMLElement | null>(null);
const desktopTocArea = ref<HTMLElement | null>(null);
const mobileTocArea = ref<HTMLElement | null>(null);
const desktopDocumentListArea = ref<HTMLElement | null>(null);
const mobileDocumentListArea = ref<HTMLElement | null>(null);
const chapterProgress = ref(0);
const theme = ref<ReaderTheme>(loadReaderTheme());
const comfort = reactive(loadReaderComfort());
const viewportHeight = ref(currentViewportHeight());
const chapterLoading = ref(false);
const pendingNodeId = ref<string | null>(null);
const loadingMore = ref(false);
const deviceId = getOrCreateReadingDeviceId();
let saveTimer: number | null = null;
let documentRequestId = 0;
let contentRequestId = 0;
let loadMoreRequestId = 0;
let contentAbortController: AbortController | null = null;
let loadMoreAbortController: AbortController | null = null;
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
const currentDocumentProgressRatio = computed(() => {
  if (activeIndex.value < 0 || readable.value.length === 0) {
    return clampProgressRatio(selected.value?.progressRatio);
  }
  return documentReadingPositionRatio(activeIndex.value, readable.value.length, chapterProgress.value);
});
const navigationDocument = computed<DocumentSummary | null>(() =>
  selected.value ? { ...selected.value, progressRatio: currentDocumentProgressRatio.value } : null);
const navigationDocuments = computed(() => documents.value.map((document) =>
  document.id === selected.value?.id
    ? { ...document, progressRatio: currentDocumentProgressRatio.value }
    : document));
const mobileProgressStyle = computed(() => ({ width: `${Math.round(chapterProgress.value * 100)}%` }));
const desktopProgressStyle = computed(() => ({ width: `${Math.round(chapterProgress.value * 100)}%` }));
const desktopRailProgressStyle = computed(() => ({ height: `${currentDocumentProgressRatio.value * 100}%` }));
const readerPageStyle = computed(() => ({
  ...comfortStyle(comfort),
  "--reader-viewport-height": `${viewportHeight.value}px`,
}));
const readerSurfaceStyle = computed(() => ({ backgroundColor: readerThemeColor(theme.value) }));
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
watch(drawer, async (open) => {
  if (!open) {
    drawerView.value = "toc";
    documentQuery.value = "";
    documentSwitchError.value = "";
    return;
  }
  drawerView.value = "toc";
  ensureActiveTocPathExpanded();
  await nextTick();
  scrollActiveTocIntoView(mobileTocArea.value);
});
watch(() => activeNode.value?.id, async (nodeId) => {
  if (!nodeId) return;
  ensureActiveTocPathExpanded();
  await nextTick();
  if (!desktopNavCollapsed.value && desktopNavView.value === "toc") {
    scrollActiveTocIntoView(desktopTocArea.value);
  }
  if (drawer.value && drawerView.value === "toc") scrollActiveTocIntoView(mobileTocArea.value);
});
onMounted(async () => {
  window.addEventListener("online", flushOfflineProgress);
  window.addEventListener("keydown", handleGlobalShortcut);
  window.addEventListener("resize", syncViewportHeight);
  window.visualViewport?.addEventListener("resize", syncViewportHeight);
  syncViewportHeight();
  updateThemeColor(theme.value);
  void flushOfflineProgress();
  await loadDocuments();
  await openFromRoute();
});
onBeforeUnmount(() => {
  if (saveTimer !== null) window.clearTimeout(saveTimer);
  contentAbortController?.abort();
  loadMoreAbortController?.abort();
  window.removeEventListener("online", flushOfflineProgress);
  window.removeEventListener("keydown", handleGlobalShortcut);
  window.removeEventListener("resize", syncViewportHeight);
  window.visualViewport?.removeEventListener("resize", syncViewportHeight);
  document.querySelector<HTMLMetaElement>('meta[name="theme-color"]')?.setAttribute("content", "#0f766e");
});

function currentViewportHeight(): number {
  return Math.max(1, Math.round(window.visualViewport?.height ?? window.innerHeight));
}

function syncViewportHeight(): void {
  viewportHeight.value = currentViewportHeight();
}

async function loadDocuments(): Promise<void> {
  try {
    documents.value = (await readerApi.documents()).items;
  } catch (caught) { error.value = message(caught); }
}

async function openFromRoute(forceRefresh = false): Promise<void> {
  const requestId = ++documentRequestId;
  const keepDrawerOpen = drawer.value;
  invalidateReadingContext();
  loading.value = true;
  error.value = "";
  let documentId = typeof route.params.documentId === "string" ? route.params.documentId : undefined;
  let latestReadDocument: DocumentSummary | null = null;
  if (!documentId) {
    try {
      latestReadDocument = await readerApi.latestReadDocument();
      if (requestId !== documentRequestId) return;
      documentId = latestReadDocument?.id;
    } catch {
      if (requestId !== documentRequestId) return;
      documentId = undefined;
    }
  }
  documentId ||= documents.value[0]?.id;
  if (!documentId) {
    if (requestId === documentRequestId) loading.value = false;
    return;
  }
  try {
    let document = forceRefresh
      ? null
      : documents.value.find((item) => item.id === documentId) || latestReadDocument;
    document ||= await readerApi.document(documentId);
    if (requestId !== documentRequestId) return;
    if (forceRefresh) {
      documents.value = documents.value.map((item) => item.id === document.id ? document : item);
    }
    selected.value = document;
    if (!document.currentVersionId) return;
    const versionId = document.currentVersionId;
    const [nextToc, saved] = await Promise.all([
      readerApi.toc(versionId),
      readerApi.progress(document.id),
    ]);
    if (requestId !== documentRequestId || !isCurrentDocumentVersion(document.id, versionId)) return;
    toc.value = nextToc;
    expandedTocNodeIds.value = loadExpandedTocNodeIds(document.id, nextToc);
    const initial = flattenToc(nextToc).find((node) => node.id === saved?.sectionId) || firstReadableNode(nextToc);
    if (initial) {
      ensureTocPathExpanded(initial.id, nextToc);
      const restoredChapterProgress = saved?.sectionId === initial.id ? saved.progressRatio : 0;
      await selectNode(initial, false, !keepDrawerOpen, restoredChapterProgress);
    }
  } catch (caught) {
    if (requestId === documentRequestId) error.value = message(caught);
  } finally {
    if (requestId === documentRequestId) loading.value = false;
  }
}

async function selectDocument(document: DocumentSummary, closeDrawer = true): Promise<void> {
  if (closeDrawer) drawer.value = false;
  if (route.params.documentId !== document.id) await router.push(`/reader/documents/${document.id}`);
}

async function selectDocumentFromNavigation(document: DocumentSummary, surface: "desktop" | "mobile"): Promise<void> {
  documentSwitchError.value = "";
  if (document.id === selected.value?.id) {
    await showNavigationToc(surface);
    return;
  }
  pendingDocumentId.value = document.id;
  try {
    await selectDocument(document, false);
    await showNavigationToc(surface);
  } catch (caught) {
    documentSwitchError.value = message(caught);
  } finally {
    pendingDocumentId.value = null;
  }
}

async function selectNode(
  node: TocNode,
  shouldScroll = true,
  closeDrawer = true,
  restoredChapterProgress = 0,
): Promise<void> {
  const documentId = selected.value?.id;
  const versionId = selected.value?.currentVersionId;
  if (!documentId || !versionId) return;
  if (chapterLoading.value && pendingNodeId.value === node.id) return;
  contentAbortController?.abort();
  cancelLoadMore();
  const abortController = new AbortController();
  contentAbortController = abortController;
  const requestId = ++contentRequestId;
  chapterLoading.value = true;
  pendingNodeId.value = node.id;
  failedNodeId.value = null;
  error.value = "";
  try {
    let nextContent: NodeContent;
    try {
      nextContent = await readerApi.content(versionId, node.id, undefined, abortController.signal);
      void cacheNodeContent(documentId, versionId, node.id, 100, nextContent).catch(() => undefined);
    } catch (caught) {
      if (abortController.signal.aborted) return;
      const cached = await getCachedNodeContent(versionId, node.id, 100);
      if (!cached) throw caught;
      nextContent = cached;
    }
    if (!isCurrentContentRequest(requestId, documentId, versionId)) return;
    activeNode.value = node;
    content.value = nextContent;
    ensureTocPathExpanded(node.id);
    if (closeDrawer) drawer.value = false;
    chapterProgress.value = clampProgressRatio(restoredChapterProgress);
    if (shouldScroll) {
      await nextTick();
      readingArea.value?.scrollTo({ top: 0, behavior: "auto" });
    } else if (chapterProgress.value > 0) {
      await nextTick();
      const area = readingArea.value;
      if (area) {
        const distance = Math.max(0, area.scrollHeight - area.clientHeight);
        area.scrollTo({ top: distance * chapterProgress.value, behavior: "auto" });
      }
    }
    if (!isCurrentContentRequest(requestId, documentId, versionId)) return;
    scheduleProgress();
  } catch (caught) {
    if (isCurrentContentRequest(requestId, documentId, versionId)) {
      failedNodeId.value = node.id;
      if (!content.value) error.value = message(caught);
    }
  } finally {
    if (requestId === contentRequestId) {
      chapterLoading.value = false;
      pendingNodeId.value = null;
      if (contentAbortController === abortController) contentAbortController = null;
    }
  }
}

async function loadMoreContent(): Promise<void> {
  const documentId = selected.value?.id;
  const versionId = selected.value?.currentVersionId;
  const node = activeNode.value;
  const current = content.value;
  if (!documentId || !versionId || !node || !current?.nextAfterSeq || loadingMore.value) return;
  loadMoreAbortController?.abort();
  const abortController = new AbortController();
  loadMoreAbortController = abortController;
  const requestId = ++loadMoreRequestId;
  loadingMore.value = true;
  try {
    const page = await readerApi.content(versionId, node.id, current.nextAfterSeq, abortController.signal);
    if (requestId !== loadMoreRequestId
        || !isCurrentDocumentVersion(documentId, versionId)
        || activeNode.value?.id !== node.id
        || content.value !== current) return;
    content.value = {
      node: current.node,
      blocks: [...current.blocks, ...page.blocks],
      nextAfterSeq: page.nextAfterSeq
    };
    void cacheNodeContent(documentId, versionId, node.id, 100, content.value).catch(() => undefined);
  } catch (caught) {
    if (!abortController.signal.aborted
        && requestId === loadMoreRequestId
        && isCurrentDocumentVersion(documentId, versionId)) {
      error.value = message(caught);
    }
  } finally {
    if (requestId === loadMoreRequestId) {
      loadingMore.value = false;
      if (loadMoreAbortController === abortController) loadMoreAbortController = null;
    }
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
  const document = selected.value;
  const node = activeNode.value;
  const currentContent = content.value;
  if (!document || !node || !document.currentVersionId || currentContent?.node.id !== node.id) return;
  if (saveTimer !== null) window.clearTimeout(saveTimer);
  const firstBlock = currentContent.blocks[0] ?? null;
  const progress: ReadingProgress = {
    versionId: document.currentVersionId,
    sectionId: node.id,
    blockId: firstBlock?.id ?? null,
    charOffset: 0,
    blockViewportOffset: 0,
    progressRatio: chapterProgress.value,
    clientUpdatedAt: new Date().toISOString(),
    deviceId,
    revision: 0
  };
  saveTimer = window.setTimeout(() => {
    saveTimer = null;
    void saveProgressOfflineAware(document.id, progress);
  }, 700);
}

async function saveProgressOfflineAware(documentId: string, progress: ReadingProgress): Promise<void> {
  try {
    await readerApi.saveProgress(documentId, progress);
  } catch (caught) {
    if (shouldQueueReadingProgress(caught)) {
      await enqueueReadingProgress(documentId, progress).catch(() => {
        if (isCurrentDocumentVersion(documentId, progress.versionId)) {
          error.value = "阅读进度暂时无法保存";
        }
      });
      return;
    }
    if (!isCurrentDocumentVersion(documentId, progress.versionId)) return;
    if (shouldDiscardReadingProgress(caught)) {
      void openFromRoute(true);
      return;
    }
    error.value = message(caught);
  }
}

function invalidateReadingContext(): void {
  contentAbortController?.abort();
  contentAbortController = null;
  chapterLoading.value = false;
  pendingNodeId.value = null;
  contentRequestId += 1;
  cancelLoadMore();
  if (saveTimer !== null) {
    window.clearTimeout(saveTimer);
    saveTimer = null;
  }
  toc.value = [];
  expandedTocNodeIds.value = [];
  failedNodeId.value = null;
  activeNode.value = null;
  content.value = null;
  chapterProgress.value = 0;
}

function cancelLoadMore(): void {
  loadMoreAbortController?.abort();
  loadMoreAbortController = null;
  loadMoreRequestId += 1;
  loadingMore.value = false;
}

function isCurrentDocumentVersion(documentId: string, versionId: string): boolean {
  return selected.value?.id === documentId && selected.value.currentVersionId === versionId;
}

function isCurrentContentRequest(requestId: number, documentId: string, versionId: string): boolean {
  return requestId === contentRequestId && isCurrentDocumentVersion(documentId, versionId);
}

function flushOfflineProgress(): void {
  void flushReadingProgressQueue(readerApi.saveProgress).catch(() => undefined);
}

function tocExpansionStorageKey(documentId: string): string {
  return `reader.toc.expanded.${documentId}`;
}

function loadExpandedTocNodeIds(documentId: string, nodes: TocNode[]): string[] {
  const expandableIds = new Set(flattenToc(nodes).filter((node) => node.children.length > 0).map((node) => node.id));
  try {
    const stored = JSON.parse(localStorage.getItem(tocExpansionStorageKey(documentId)) ?? "[]");
    return Array.isArray(stored)
      ? stored.filter((nodeId): nodeId is string => typeof nodeId === "string" && expandableIds.has(nodeId))
      : [];
  } catch {
    return [];
  }
}

function persistExpandedTocNodeIds(): void {
  const documentId = selected.value?.id;
  if (!documentId) return;
  try {
    localStorage.setItem(tocExpansionStorageKey(documentId), JSON.stringify(expandedTocNodeIds.value));
  } catch {
    // Reading still works when storage is unavailable (for example, private browsing quota limits).
  }
}

function findTocPath(nodes: TocNode[], nodeId: string, ancestors: TocNode[] = []): TocNode[] | null {
  for (const node of nodes) {
    const path = [...ancestors, node];
    if (node.id === nodeId) return path;
    const childPath = findTocPath(node.children, nodeId, path);
    if (childPath) return childPath;
  }
  return null;
}

function ensureTocPathExpanded(nodeId: string, nodes = toc.value): void {
  const path = findTocPath(nodes, nodeId);
  if (!path) return;
  const nextExpanded = new Set(expandedTocNodeIds.value);
  path.slice(0, -1).forEach((node) => {
    if (node.children.length > 0) nextExpanded.add(node.id);
  });
  if (nextExpanded.size === expandedTocNodeIds.value.length
      && expandedTocNodeIds.value.every((nodeId) => nextExpanded.has(nodeId))) return;
  expandedTocNodeIds.value = [...nextExpanded];
  persistExpandedTocNodeIds();
}

function ensureActiveTocPathExpanded(): void {
  if (activeNode.value) ensureTocPathExpanded(activeNode.value.id);
}

function toggleTocNode(nodeId: string): void {
  const nextExpanded = new Set(expandedTocNodeIds.value);
  if (nextExpanded.has(nodeId)) nextExpanded.delete(nodeId);
  else nextExpanded.add(nodeId);
  expandedTocNodeIds.value = [...nextExpanded];
  persistExpandedTocNodeIds();
}

function scrollActiveTocIntoView(container: HTMLElement | null): void {
  container?.querySelector<HTMLElement>(".toc-node[aria-current='location']")
    ?.scrollIntoView({ block: "center", inline: "nearest", behavior: "auto" });
}

function scrollCurrentDocumentIntoView(container: HTMLElement | null): void {
  container?.querySelector<HTMLElement>(".reader-document-option.active")
    ?.scrollIntoView({ block: "center", inline: "nearest", behavior: "auto" });
}

async function showMobileDocumentsView(): Promise<void> {
  drawerView.value = "documents";
  documentSwitchError.value = "";
  await nextTick();
  scrollCurrentDocumentIntoView(mobileDocumentListArea.value);
}

async function showMobileTocView(): Promise<void> {
  drawerView.value = "toc";
  documentQuery.value = "";
  ensureActiveTocPathExpanded();
  await nextTick();
  scrollActiveTocIntoView(mobileTocArea.value);
}

async function showDesktopDocumentsView(): Promise<void> {
  desktopNavView.value = "documents";
  documentSwitchError.value = "";
  await nextTick();
  scrollCurrentDocumentIntoView(desktopDocumentListArea.value);
}

async function showDesktopTocView(): Promise<void> {
  desktopNavView.value = "toc";
  documentQuery.value = "";
  ensureActiveTocPathExpanded();
  await nextTick();
  scrollActiveTocIntoView(desktopTocArea.value);
}

function showNavigationToc(surface: "desktop" | "mobile"): Promise<void> {
  return surface === "desktop" ? showDesktopTocView() : showMobileTocView();
}

function loadDesktopNavCollapsed(): boolean {
  try {
    const stored = localStorage.getItem("reader.desktopNav.collapsed");
    if (stored === "true" || stored === "false") return stored === "true";
  } catch {
    // Use the responsive default if storage is unavailable.
  }
  return window.innerWidth < 1100;
}

async function toggleDesktopNav(): Promise<void> {
  desktopNavCollapsed.value = !desktopNavCollapsed.value;
  try {
    localStorage.setItem("reader.desktopNav.collapsed", String(desktopNavCollapsed.value));
  } catch {
    // The current session can still use the selected layout.
  }
  if (!desktopNavCollapsed.value) {
    await nextTick();
    if (desktopNavView.value === "toc") scrollActiveTocIntoView(desktopTocArea.value);
    else scrollCurrentDocumentIntoView(desktopDocumentListArea.value);
  }
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
  <div
    class="reader-page"
    :class="[
      `theme-${theme}`,
      {
        'reader-overlay-open': drawer || searchOpen,
        'desktop-nav-collapsed': desktopNavCollapsed,
      },
    ]"
    :style="readerPageStyle"
  >
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

    <aside class="reader-desktop-nav" :class="{ collapsed: desktopNavCollapsed }">
      <template v-if="desktopNavCollapsed">
        <button class="reader-desktop-nav-expand" type="button" aria-label="展开文档目录" @click="toggleDesktopNav">
          <Expand aria-hidden="true" />
        </button>
        <div class="reader-desktop-rail-progress" role="progressbar" aria-label="文档阅读位置" aria-valuemin="0" aria-valuemax="100" :aria-valuenow="Math.round(currentDocumentProgressRatio * 100)">
          <span :style="desktopRailProgressStyle"></span>
        </div>
      </template>
      <template v-else-if="desktopNavView === 'toc'">
        <div class="reader-desktop-nav-header">
          <ReaderDocumentSelector :document="navigationDocument" @open="showDesktopDocumentsView" />
          <button class="reader-desktop-nav-collapse" type="button" aria-label="收起文档侧栏" title="收起文档侧栏" @click="toggleDesktopNav">
            <Fold aria-hidden="true" />
          </button>
        </div>
        <div ref="desktopTocArea" class="reader-desktop-toc-scroll" aria-label="当前文档章节目录">
          <div v-if="loading && toc.length === 0" class="reader-toc-state" role="status">正在加载目录…</div>
          <div v-else-if="toc.length === 0" class="reader-toc-state">当前文档暂无目录</div>
          <TocTree
            v-else
            tree-id="desktop"
            :nodes="toc"
            :active-node-id="activeNode?.id || null"
            :expanded-node-ids="expandedTocNodeIds"
            :pending-node-id="pendingNodeId"
            :failed-node-id="failedNodeId"
            @select="selectNode"
            @toggle="toggleTocNode"
          />
        </div>
      </template>
      <template v-else>
        <div class="reader-desktop-nav-header document-browser-header">
          <button class="reader-drawer-back" type="button" aria-label="返回当前文档目录" @click="showDesktopTocView">
            <ArrowLeft class="reader-drawer-back-icon" aria-hidden="true" />
            返回目录
          </button>
          <strong>切换文档</strong>
          <button class="reader-desktop-nav-collapse" type="button" aria-label="收起文档侧栏" title="收起文档侧栏" @click="toggleDesktopNav">
            <Fold aria-hidden="true" />
          </button>
        </div>
        <div ref="desktopDocumentListArea" class="reader-desktop-document-browser">
          <ReaderDocumentList
            v-model:query="documentQuery"
            :documents="navigationDocuments"
            :selected-document-id="selected?.id || null"
            :pending-document-id="pendingDocumentId"
            :error="documentSwitchError"
            @select="selectDocumentFromNavigation($event, 'desktop')"
          />
        </div>
      </template>
    </aside>

    <main
      ref="readingArea"
      class="reader-content"
      :class="{ 'chapter-transitioning': chapterLoading && !!content }"
      :style="readerSurfaceStyle"
      :aria-busy="chapterLoading"
      @scroll.passive="onReadingScroll"
    >
      <div v-if="chapterLoading && content" class="chapter-transition-status" role="status" aria-live="polite">正在加载章节…</div>
      <div v-if="loading" class="reader-state">正在加载章节…</div>
      <el-alert v-else-if="error" :title="error" type="error" show-icon :closable="false" />
      <template v-else-if="content">
        <article :key="content.node.id" class="reader-article" :data-node-id="content.node.id">
          <h1>{{ content.node.title }}</h1>
          <ContentBlockView v-for="block in content.blocks" :key="block.id" :block="block" :asset-base-url="selected ? `/assets/versions/${selected.currentVersionId}` : undefined" />
          <div v-if="content.nextAfterSeq" class="reader-load-more">
            <el-button :loading="loadingMore" @click="loadMoreContent">加载更多内容</el-button>
          </div>
        </article>
        <nav class="chapter-pagination" aria-label="章节翻页">
          <el-button class="chapter-nav-button chapter-nav-previous" :disabled="!previousNode || chapterLoading" :loading="pendingNodeId === previousNode?.id" :icon="ArrowLeft" @click="previousNode && selectNode(previousNode)">上一节</el-button>
          <span class="chapter-position" aria-live="polite">{{ chapterPosition }}</span>
          <el-button class="chapter-nav-button chapter-nav-next" type="primary" :disabled="!nextNode || chapterLoading" :loading="pendingNodeId === nextNode?.id" @click="nextNode && selectNode(nextNode)">下一节<el-icon><ArrowRight /></el-icon></el-button>
        </nav>
      </template>
      <div v-else class="reader-state">选择一篇文档开始阅读</div>
    </main>

    <!-- 移动端目录抽屉 -->
    <el-drawer
      v-model="drawer"
      class="reader-overlay-drawer reader-toc-drawer"
      direction="ltr"
      size="min(92vw, 400px)"
      :with-header="false"
    >
      <section class="reader-drawer" :data-view="drawerView">
        <template v-if="drawerView === 'toc'">
          <div class="reader-drawer-sticky">
            <header>
              <strong>文档目录</strong>
              <el-button circle :icon="Close" aria-label="关闭目录" @click="drawer = false" />
            </header>
            <ReaderDocumentSelector :document="navigationDocument" @open="showMobileDocumentsView" />
          </div>
          <div ref="mobileTocArea" class="reader-toc-scroll" aria-label="当前文档章节目录">
            <div v-if="loading && toc.length === 0" class="reader-toc-state" role="status">正在加载目录…</div>
            <div v-else-if="toc.length === 0" class="reader-toc-state">当前文档暂无目录</div>
            <TocTree
              v-else
              tree-id="mobile"
              :nodes="toc"
              :active-node-id="activeNode?.id || null"
              :expanded-node-ids="expandedTocNodeIds"
              :pending-node-id="pendingNodeId"
              :failed-node-id="failedNodeId"
              @select="selectNode"
              @toggle="toggleTocNode"
            />
          </div>
        </template>
        <template v-else>
          <div class="reader-drawer-sticky">
            <header>
              <button class="reader-drawer-back" type="button" aria-label="返回当前文档目录" @click="showMobileTocView">
                <ArrowLeft class="reader-drawer-back-icon" aria-hidden="true" />
                返回目录
              </button>
              <strong>切换文档</strong>
              <el-button circle :icon="Close" aria-label="关闭目录" @click="drawer = false" />
            </header>
          </div>
          <div ref="mobileDocumentListArea" class="reader-mobile-document-browser">
            <ReaderDocumentList
              v-model:query="documentQuery"
              :documents="navigationDocuments"
              :selected-document-id="selected?.id || null"
              :pending-document-id="pendingDocumentId"
              :error="documentSwitchError"
              @select="selectDocumentFromNavigation($event, 'mobile')"
            />
          </div>
        </template>
      </section>
    </el-drawer>

    <!-- 搜索面板 -->
    <el-drawer
      v-model="searchOpen"
      class="reader-overlay-drawer reader-search-drawer"
      direction="btt"
      size="min(68vh, 520px)"
      :with-header="false"
    >
      <section class="reader-search-sheet">
        <header>
          <strong>搜索当前文档</strong>
          <el-button circle :icon="Close" aria-label="关闭搜索" @click="searchOpen = false" />
        </header>
        <el-input ref="searchInput" v-model="query" name="reader-search" aria-label="搜索标题或正文" autocomplete="off" placeholder="搜索标题或正文…" clearable @keyup.enter="search">
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
