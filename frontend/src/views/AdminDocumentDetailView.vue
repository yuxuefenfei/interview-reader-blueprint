<script setup lang="ts">
import { ArrowLeft, CircleCheckFilled, Delete, EditPen, MoreFilled, Plus, RefreshRight, UploadFilled } from "@element-plus/icons-vue";
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus/es/components/message/index";
import { ElMessageBox } from "element-plus/es/components/message-box/index";
import { adminApi } from "../api/admin";
import { formatTime, zh } from "../shared/presentation";
import type { AdminDocumentSummary, DeletionJob, VersionSummary } from "../types/api";

type ActionKind = "create" | "publish" | "discard" | "take-down" | "restore" | "delete-document" | "retry-delete";
type MoreCommand = "create" | "discard";

const route = useRoute();
const router = useRouter();
const documentId = route.params.documentId as string;
const document = ref<AdminDocumentSummary | null>(null);
const versions = ref<VersionSummary[]>([]);
const loading = ref(false);
const loadError = ref("");
const activeAction = ref<{ target: string; kind: ActionKind } | null>(null);
let deletionPollTimer: number | null = null;

const versionById = computed(() => new Map(versions.value.map((version) => [version.id, version])));
const publishedVersion = computed(() => versions.value.find((version) => version.status === "PUBLISHED") ?? null);
const historyVersions = computed(() => versions.value.filter((version) => version.id !== publishedVersion.value?.id));
const latestDraftVersionNo = computed(() => Math.max(0, ...versions.value.filter((version) => version.status === "DRAFT").map((version) => version.versionNo)));
const deletionLocked = computed(() => document.value?.status === "DELETING" || document.value?.status === "DELETE_FAILED");
const actionsLocked = computed(() => activeAction.value !== null || deletionLocked.value);
const canPermanentlyDelete = computed(() => document.value?.status === "DRAFT" || document.value?.status === "OFFLINE");

onMounted(() => { void load(); });
onBeforeUnmount(() => { if (deletionPollTimer !== null) window.clearTimeout(deletionPollTimer); });

async function load(): Promise<void> {
  loading.value = true;
  loadError.value = "";
  try {
    [document.value, versions.value] = await Promise.all([adminApi.document(documentId), adminApi.versions(documentId)]);
    scheduleDeletionPoll(document.value.deletionJob);
  } catch (caught) {
    loadError.value = message(caught);
    ElMessage.error(loadError.value);
  } finally { loading.value = false; }
}

function scheduleDeletionPoll(job: DeletionJob | null): void {
  if (deletionPollTimer !== null) window.clearTimeout(deletionPollTimer);
  deletionPollTimer = null;
  if (!job || (job.status !== "QUEUED" && job.status !== "RUNNING")) return;
  deletionPollTimer = window.setTimeout(() => { void pollDeletion(job.id); }, 700);
}

async function pollDeletion(jobId: string): Promise<void> {
  try {
    const job = await adminApi.deletionJob(jobId);
    if (job.status === "COMPLETED") {
      ElMessage.success("文档及其关联数据已永久删除");
      await router.push("/admin/documents");
      return;
    }
    if (document.value) {
      document.value.deletionJob = job;
      document.value.status = job.status === "FAILED" ? "DELETE_FAILED" : "DELETING";
    }
    scheduleDeletionPoll(job);
  } catch (caught) {
    ElMessage.error(message(caught));
  }
}

function lineageNumbers(version: VersionSummary): number[] {
  const numbers = [version.versionNo];
  const visited = new Set<string>([version.id]);
  let current = version;
  while (current.parentVersionId) {
    if (visited.has(current.parentVersionId)) break;
    visited.add(current.parentVersionId);
    const parent = versionById.value.get(current.parentVersionId);
    if (!parent) {
      if (current.parentVersionNo !== null && numbers[0] !== current.parentVersionNo) numbers.unshift(current.parentVersionNo);
      return numbers;
    }
    numbers.unshift(parent.versionNo);
    current = parent;
  }
  if (!current.parentVersionId && current.parentVersionNo !== null && numbers[0] !== current.parentVersionNo) numbers.unshift(current.parentVersionNo);
  return numbers;
}
function lineageLabel(version: VersionSummary): string {
  const numbers = lineageNumbers(version);
  return numbers.length > 1 ? `修订链：${numbers.map((number) => `v${number}`).join(" → ")}` : "初始版本";
}
function includesCurrentPublishedVersion(version: VersionSummary): boolean | null {
  const current = publishedVersion.value;
  if (!current) return null;
  if (version.id === current.id) return true;
  const visited = new Set<string>([version.id]);
  let candidate = version;
  while (candidate.parentVersionId) {
    if (candidate.parentVersionId === current.id) return true;
    if (visited.has(candidate.parentVersionId)) return null;
    visited.add(candidate.parentVersionId);
    const parent = versionById.value.get(candidate.parentVersionId);
    if (!parent) return null;
    candidate = parent;
  }
  return candidate.parentVersionNo === current.versionNo ? true : false;
}
function isLatestDraft(version: VersionSummary): boolean { return version.status === "DRAFT" && version.versionNo === latestDraftVersionNo.value; }
function isBranchDraft(version: VersionSummary): boolean { return version.status === "DRAFT" && includesCurrentPublishedVersion(version) === false; }
function versionStatusLabel(version: VersionSummary): string {
  if (version.status !== "DRAFT") return zh(version.status);
  if (isLatestDraft(version) && isBranchDraft(version)) return "最新分支草稿";
  if (isLatestDraft(version)) return "最新草稿";
  if (isBranchDraft(version)) return "分支草稿";
  return "草稿";
}
function isActive(target: string, kind: ActionKind): boolean { return activeAction.value?.target === target && activeAction.value.kind === kind; }
function begin(target: string, kind: ActionKind): boolean {
  if (activeAction.value !== null || (deletionLocked.value && kind !== "retry-delete")) return false;
  activeAction.value = { target, kind };
  return true;
}
function finish(target: string, kind: ActionKind): void { if (isActive(target, kind)) activeAction.value = null; }

async function createRevision(version: VersionSummary): Promise<void> {
  if (!begin(version.id, "create")) return;
  try {
    const draft = await adminApi.createRevision(documentId, version.id);
    ElMessage.success(`已基于 v${version.versionNo} 创建草稿 v${draft.versionNo}`);
    await router.push(`/admin/versions/${draft.id}/edit`);
  } catch (caught) { ElMessage.error(message(caught)); }
  finally { finish(version.id, "create"); }
}
function publishConfirmation(version: VersionSummary): string {
  const current = publishedVersion.value;
  const lineage = lineageLabel(version).replace("修订链：", "修订链 ");
  if (!current) return `${lineage}。发布 v${version.versionNo} 后，它将成为阅读端的首个线上版本。`;
  const warning = includesCurrentPublishedVersion(version) === false ? `该草稿不包含当前线上 v${current.versionNo} 的后续变更，可能覆盖现有内容。` : "";
  return `${lineage}。${warning}发布 v${version.versionNo} 后将替换当前线上 v${current.versionNo}，阅读端会立即切换。`;
}
async function publish(version: VersionSummary): Promise<void> {
  if (!begin(version.id, "publish")) return;
  try {
    await ElMessageBox.confirm(publishConfirmation(version), `发布 v${version.versionNo}`, { type: "warning", confirmButtonText: `发布 v${version.versionNo}`, cancelButtonText: "取消" });
    await adminApi.publish(documentId, version.id);
    ElMessage.success(`v${version.versionNo} 已发布`);
    await load();
  } catch (caught) { if (caught !== "cancel" && caught !== "close") ElMessage.error(message(caught)); }
  finally { finish(version.id, "publish"); }
}
async function takeDown(): Promise<void> {
  if (!document.value || !begin(documentId, "take-down")) return;
  try {
    await ElMessageBox.confirm("下架后阅读端将立即隐藏该文档；所有版本、阅读进度、书签和笔记都会保留，可随时重新上架。", "下架文档", { type: "warning", confirmButtonText: "确认下架", cancelButtonText: "取消" });
    await adminApi.takeDown(documentId);
    ElMessage.success("文档已下架，数据已保留");
    await load();
  } catch (caught) { if (caught !== "cancel" && caught !== "close") ElMessage.error(message(caught)); }
  finally { finish(documentId, "take-down"); }
}
async function restore(): Promise<void> {
  if (!begin(documentId, "restore")) return;
  try {
    await adminApi.restore(documentId);
    ElMessage.success("文档已重新上架");
    await load();
  } catch (caught) { ElMessage.error(message(caught)); }
  finally { finish(documentId, "restore"); }
}
async function permanentlyDelete(): Promise<void> {
  const current = document.value;
  if (!current || !begin(documentId, "delete-document")) return;
  try {
    const result = await ElMessageBox.prompt(
      `此操作开始后不可撤销。文档、全部版本与草稿、阅读数据、导入记录、原始文件及转换中间产物都会被彻底删除。请输入完整文档标题：${current.title}`,
      "永久删除文档",
      { type: "error", confirmButtonText: "永久删除", cancelButtonText: "取消", inputPlaceholder: current.title,
        inputValidator: (value: string) => value === current.title || "输入必须与完整文档标题完全一致" }
    );
    const job = await adminApi.deleteDocument(documentId, result.value);
    current.status = job.status === "FAILED" ? "DELETE_FAILED" : "DELETING";
    current.deletionJob = job;
    ElMessage.warning("永久删除任务已开始，操作不可撤销");
    scheduleDeletionPoll(job);
  } catch (caught) { if (caught !== "cancel" && caught !== "close") ElMessage.error(message(caught)); }
  finally { finish(documentId, "delete-document"); }
}
async function retryDeletion(): Promise<void> {
  const job = document.value?.deletionJob;
  if (!job || !begin(documentId, "retry-delete")) return;
  try {
    const restarted = await adminApi.retryDeletion(job.id);
    if (document.value) { document.value.status = "DELETING"; document.value.deletionJob = restarted; }
    ElMessage.info("已重新提交永久删除任务");
    scheduleDeletionPoll(restarted);
  } catch (caught) { ElMessage.error(message(caught)); }
  finally { finish(documentId, "retry-delete"); }
}
async function discard(version: VersionSummary): Promise<void> {
  if (!begin(version.id, "discard")) return;
  try {
    await ElMessageBox.confirm(`将永久丢弃草稿 v${version.versionNo}。`, `丢弃草稿 v${version.versionNo}`, { type: "warning", confirmButtonText: `丢弃 v${version.versionNo}`, cancelButtonText: "取消" });
    await adminApi.deleteDraft(version.id);
    ElMessage.success(`草稿 v${version.versionNo} 已丢弃`);
    await load();
  } catch (caught) { if (caught !== "cancel" && caught !== "close") ElMessage.error(message(caught)); }
  finally { finish(version.id, "discard"); }
}
function editVersion(version: VersionSummary): void { if (!actionsLocked.value) void router.push(`/admin/versions/${version.id}/edit`); }
function handleMoreCommand(version: VersionSummary, command: string | number | object): void {
  if (command === ("create" satisfies MoreCommand)) void createRevision(version);
  if (command === ("discard" satisfies MoreCommand)) void discard(version);
}
function message(value: unknown): string { return value instanceof Error ? value.message : "操作失败，请稍后重试"; }
</script>

<template>
  <section class="admin-view document-detail-view" v-loading="loading" :aria-busy="loading || actionsLocked">
    <header class="admin-view-header">
      <div class="detail-heading">
        <el-button text :icon="ArrowLeft" @click="router.push('/admin/documents')">返回文档管理</el-button>
        <p class="eyebrow">版本管理</p>
        <h1>{{ document?.title || '文档详情' }}</h1>
        <span>{{ document?.code }} · 共 {{ document?.versionCount || 0 }} 个版本，{{ document?.draftCount || 0 }} 个草稿</span>
      </div>
      <div class="document-lifecycle-actions">
        <el-button v-if="document?.status === 'OFFLINE' && document.currentVersionId" type="success" :icon="RefreshRight" :loading="isActive(documentId, 'restore')" :disabled="actionsLocked && !isActive(documentId, 'restore')" data-testid="restore-document" @click="restore">重新上架</el-button>
        <el-button v-if="canPermanentlyDelete" type="danger" plain :icon="Delete" :loading="isActive(documentId, 'delete-document')" :disabled="actionsLocked && !isActive(documentId, 'delete-document')" data-testid="delete-document" @click="permanentlyDelete">永久删除文档</el-button>
        <el-button :icon="UploadFilled" :disabled="actionsLocked" @click="router.push({ path: '/admin/imports', query: { targetDocumentId: documentId } })">重新导入</el-button>
      </div>
    </header>

    <el-alert v-if="document?.status === 'DELETING'" :title="`永久删除进行中：${zh(document.deletionJob?.currentStage)}`" type="warning" :closable="false" show-icon description="任务开始后不可撤销；当前文档的全部管理操作已锁定。" />
    <el-alert v-if="document?.status === 'DELETE_FAILED'" title="永久删除失败" type="error" :closable="false" show-icon :description="document.deletionJob?.errorMessage || '已自动重试 3 次，请手动重试。'">
      <template #default><el-button type="danger" :loading="isActive(documentId, 'retry-delete')" data-testid="retry-deletion" @click="retryDeletion">重试删除</el-button></template>
    </el-alert>
    <div v-if="loadError" class="load-error-panel" role="alert"><span>{{ loadError }}</span><el-button :loading="loading" @click="load">重新加载</el-button></div>

    <el-card v-if="!loadError || versions.length" shadow="never" class="version-history-card">
      <template #header><div class="card-heading"><div><h2>版本历史</h2><span>当前发布版本单独置顶；下架只影响阅读端可见性，不删除任何数据。</span></div></div></template>
      <el-empty v-if="!loading && !versions.length" description="当前文档还没有版本" />
      <template v-else>
        <section v-if="publishedVersion" class="current-version-panel" aria-labelledby="current-version-heading">
          <div class="current-version-identity"><span>{{ document?.status === 'OFFLINE' ? '已保留的发布版本' : '当前线上版本' }}</span><h3 id="current-version-heading">v{{ publishedVersion.versionNo }}</h3><small>发布于 {{ formatTime(publishedVersion.publishedAt || publishedVersion.createdAt) }}</small></div>
          <div class="current-version-meta"><div><el-tag :type="document?.status === 'OFFLINE' ? 'info' : 'success'">{{ zh(document?.status) }}</el-tag><el-tag effect="plain">{{ zh(publishedVersion.sourceType) }}</el-tag></div><strong class="version-file-name">{{ publishedVersion.sourceFileName || '未命名来源文件' }}</strong><span>{{ lineageLabel(publishedVersion) }}</span></div>
          <div class="current-version-actions">
            <el-button :icon="Plus" :loading="isActive(publishedVersion.id, 'create')" :disabled="actionsLocked && !isActive(publishedVersion.id, 'create')" :data-testid="`create-${publishedVersion.id}`" @click="createRevision(publishedVersion)">基于 v{{ publishedVersion.versionNo }} 创建修订</el-button>
            <el-button v-if="document?.status === 'PUBLISHED'" type="warning" plain :loading="isActive(documentId, 'take-down')" :disabled="actionsLocked && !isActive(documentId, 'take-down')" :data-testid="`take-down-${publishedVersion.id}`" @click="takeDown">下架</el-button>
          </div>
        </section>
        <div v-if="historyVersions.length" class="history-section-heading"><div><h3>草稿与历史版本</h3><span>{{ historyVersions.length }} 个版本，按版本号倒序排列</span></div></div>
        <el-empty v-else description="暂无草稿或历史版本" :image-size="72" />
        <div v-if="historyVersions.length" class="version-history">
          <article v-for="version in historyVersions" :key="version.id" :data-version-id="version.id" class="version-row" :class="{ 'latest-draft': isLatestDraft(version), 'branch-draft': isBranchDraft(version) }">
            <div class="version-number"><strong>v{{ version.versionNo }}</strong><span>{{ formatTime(version.createdAt) }}</span></div>
            <div class="version-meta"><div><el-tag :type="version.status === 'DRAFT' ? 'warning' : 'info'">{{ versionStatusLabel(version) }}</el-tag><el-tag effect="plain">{{ zh(version.sourceType) }}</el-tag></div><strong class="version-file-name">{{ version.sourceFileName || '未命名来源文件' }}</strong><span class="version-lineage">{{ lineageLabel(version) }}</span><span v-if="isBranchDraft(version)" class="version-branch-warning">不包含当前线上版本的后续变更，发布前请核对内容。</span></div>
            <div class="version-row-actions">
              <template v-if="version.status === 'DRAFT'">
                <el-button type="primary" :icon="EditPen" :disabled="actionsLocked" :data-testid="`edit-${version.id}`" @click="editVersion(version)">继续编辑</el-button>
                <el-button type="success" plain :icon="CircleCheckFilled" :loading="isActive(version.id, 'publish')" :disabled="actionsLocked && !isActive(version.id, 'publish')" :data-testid="`publish-${version.id}`" @click="publish(version)">发布 v{{ version.versionNo }}</el-button>
                <el-dropdown trigger="click" :disabled="actionsLocked" @command="handleMoreCommand(version, $event)"><el-button :icon="MoreFilled" :loading="isActive(version.id, 'create') || isActive(version.id, 'discard')" :disabled="actionsLocked" :data-testid="`more-${version.id}`">更多</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item command="create" :icon="Plus">基于 v{{ version.versionNo }} 创建修订</el-dropdown-item><el-dropdown-item command="discard" :icon="Delete" divided class="danger-command">丢弃草稿 v{{ version.versionNo }}</el-dropdown-item></el-dropdown-menu></template></el-dropdown>
              </template>
              <el-button v-else :icon="Plus" :loading="isActive(version.id, 'create')" :disabled="actionsLocked && !isActive(version.id, 'create')" :data-testid="`create-${version.id}`" @click="createRevision(version)">基于 v{{ version.versionNo }} 创建修订</el-button>
            </div>
          </article>
        </div>
      </template>
    </el-card>
  </section>
</template>