<script setup lang="ts">
import { toUserMessage } from "../utils/errorMessage";
import { ArrowDown, ArrowLeft, ArrowLeftBold, ArrowRight, Close, Expand, Fold, Reading, Search, Tickets, User } from "@element-plus/icons-vue";
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus/es/components/message/index";
import { readerApi } from "../api/reader";
import ContentBlockView from "../components/ContentBlockView.vue";
import InlineMarkdown from "../components/InlineMarkdown.vue";
import ReaderComfortSettings from "../components/ReaderComfortSettings.vue";
import ReaderDocumentList from "../components/ReaderDocumentList.vue";
import ReaderDocumentSelector from "../components/ReaderDocumentSelector.vue";
import TocTree from "../components/TocTree.vue";
import { cacheNodeContent, getCachedNodeContent } from "../offline/contentCache";
import {
  cacheReaderDocuments,
  cacheReaderToc,
  getCachedReaderDocuments,
  getCachedReaderToc,
} from "../offline/bootstrapCache";
import {
  enqueueReadingProgress,
  flushReadingProgressQueue,
  shouldDiscardReadingProgress,
  shouldQueueReadingProgress
} from "../offline/progressQueue";
import type { DocumentSummary, NodeContent, ReadingProgress, SearchHit, TocNode } from "../types/api";
import { getOrCreateReadingDeviceId } from "../utils/readingDevice";
import { clampProgressRatio, documentReadingPositionRatio } from "../utils/readingProgress";
import {
  comfortStyle,
  loadReaderComfort,
  loadReaderTheme,
  persistReaderComfort,
  readerThemeColor,
  type ReaderTheme
} from "../utils/readingComfort";
import { firstReadableNode, flattenToc, isQuestionNode } from "../utils/toc";
import { blockAtViewportAnchor, scrollTopForBlockOffset } from "../utils/readingPosition";

const props = defineProps<{ username?: string | null; online?: boolean }>();
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
const mobileComfortOpen = ref(false);
const query = ref("");
const searchHits = ref<SearchHit[]>([]);
const searchScope = ref<"document" | "all">("document");
const searchHighlight = ref("");
const searchLoading = ref(false);
const searchError = ref("");
const searchCompletedTerm = ref("");
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
const contentLoadError = ref("");
const contentLoadSentinel = ref<HTMLElement | null>(null);
const documentNextCursor = ref<string | null>(null);
const documentListLoading = ref(false);
const documentListLoadError = ref("");
const deviceId = getOrCreateReadingDeviceId();
let saveTimer: number | null = null;
let readingScrollFrame: number | null = null;
let documentRequestId = 0;
let documentListRequestId = 0;
let contentRequestId = 0;
let loadMoreRequestId = 0;
let searchRequestId = 0;
let searchTimer: number | null = null;
let documentSearchTimer: number | null = null;
let contentAbortController: AbortController | null = null;
let documentListAbortController: AbortController | null = null;
let loadMoreAbortController: AbortController | null = null;
let searchAbortController: AbortController | null = null;
let ignoredRouteDocumentId: string | null = null;
let keepSearchHighlightAfterClose = false;
let contentLoadObserver: IntersectionObserver | null = null;
const completedNodes = new Set<string>();
const contentPrefetches = new Map<string, {
  controller: AbortController;
  promise: Promise<NodeContent>;
}>();

function isReadableNode(node: TocNode): boolean {
  return isQuestionNode(node) || node.children.length === 0;
}

function firstReadableDescendant(node: TocNode): TocNode | null {
  return flattenToc(node.children).find(isReadableNode) ?? null;
}

const readable = computed(() => flattenToc(toc.value).filter(isReadableNode));
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
const navigationDocuments = computed(() => {
  const listed = documents.value.map((document) =>
    document.id === selected.value?.id
    ? { ...document, progressRatio: currentDocumentProgressRatio.value }
    : document);
  if (!documentQuery.value.trim() && selected.value && !listed.some((document) => document.id === selected.value?.id)) {
    listed.unshift({ ...selected.value, progressRatio: currentDocumentProgressRatio.value });
  }
  return listed;
});
const mobileProgressStyle = computed(() => ({ width: `${Math.round(chapterProgress.value * 100)}%` }));
const desktopProgressStyle = computed(() => ({ width: `${Math.round(chapterProgress.value * 100)}%` }));
const desktopRailProgressStyle = computed(() => ({ height: `${currentDocumentProgressRatio.value * 100}%` }));
const chapterTransitioning = computed(() => chapterLoading.value && content.value !== null);
const readerPageStyle = computed(() => ({
  ...comfortStyle(comfort),
  "--reader-viewport-height": `${viewportHeight.value}px`,
}));
const readerSurfaceStyle = computed(() => ({ backgroundColor: readerThemeColor(theme.value) }));
const chapterPosition = computed(() => activeIndex.value >= 0 ? `${activeIndex.value + 1} / ${readable.value.length}` : `0 / ${readable.value.length}`);
const progressPercent = computed(() => Math.round(chapterProgress.value * 100));
const searchShortcut = navigator.platform.toLowerCase().includes("mac") ? "⌘ K" : "Ctrl K";
const searchGroups = computed(() => {
  const grouped = new Map<string, { documentId: string; documentTitle: string; hits: SearchHit[] }>();
  for (const hit of searchHits.value) {
    const group = grouped.get(hit.documentId) ?? {
      documentId: hit.documentId,
      documentTitle: hit.documentTitle,
      hits: [],
    };
    group.hits.push(hit);
    grouped.set(hit.documentId, group);
  }
  return [...grouped.values()];
});

watch(theme, (value) => {
  localStorage.setItem("reader.theme", value);
  updateThemeColor(value);
});
watch(comfort, (value) => persistReaderComfort(value), { deep: true });
watch(() => route.params.documentId, (documentId) => {
  if (typeof documentId === "string" && ignoredRouteDocumentId === documentId) {
    ignoredRouteDocumentId = null;
    return;
  }
  void openFromRoute();
});
watch([query, searchScope], () => scheduleSearch());
watch(documentQuery, () => scheduleDocumentSearch());
watch(() => content.value?.nextAfterSeq, async () => {
  await nextTick();
  connectContentLoadObserver();
  requestMoreContentIfVisible();
});
watch(searchOpen, (open) => {
  if (open) {
    scheduleSearch();
  } else {
    cancelSearchRequest();
    if (keepSearchHighlightAfterClose) {
      keepSearchHighlightAfterClose = false;
    } else {
      searchHighlight.value = "";
    }
  }
});
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
  if (readingScrollFrame !== null) window.cancelAnimationFrame(readingScrollFrame);
  if (searchTimer !== null) window.clearTimeout(searchTimer);
  if (documentSearchTimer !== null) window.clearTimeout(documentSearchTimer);
  contentAbortController?.abort();
  documentListAbortController?.abort();
  loadMoreAbortController?.abort();
  searchAbortController?.abort();
  contentLoadObserver?.disconnect();
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

function scheduleDocumentSearch(): void {
  if (documentSearchTimer !== null) window.clearTimeout(documentSearchTimer);
  documentSearchTimer = window.setTimeout(() => {
    documentSearchTimer = null;
    void loadDocuments(true);
  }, 400);
}

async function loadDocuments(reset = true): Promise<void> {
  if (!reset && (documentListLoading.value || !documentNextCursor.value)) return;
  if (reset) {
    documentListAbortController?.abort();
    documentListRequestId += 1;
    documentNextCursor.value = null;
    documentListLoadError.value = "";
  }
  const requestId = documentListRequestId;
  const requestedQuery = documentQuery.value.trim();
  const requestedCursor = reset ? null : documentNextCursor.value;
  const abortController = new AbortController();
  documentListAbortController = abortController;
  documentListLoading.value = true;
  try {
    const page = await readerApi.documents(requestedQuery, requestedCursor, 16, abortController.signal);
    if (requestId !== documentListRequestId || requestedQuery !== documentQuery.value.trim()) return;
    documents.value = reset
      ? page.items
      : [...documents.value, ...page.items.filter((item) => !documents.value.some((existing) => existing.id === item.id))];
    documentNextCursor.value = page.nextCursor;
    documentListLoadError.value = "";
    if (!requestedQuery) void cacheReaderDocuments(documents.value).catch(() => undefined);
  } catch (caught) {
    if (!abortController.signal.aborted && requestId === documentListRequestId) {
      if (reset) {
        const cachedDocuments = await getCachedReaderDocuments().catch(() => []);
        if (cachedDocuments.length > 0) {
          const normalizedQuery = requestedQuery.toLocaleLowerCase();
          documents.value = normalizedQuery
            ? cachedDocuments.filter((document) =>
                `${document.title} ${document.code}`.toLocaleLowerCase().includes(normalizedQuery))
            : cachedDocuments;
          documentNextCursor.value = null;
          documentListLoadError.value = "";
        } else {
          documentListLoadError.value = message(caught);
        }
      } else {
        documentListLoadError.value = message(caught);
      }
    }
  } finally {
    if (requestId === documentListRequestId) {
      documentListLoading.value = false;
      if (documentListAbortController === abortController) documentListAbortController = null;
    }
  }
}

async function openFromRoute(forceRefresh = false, requestedDocumentId?: string): Promise<void> {
  const requestId = ++documentRequestId;
  const keepDrawerOpen = drawer.value;
  invalidateReadingContext();
  loading.value = true;
  error.value = "";
  let documentId = requestedDocumentId ?? (typeof route.params.documentId === "string" ? route.params.documentId : undefined);
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
    if (!documents.value.some((item) => item.id === document.id)) {
      void cacheReaderDocuments([...documents.value, document]).catch(() => undefined);
    }
    if (!document.currentVersionId) return;
    const versionId = document.currentVersionId;
    const [nextToc, saved] = await Promise.all([
      loadTocOfflineAware(document.id, versionId),
      readerApi.progress(document.id).catch(() => null),
    ]);
    if (requestId !== documentRequestId || !isCurrentDocumentVersion(document.id, versionId)) return;
    toc.value = nextToc;
    expandedTocNodeIds.value = loadExpandedTocNodeIds(document.id, nextToc);
    const initial = flattenToc(nextToc).find((node) => node.id === saved?.sectionId) || firstReadableNode(nextToc);
    if (initial) {
      ensureTocPathExpanded(initial.id, nextToc);
      const restoredProgress = saved?.sectionId === initial.id ? saved : null;
      await selectNode(initial, false, !keepDrawerOpen, restoredProgress);
    }
  } catch (caught) {
    if (requestId === documentRequestId) error.value = message(caught);
  } finally {
    if (requestId === documentRequestId) loading.value = false;
  }
}

async function loadTocOfflineAware(documentId: string, versionId: string): Promise<TocNode[]> {
  try {
    const nextToc = await readerApi.toc(versionId);
    void cacheReaderToc(documentId, versionId, nextToc).catch(() => undefined);
    return nextToc;
  } catch (caught) {
    const cachedToc = await getCachedReaderToc(versionId);
    if (!cachedToc) throw caught;
    return cachedToc;
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
  restoredProgress: ReadingProgress | null = null,
  targetBlockId: string | null = null,
): Promise<void> {
  const targetNode = isReadableNode(node) ? node : firstReadableDescendant(node);
  if (!targetNode) return;
  if (!targetBlockId) searchHighlight.value = "";

  const documentId = selected.value?.id;
  const versionId = selected.value?.currentVersionId;
  if (!documentId || !versionId) return;
  if (chapterLoading.value && pendingNodeId.value === targetNode.id) return;
  contentAbortController?.abort();
  cancelLoadMore();
  const abortController = new AbortController();
  contentAbortController = abortController;
  const requestId = ++contentRequestId;
  chapterLoading.value = true;
  pendingNodeId.value = targetNode.id;
  failedNodeId.value = null;
  error.value = "";
  try {
    let nextContent: NodeContent;
    try {
      const prefetchKey = contentPrefetchKey(versionId, targetNode.id);
      const prefetched = contentPrefetches.get(prefetchKey);
      nextContent = prefetched
        ? await prefetched.promise
        : await readerApi.content(versionId, targetNode.id, undefined, abortController.signal);
      contentPrefetches.delete(prefetchKey);
      void cacheNodeContent(documentId, versionId, targetNode.id, 100, nextContent).catch(() => undefined);
    } catch (caught) {
      if (abortController.signal.aborted) return;
      const cached = await getCachedNodeContent(versionId, targetNode.id, 100);
      if (!cached) throw caught;
      nextContent = cached;
    }
    if (restoredProgress?.blockId
        && !nextContent.blocks.some((block) => block.id === restoredProgress.blockId)
        && nextContent.nextAfterSeq
        && navigator.onLine !== false) {
      nextContent = await loadThroughRestoredBlock(
        versionId,
        targetNode.id,
        nextContent,
        restoredProgress.blockId,
        abortController.signal,
      ).catch(() => nextContent);
    }
    if (!isCurrentContentRequest(requestId, documentId, versionId)) return;
    activeNode.value = targetNode;
    content.value = nextContent;
    ensureTocPathExpanded(targetNode.id);
    if (closeDrawer) drawer.value = false;
    chapterProgress.value = clampProgressRatio(targetNode.id === node.id ? restoredProgress?.progressRatio : 0);
    if (shouldScroll) {
      await nextTick();
      const targetBlock = targetBlockId
        ? [...(readingArea.value?.querySelectorAll<HTMLElement>("[data-block-id]") ?? [])]
            .find((element) => element.dataset.blockId === targetBlockId)
        : null;
      if (targetBlock) {
        targetBlock.scrollIntoView({ block: "center", inline: "nearest", behavior: "auto" });
      } else {
        readingArea.value?.scrollTo({ top: 0, behavior: "auto" });
      }
    } else if (restoredProgress?.blockId || chapterProgress.value > 0) {
      await nextTick();
      const area = readingArea.value;
      if (area) {
        const restoredBlock = restoredProgress?.blockId
          ? area.querySelector<HTMLElement>(`[data-block-id="${CSS.escape(restoredProgress.blockId)}"]`)
          : null;
        if (restoredBlock && restoredProgress) {
          const areaTop = area.getBoundingClientRect().top;
          area.scrollTo({
            top: scrollTopForBlockOffset(
              area.scrollTop,
              restoredBlock.getBoundingClientRect().top - areaTop,
              restoredProgress.blockViewportOffset,
            ),
            behavior: "auto",
          });
        } else {
          const distance = Math.max(0, area.scrollHeight - area.clientHeight);
          area.scrollTo({ top: distance * chapterProgress.value, behavior: "auto" });
        }
      }
    }
    if (!isCurrentContentRequest(requestId, documentId, versionId)) return;
    await nextTick();
    scheduleProgress();
    connectContentLoadObserver();
    requestMoreContentIfVisible();
  } catch (caught) {
    if (isCurrentContentRequest(requestId, documentId, versionId)) {
      failedNodeId.value = targetNode.id;
      if (content.value) {
        ElMessage.error({ message: "章节加载失败，请重试", duration: 2_000, showClose: false });
      } else {
        error.value = message(caught);
      }
    }
  } finally {
    if (requestId === contentRequestId) {
      chapterLoading.value = false;
      pendingNodeId.value = null;
      if (contentAbortController === abortController) contentAbortController = null;
    }
  }
}

async function loadThroughRestoredBlock(
  versionId: string,
  nodeId: string,
  initialContent: NodeContent,
  blockId: string,
  signal: AbortSignal,
): Promise<NodeContent> {
  let merged = initialContent;
  for (let pageNumber = 0; pageNumber < 20 && merged.nextAfterSeq; pageNumber += 1) {
    const page = await readerApi.content(versionId, nodeId, merged.nextAfterSeq, signal);
    merged = {
      node: initialContent.node,
      blocks: [...merged.blocks, ...page.blocks],
      nextAfterSeq: page.nextAfterSeq,
    };
    if (page.blocks.some((block) => block.id === blockId)) break;
  }
  return merged;
}

function handleAccountCommand(command: string): void {
  if (props.online === false) return;
  if (command === "admin") {
    void router.push("/admin");
    return;
  }
  if (command === "logout") emit("logout");
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
  contentLoadError.value = "";
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
      contentLoadError.value = message(caught);
    }
  } finally {
    if (requestId === loadMoreRequestId) {
      loadingMore.value = false;
      if (loadMoreAbortController === abortController) loadMoreAbortController = null;
      await nextTick();
      connectContentLoadObserver();
      requestMoreContentIfVisible();
    }
  }
}

function connectContentLoadObserver(): void {
  contentLoadObserver?.disconnect();
  if (typeof IntersectionObserver === "undefined" || !contentLoadSentinel.value) return;
  contentLoadObserver = new IntersectionObserver((entries) => {
    if (entries.some((entry) => entry.isIntersecting)) void loadMoreContent();
  }, { root: readingArea.value, rootMargin: "0px 0px 240px", threshold: 0 });
  contentLoadObserver.observe(contentLoadSentinel.value);
}

function captureContentLoadSentinel(element: unknown): void {
  contentLoadSentinel.value = element instanceof HTMLElement ? element : null;
  if (!contentLoadSentinel.value) return;
  void nextTick(() => {
    connectContentLoadObserver();
    requestMoreContentIfVisible();
  });
}

function requestMoreContentIfVisible(): void {
  const area = readingArea.value;
  const sentinel = contentLoadSentinel.value;
  if (!area || !sentinel || loadingMore.value || contentLoadError.value || !content.value?.nextAfterSeq) return;
  const areaRect = area.getBoundingClientRect();
  const sentinelRect = sentinel.getBoundingClientRect();
  if (sentinelRect.top <= areaRect.bottom + 240 && sentinelRect.bottom >= areaRect.top) {
    void loadMoreContent();
  }
}

function onReadingScroll(): void {
  if (readingScrollFrame !== null) return;
  readingScrollFrame = window.requestAnimationFrame(() => {
    readingScrollFrame = null;
    updateReadingProgressFromScroll();
  });
}

function updateReadingProgressFromScroll(): void {
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
  const blockPosition = currentReadingBlockPosition();
  const firstBlock = currentContent.blocks[0] ?? null;
  const progress: ReadingProgress = {
    versionId: document.currentVersionId,
    sectionId: node.id,
    blockId: blockPosition?.id ?? firstBlock?.id ?? null,
    charOffset: 0,
    blockViewportOffset: blockPosition?.top ?? 0,
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

function currentReadingBlockPosition(): { id: string; top: number } | null {
  const area = readingArea.value;
  if (!area) return null;
  const areaTop = area.getBoundingClientRect().top;
  const blocks = [...area.querySelectorAll<HTMLElement>("[data-block-id]")].map((element) => {
    const rect = element.getBoundingClientRect();
    return {
      id: element.dataset.blockId ?? "",
      top: rect.top - areaTop,
      bottom: rect.bottom - areaTop,
    };
  }).filter((block) => block.id);
  const anchor = Math.min(72, Math.max(24, area.clientHeight * 0.12));
  const block = blockAtViewportAnchor(blocks, anchor);
  return block ? { id: block.id, top: block.top } : null;
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
  clearContentPrefetches();
}

function cancelLoadMore(): void {
  loadMoreAbortController?.abort();
  loadMoreAbortController = null;
  loadMoreRequestId += 1;
  loadingMore.value = false;
  contentLoadError.value = "";
  contentLoadObserver?.disconnect();
}

function isCurrentDocumentVersion(documentId: string, versionId: string): boolean {
  return selected.value?.id === documentId && selected.value.currentVersionId === versionId;
}

function isCurrentContentRequest(requestId: number, documentId: string, versionId: string): boolean {
  return requestId === contentRequestId && isCurrentDocumentVersion(documentId, versionId);
}

function contentPrefetchKey(versionId: string, nodeId: string): string {
  return `${versionId}:${nodeId}`;
}

function shouldPrefetchContent(): boolean {
  const connection = (navigator as Navigator & {
    connection?: { saveData?: boolean; effectiveType?: string };
  }).connection;
  return connection?.saveData !== true && connection?.effectiveType !== "slow-2g" && connection?.effectiveType !== "2g";
}

function prefetchNode(node: TocNode | null): void {
  const targetNode = node && (isReadableNode(node) ? node : firstReadableDescendant(node));
  const documentId = selected.value?.id;
  const versionId = selected.value?.currentVersionId;
  if (!targetNode || !documentId || !versionId || targetNode.id === activeNode.value?.id || !shouldPrefetchContent()) return;
  const key = contentPrefetchKey(versionId, targetNode.id);
  if (contentPrefetches.has(key)) return;
  if (contentPrefetches.size >= 4) {
    const oldestKey = contentPrefetches.keys().next().value as string | undefined;
    if (oldestKey) {
      contentPrefetches.get(oldestKey)?.controller.abort();
      contentPrefetches.delete(oldestKey);
    }
  }
  const controller = new AbortController();
  const promise = readerApi.content(versionId, targetNode.id, undefined, controller.signal)
    .then((prefetchedContent) => {
      if (isCurrentDocumentVersion(documentId, versionId)) {
        void cacheNodeContent(documentId, versionId, targetNode.id, 100, prefetchedContent).catch(() => undefined);
      }
      return prefetchedContent;
    })
    .catch((caught) => {
      contentPrefetches.delete(key);
      throw caught;
    });
  contentPrefetches.set(key, { controller, promise });
  void promise.catch(() => undefined);
}

function clearContentPrefetches(): void {
  contentPrefetches.forEach(({ controller }) => controller.abort());
  contentPrefetches.clear();
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

function scheduleSearch(): void {
  if (searchTimer !== null) {
    window.clearTimeout(searchTimer);
    searchTimer = null;
  }
  cancelSearchRequest();
  searchHits.value = [];
  searchError.value = "";
  searchCompletedTerm.value = "";
  searchHighlight.value = "";
  const term = query.value.trim();
  if (!searchOpen.value || term.length < 2) {
    return;
  }
  searchTimer = window.setTimeout(() => {
    searchTimer = null;
    void runSearch();
  }, 500);
}

function cancelSearchRequest(): void {
  if (searchTimer !== null) {
    window.clearTimeout(searchTimer);
    searchTimer = null;
  }
  searchAbortController?.abort();
  searchAbortController = null;
  searchRequestId += 1;
  searchLoading.value = false;
}

function searchNow(): void {
  if (searchTimer !== null) {
    window.clearTimeout(searchTimer);
    searchTimer = null;
  }
  void runSearch();
}

async function runSearch(): Promise<void> {
  const term = query.value.trim();
  if (term.length < 2 || !searchOpen.value) {
    searchHits.value = [];
    searchHighlight.value = "";
    return;
  }
  searchAbortController?.abort();
  const abortController = new AbortController();
  searchAbortController = abortController;
  const requestId = ++searchRequestId;
  searchLoading.value = true;
  searchError.value = "";
  try {
    const hits = await readerApi.search(
      term,
      searchScope.value === "document" ? selected.value?.id : undefined,
      abortController.signal,
    );
    if (requestId !== searchRequestId || abortController.signal.aborted) return;
    searchHits.value = hits;
    searchHighlight.value = term;
    searchCompletedTerm.value = term;
  } catch (caught) {
    if (requestId !== searchRequestId || abortController.signal.aborted) return;
    searchError.value = message(caught);
  } finally {
    if (requestId === searchRequestId) {
      searchLoading.value = false;
      if (searchAbortController === abortController) searchAbortController = null;
    }
  }
}

function setSearchScope(scope: "document" | "all"): void {
  if (searchScope.value === scope) return;
  searchScope.value = scope;
}

function searchHitSource(hit: SearchHit): string {
  return hit.sectionPath.join(" / ") || hit.title;
}

async function jump(hit: SearchHit): Promise<void> {
  if (hit.documentId !== selected.value?.id) {
    ignoredRouteDocumentId = hit.documentId;
    try {
      await router.push(`/reader/documents/${hit.documentId}`);
      await openFromRoute(false, hit.documentId);
    } finally {
      if (ignoredRouteDocumentId === hit.documentId) ignoredRouteDocumentId = null;
    }
  }
  const node = flattenToc(toc.value).find((item) => item.id === hit.nodeId);
  if (node) {
    searchHighlight.value = query.value.trim();
    await selectNode(node, true, true, null, hit.blockId);
    keepSearchHighlightAfterClose = true;
  }
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

function resetComfort(): void {
  comfort.fontSize = 18;
  comfort.lineHeight = 1.85;
  comfort.columnWidth = 740;
  comfort.codeWrap = false;
  comfort.fontFamily = "sans";
}
function message(value: unknown): string { return toUserMessage(value, "加载失败"); }
</script>

<template>
  <div
    class="reader-page"
    :class="[
      `theme-${theme}`,
      {
        'reader-overlay-open': drawer || searchOpen || mobileComfortOpen,
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
              class="reader-comfort-button reader-comfort-desktop"
              type="button"
              aria-label="阅读设置"
              title="阅读设置"
              :aria-expanded="comfortOpen"
            >
              <el-icon><Reading /></el-icon>
              <span>阅读设置</span>
            </button>
          </template>
          <ReaderComfortSettings
            v-model:theme="theme"
            v-model:font-size="comfort.fontSize"
            v-model:line-height="comfort.lineHeight"
            v-model:column-width="comfort.columnWidth"
            v-model:code-wrap="comfort.codeWrap"
            v-model:font-family="comfort.fontFamily"
            @reset="resetComfort"
          />
        </el-popover>
        <button
          class="reader-comfort-button reader-comfort-mobile"
          type="button"
          aria-label="阅读设置"
          title="阅读设置"
          :aria-expanded="mobileComfortOpen"
          @click="mobileComfortOpen = true"
        >
          <el-icon><Reading /></el-icon>
        </button>
        <el-button v-if="props.online !== false" class="reader-admin-link" text @click="router.push('/admin')">管理后台</el-button>
        <el-button v-if="props.online !== false" class="reader-logout-button" text @click="emit('logout')">退出</el-button>
        <el-dropdown v-if="props.online !== false" class="reader-account-menu" trigger="click" placement="bottom-end" @command="handleAccountCommand">
          <button class="reader-theme-trigger" type="button" aria-label="账户菜单" title="账户菜单">
            <el-icon><User /></el-icon>
            <span>账户</span>
            <el-icon class="reader-theme-chevron"><ArrowDown /></el-icon>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="admin">管理后台</el-dropdown-item>
              <el-dropdown-item command="logout">退出</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
      <!-- 桌面阅读进度条 -->
      <div class="reader-header-progress" :class="{ 'is-loading': chapterTransitioning }" aria-hidden="true">
        <span class="reader-progress-fill" :style="desktopProgressStyle"></span>
        <span v-if="chapterTransitioning" class="chapter-loading-dots"><i /><i /><i /></span>
      </div>
      <!-- 移动端章节进度条 -->
      <div class="mobile-chapter-progress" :class="{ 'is-loading': chapterTransitioning }" aria-label="当前章节阅读进度">
        <span class="reader-progress-fill" :style="mobileProgressStyle"></span>
        <span v-if="chapterTransitioning" class="chapter-loading-dots" aria-hidden="true"><i /><i /><i /></span>
      </div>
      <output v-if="!chapterTransitioning" class="mobile-progress-label" aria-live="polite">{{ progressPercent }}%</output>
      <span v-if="chapterTransitioning" class="sr-only" role="status" aria-live="polite">正在加载下一节</span>
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
            @prefetch="prefetchNode"
          />
        </div>
      </template>
      <template v-else>
        <div class="reader-desktop-nav-header document-browser-header">
          <button class="reader-drawer-back" type="button" aria-label="返回当前文档目录" @click="showDesktopTocView">
            <ArrowLeftBold class="reader-drawer-back-icon" aria-hidden="true" />
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
            :loading="documentListLoading"
            :has-more="!!documentNextCursor"
            :load-error="documentListLoadError"
            @select="selectDocumentFromNavigation($event, 'desktop')"
            @load-more="loadDocuments(false)"
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
      <div v-if="loading" class="reader-state">正在加载章节…</div>
      <el-alert v-else-if="error" :title="error" type="error" show-icon :closable="false" />
      <template v-else-if="content">
        <article :key="content.node.id" class="reader-article" :data-node-id="content.node.id">
          <h1>{{ content.node.title }}</h1>
          <ContentBlockView v-for="block in content.blocks" :key="block.id" :block="block" :highlight="searchHighlight" :wrap-code="comfort.codeWrap" show-code-wrap-toggle :asset-base-url="selected ? `/assets/documents/${selected.id}/versions/${selected.currentVersionId}` : undefined" @update:wrap-code="comfort.codeWrap = $event" />
          <div v-if="content.nextAfterSeq" :ref="captureContentLoadSentinel" class="reader-load-more">
            <span v-if="loadingMore" role="status">正在载入后续内容…</span>
            <button v-else-if="contentLoadError" type="button" @click="loadMoreContent">载入失败，点击重试</button>
            <span v-else class="sr-only">继续阅读时将自动载入后续内容</span>
          </div>
        </article>
        <nav class="chapter-pagination" aria-label="章节翻页">
          <el-button class="chapter-nav-button chapter-nav-previous" :disabled="!previousNode || chapterLoading" :loading="pendingNodeId === previousNode?.id" :icon="ArrowLeft" @pointerenter="prefetchNode(previousNode)" @focus="prefetchNode(previousNode)" @click="previousNode && selectNode(previousNode)">上一节</el-button>
          <span class="chapter-position" aria-live="polite">{{ chapterPosition }}</span>
          <el-button class="chapter-nav-button chapter-nav-next" type="primary" :disabled="!nextNode || chapterLoading" :loading="pendingNodeId === nextNode?.id" @pointerenter="prefetchNode(nextNode)" @focus="prefetchNode(nextNode)" @click="nextNode && selectNode(nextNode)">下一节<el-icon><ArrowRight /></el-icon></el-button>
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
              compact-groups
              @select="selectNode"
              @toggle="toggleTocNode"
              @prefetch="prefetchNode"
            />
          </div>
        </template>
        <template v-else>
          <div class="reader-drawer-sticky">
            <header>
              <button class="reader-drawer-back" type="button" aria-label="返回当前文档目录" @click="showMobileTocView">
                <ArrowLeftBold class="reader-drawer-back-icon" aria-hidden="true" />
                返回目录
              </button>
              <strong>切换文档</strong>
            </header>
          </div>
          <div ref="mobileDocumentListArea" class="reader-mobile-document-browser">
            <ReaderDocumentList
              v-model:query="documentQuery"
              :documents="navigationDocuments"
              :selected-document-id="selected?.id || null"
              :pending-document-id="pendingDocumentId"
              :error="documentSwitchError"
              :loading="documentListLoading"
              :has-more="!!documentNextCursor"
              :load-error="documentListLoadError"
              @select="selectDocumentFromNavigation($event, 'mobile')"
              @load-more="loadDocuments(false)"
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
          <strong>搜索文档内容</strong>
          <el-button circle :icon="Close" aria-label="关闭搜索" @click="searchOpen = false" />
        </header>
        <div class="comfort-option-grid" style="width:max-content;grid-template-columns:repeat(2,minmax(0,1fr))" role="group" aria-label="搜索范围">
          <button type="button" :class="{ active: searchScope === 'document' }" :aria-pressed="searchScope === 'document'" @click="setSearchScope('document')">当前文档</button>
          <button type="button" :class="{ active: searchScope === 'all' }" :aria-pressed="searchScope === 'all'" @click="setSearchScope('all')">全部文档</button>
        </div>
        <el-input
          ref="searchInput"
          v-model="query"
          name="reader-search"
          aria-label="搜索标题或正文"
          aria-describedby="reader-search-feedback"
          autocomplete="off"
          placeholder="输入至少 2 个字符…"
          clearable
          @keyup.enter="searchNow"
        >
          <template #append><el-button :icon="Search" :loading="searchLoading" aria-label="立即搜索" @click="searchNow" /></template>
        </el-input>
        <div id="reader-search-feedback" class="reader-search-feedback" role="status" aria-live="polite">
          <template v-if="query.trim().length === 1">再输入 1 个字符开始搜索</template>
          <template v-else-if="searchLoading">正在搜索“{{ query.trim() }}”…</template>
          <template v-else-if="searchError">
            <span>{{ searchError }}</span>
            <button type="button" @click="searchNow">重新搜索</button>
          </template>
          <template v-else-if="searchCompletedTerm && searchHits.length === 0">没有找到“{{ searchCompletedTerm }}”</template>
          <template v-else-if="searchCompletedTerm">找到 {{ searchHits.length }} 条结果</template>
          <template v-else>可搜索章节标题和正文</template>
        </div>
        <div v-if="searchGroups.length" class="reader-search-groups">
          <section v-for="group in searchGroups" :key="group.documentId" class="reader-search-group">
            <header v-if="searchScope === 'all'">
              <strong>{{ group.documentTitle }}</strong>
              <span>{{ group.hits.length }} 条</span>
            </header>
            <button v-for="hit in group.hits" :key="hit.blockId" class="reader-search-hit" type="button" @click="jump(hit)">
              <strong><InlineMarkdown :text="hit.title" :highlight="searchHighlight" /></strong>
              <small>{{ searchHitSource(hit) }}</small>
              <span><InlineMarkdown :text="hit.snippet" :highlight="searchHighlight" /></span>
            </button>
          </section>
        </div>
      </section>
    </el-drawer>

    <el-drawer
      v-model="mobileComfortOpen"
      class="reader-overlay-drawer reader-comfort-drawer"
      direction="btt"
      size="min(82vh, 620px)"
      :with-header="false"
    >
      <section class="reader-mobile-comfort-sheet">
        <header>
          <strong>阅读设置</strong>
          <el-button circle :icon="Close" aria-label="关闭阅读设置" @click="mobileComfortOpen = false" />
        </header>
        <ReaderComfortSettings
          v-model:theme="theme"
          v-model:font-size="comfort.fontSize"
          v-model:line-height="comfort.lineHeight"
          v-model:column-width="comfort.columnWidth"
          v-model:code-wrap="comfort.codeWrap"
          v-model:font-family="comfort.fontFamily"
          @reset="resetComfort"
        />
      </section>
    </el-drawer>
  </div>
</template>
