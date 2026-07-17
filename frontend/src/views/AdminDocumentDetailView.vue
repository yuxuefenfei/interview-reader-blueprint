<script setup lang="ts">
import { ArrowLeft, CircleCheckFilled, Delete, EditPen, MoreFilled, Plus, UploadFilled } from "@element-plus/icons-vue";
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus/es/components/message/index";
import { ElMessageBox } from "element-plus/es/components/message-box/index";
import { adminApi } from "../api/admin";
import { formatTime, zh } from "../shared/presentation";
import type { AdminDocumentSummary, VersionSummary } from "../types/api";

type VersionActionKind = "create" | "publish" | "discard";
type MoreCommand = "create" | "discard";

const route = useRoute();
const router = useRouter();
const documentId = route.params.documentId as string;
const document = ref<AdminDocumentSummary | null>(null);
const versions = ref<VersionSummary[]>([]);
const loading = ref(false);
const loadError = ref("");
const activeAction = ref<{ versionId: string; kind: VersionActionKind } | null>(null);

const versionById = computed(() => new Map(versions.value.map((version) => [version.id, version])));
const publishedVersion = computed(() => versions.value.find((version) => version.status === "PUBLISHED") ?? null);
const historyVersions = computed(() => versions.value.filter((version) => version.id !== publishedVersion.value?.id));
const latestDraftVersionNo = computed(() => Math.max(0, ...versions.value
  .filter((version) => version.status === "DRAFT")
  .map((version) => version.versionNo)));
const actionsLocked = computed(() => activeAction.value !== null);

onMounted(() => { void load(); });

async function load(): Promise<void> {
  loading.value = true;
  loadError.value = "";
  try {
    [document.value, versions.value] = await Promise.all([
      adminApi.document(documentId),
      adminApi.versions(documentId)
    ]);
  } catch (caught) {
    loadError.value = message(caught);
    ElMessage.error(loadError.value);
  } finally {
    loading.value = false;
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

  if (!current.parentVersionId && current.parentVersionNo !== null && numbers[0] !== current.parentVersionNo) {
    numbers.unshift(current.parentVersionNo);
  }
  return numbers;
}

function lineageLabel(version: VersionSummary): string {
  const numbers = lineageNumbers(version);
  return numbers.length > 1 ? `修订链：${numbers.map((number) => `v${number}`).join(" → ")}` : "初始版本";
}

function includesCurrentPublishedVersion(version: VersionSummary): boolean | null {
  const currentPublished = publishedVersion.value;
  if (!currentPublished) return null;
  if (version.id === currentPublished.id) return true;

  const visited = new Set<string>([version.id]);
  let candidate = version;
  while (candidate.parentVersionId) {
    if (candidate.parentVersionId === currentPublished.id) return true;
    if (visited.has(candidate.parentVersionId)) return null;
    visited.add(candidate.parentVersionId);
    const parent = versionById.value.get(candidate.parentVersionId);
    if (!parent) return null;
    candidate = parent;
  }
  return candidate.parentVersionNo === currentPublished.versionNo ? true : false;
}

function isLatestDraft(version: VersionSummary): boolean {
  return version.status === "DRAFT" && version.versionNo === latestDraftVersionNo.value;
}

function isBranchDraft(version: VersionSummary): boolean {
  return version.status === "DRAFT" && includesCurrentPublishedVersion(version) === false;
}

function versionStatusLabel(version: VersionSummary): string {
  if (version.status !== "DRAFT") return zh(version.status);
  if (isLatestDraft(version) && isBranchDraft(version)) return "最新分支草稿";
  if (isLatestDraft(version)) return "最新草稿";
  if (isBranchDraft(version)) return "分支草稿";
  return "草稿";
}

function isActiveAction(versionId: string, kind: VersionActionKind): boolean {
  return activeAction.value?.versionId === versionId && activeAction.value.kind === kind;
}

function beginAction(versionId: string, kind: VersionActionKind): boolean {
  if (actionsLocked.value) return false;
  activeAction.value = { versionId, kind };
  return true;
}

function finishAction(versionId: string, kind: VersionActionKind): void {
  if (isActiveAction(versionId, kind)) activeAction.value = null;
}

async function createRevision(version: VersionSummary): Promise<void> {
  if (!beginAction(version.id, "create")) return;
  try {
    const draft = await adminApi.createRevision(documentId, version.id);
    ElMessage.success(`已基于 v${version.versionNo} 创建草稿 v${draft.versionNo}`);
    await router.push(`/admin/versions/${draft.id}/edit`);
  } catch (caught) {
    ElMessage.error(message(caught));
  } finally {
    finishAction(version.id, "create");
  }
}

function publishConfirmation(version: VersionSummary): string {
  const currentPublished = publishedVersion.value;
  const lineage = lineageLabel(version).replace("修订链：", "修订链 ");
  if (!currentPublished) return `${lineage}。发布 v${version.versionNo} 后，它将成为阅读端的首个线上版本。`;
  const branchWarning = includesCurrentPublishedVersion(version) === false
    ? `该草稿不包含当前线上 v${currentPublished.versionNo} 的后续变更，可能覆盖现有内容。`
    : "";
  return `${lineage}。${branchWarning}发布 v${version.versionNo} 后将替换当前线上 v${currentPublished.versionNo}，阅读端会立即切换。`;
}

async function publish(version: VersionSummary): Promise<void> {
  if (!beginAction(version.id, "publish")) return;
  try {
    await ElMessageBox.confirm(publishConfirmation(version), `发布 v${version.versionNo}`, {
      type: "warning",
      confirmButtonText: `发布 v${version.versionNo}`,
      cancelButtonText: "取消"
    });
    await adminApi.publish(documentId, version.id);
    ElMessage.success(`v${version.versionNo} 已发布`);
    await load();
  } catch (caught) {
    if (caught !== "cancel" && caught !== "close") ElMessage.error(message(caught));
  } finally {
    finishAction(version.id, "publish");
  }
}

async function discard(version: VersionSummary): Promise<void> {
  if (!beginAction(version.id, "discard")) return;
  try {
    await ElMessageBox.confirm(
      `将永久丢弃草稿 v${version.versionNo}。关联的导入任务会保留，可再次提交生成草稿。`,
      `丢弃草稿 v${version.versionNo}`,
      { type: "warning", confirmButtonText: `丢弃 v${version.versionNo}`, cancelButtonText: "取消" }
    );
    await adminApi.deleteDraft(version.id);
    ElMessage.success(`草稿 v${version.versionNo} 已丢弃`);
    await load();
  } catch (caught) {
    if (caught !== "cancel" && caught !== "close") ElMessage.error(message(caught));
  } finally {
    finishAction(version.id, "discard");
  }
}

function editVersion(version: VersionSummary): void {
  if (!actionsLocked.value) void router.push(`/admin/versions/${version.id}/edit`);
}

function handleMoreCommand(version: VersionSummary, command: string | number | object): void {
  if (command === ("create" satisfies MoreCommand)) void createRevision(version);
  if (command === ("discard" satisfies MoreCommand)) void discard(version);
}

function message(value: unknown): string {
  return value instanceof Error ? value.message : "操作失败，请稍后重试";
}
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
      <el-button :icon="UploadFilled" :disabled="actionsLocked" @click="router.push({ path: '/admin/imports', query: { targetDocumentId: documentId } })">重新导入</el-button>
    </header>

    <div v-if="loadError" class="load-error-panel" role="alert">
      <span>{{ loadError }}</span>
      <el-button :loading="loading" @click="load">重新加载</el-button>
    </div>

    <el-card v-if="!loadError || versions.length" shadow="never" class="version-history-card">
      <template #header>
        <div class="card-heading">
          <div>
            <h2>版本历史</h2>
            <span>当前线上版本单独置顶；修订链用于识别并行草稿，发布前请确认来源分支。</span>
          </div>
        </div>
      </template>

      <el-empty v-if="!loading && !versions.length" description="当前文档还没有版本" />
      <template v-else>
        <section v-if="publishedVersion" class="current-version-panel" aria-labelledby="current-version-heading">
          <div class="current-version-identity">
            <span>当前线上版本</span>
            <h3 id="current-version-heading">v{{ publishedVersion.versionNo }}</h3>
            <small>发布于 {{ formatTime(publishedVersion.publishedAt || publishedVersion.createdAt) }}</small>
          </div>
          <div class="current-version-meta">
            <div><el-tag type="success">已发布</el-tag><el-tag effect="plain">{{ zh(publishedVersion.sourceType) }}</el-tag></div>
            <strong class="version-file-name" :title="publishedVersion.sourceFileName || '未命名来源文件'">{{ publishedVersion.sourceFileName || '未命名来源文件' }}</strong>
            <span>{{ lineageLabel(publishedVersion) }}</span>
          </div>
          <el-button
            :icon="Plus"
            :loading="isActiveAction(publishedVersion.id, 'create')"
            :disabled="actionsLocked && !isActiveAction(publishedVersion.id, 'create')"
            :data-testid="`create-${publishedVersion.id}`"
            @click="createRevision(publishedVersion)"
          >基于 v{{ publishedVersion.versionNo }} 创建修订</el-button>
        </section>

        <div v-if="historyVersions.length" class="history-section-heading">
          <div><h3>草稿与历史版本</h3><span>{{ historyVersions.length }} 个版本，按版本号倒序排列</span></div>
        </div>
        <el-empty v-else description="暂无草稿或历史版本" :image-size="72" />

        <div v-if="historyVersions.length" class="version-history">
          <article
            v-for="version in historyVersions"
            :key="version.id"
            :data-version-id="version.id"
            class="version-row"
            :class="{ 'latest-draft': isLatestDraft(version), 'branch-draft': isBranchDraft(version) }"
          >
            <div class="version-number">
              <strong>v{{ version.versionNo }}</strong>
              <span>{{ formatTime(version.createdAt) }}</span>
            </div>
            <div class="version-meta">
              <div>
                <el-tag :type="version.status === 'DRAFT' ? 'warning' : 'info'">{{ versionStatusLabel(version) }}</el-tag>
                <el-tag effect="plain">{{ zh(version.sourceType) }}</el-tag>
              </div>
              <strong class="version-file-name" :title="version.sourceFileName || '未命名来源文件'">{{ version.sourceFileName || '未命名来源文件' }}</strong>
              <span class="version-lineage">{{ lineageLabel(version) }}</span>
              <span v-if="isBranchDraft(version)" class="version-branch-warning">不包含当前线上版本的后续变更，发布前请核对内容。</span>
            </div>
            <div class="version-row-actions">
              <template v-if="version.status === 'DRAFT'">
                <el-button type="primary" :icon="EditPen" :disabled="actionsLocked" :data-testid="`edit-${version.id}`" @click="editVersion(version)">继续编辑</el-button>
                <el-button
                  type="success"
                  plain
                  :icon="CircleCheckFilled"
                  :loading="isActiveAction(version.id, 'publish')"
                  :disabled="actionsLocked && !isActiveAction(version.id, 'publish')"
                  :data-testid="`publish-${version.id}`"
                  @click="publish(version)"
                >发布 v{{ version.versionNo }}</el-button>
                <el-dropdown trigger="click" :disabled="actionsLocked" @command="handleMoreCommand(version, $event)">
                  <el-button
                    :icon="MoreFilled"
                    :loading="isActiveAction(version.id, 'create') || isActiveAction(version.id, 'discard')"
                    :disabled="actionsLocked && !isActiveAction(version.id, 'create') && !isActiveAction(version.id, 'discard')"
                    :data-testid="`more-${version.id}`"
                  >更多</el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="create" :icon="Plus">基于 v{{ version.versionNo }} 创建修订</el-dropdown-item>
                      <el-dropdown-item command="discard" :icon="Delete" divided class="danger-command">丢弃草稿 v{{ version.versionNo }}</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </template>
              <el-button
                v-else
                :icon="Plus"
                :loading="isActiveAction(version.id, 'create')"
                :disabled="actionsLocked && !isActiveAction(version.id, 'create')"
                :data-testid="`create-${version.id}`"
                @click="createRevision(version)"
              >基于 v{{ version.versionNo }} 创建修订</el-button>
            </div>
          </article>
        </div>
      </template>
    </el-card>
  </section>
</template>
