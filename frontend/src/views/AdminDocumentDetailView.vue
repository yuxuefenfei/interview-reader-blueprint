<script setup lang="ts">
import { ArrowLeft, EditPen, Plus, UploadFilled, Delete, Promotion } from "@element-plus/icons-vue";
import { onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus/es/components/message/index";
import { ElMessageBox } from "element-plus/es/components/message-box/index";
import { adminApi } from "../api/admin";
import { formatTime, zh } from "../shared/presentation";
import type { AdminDocumentSummary, VersionSummary } from "../types/api";

const route = useRoute();
const router = useRouter();
const documentId = route.params.documentId as string;
const document = ref<AdminDocumentSummary | null>(null);
const versions = ref<VersionSummary[]>([]);
const loading = ref(false);
const actionVersion = ref<string | null>(null);

onMounted(() => { void load(); });
async function load(): Promise<void> {
  loading.value = true;
  try { [document.value, versions.value] = await Promise.all([adminApi.document(documentId), adminApi.versions(documentId)]); }
  catch (caught) { ElMessage.error(message(caught)); }
  finally { loading.value = false; }
}
function revisionSourceLabel(version: VersionSummary): string | null {
  const parentVersionNo = version.parentVersionNo
    ?? versions.value.find((candidate) => candidate.id === version.parentVersionId)?.versionNo
    ?? null;

  if (parentVersionNo === null) {
    return version.parentVersionId ? "基于已删除版本修订" : null;
  }

  const deletedSuffix = version.parentVersionId ? "" : "（源版本已删除）";
  return `基于 v${parentVersionNo} 修订${deletedSuffix}`;
}
async function createRevision(version: VersionSummary): Promise<void> {
  actionVersion.value = version.id;
  try {
    const draft = await adminApi.createRevision(documentId, version.id);
    ElMessage.success(`已基于 v${version.versionNo} 创建草稿 v${draft.versionNo}`);
    await router.push(`/admin/versions/${draft.id}/edit`);
  } catch (caught) { ElMessage.error(message(caught)); }
  finally { actionVersion.value = null; }
}
async function publish(version: VersionSummary): Promise<void> {
  try {
    await ElMessageBox.confirm(`发布 v${version.versionNo} 后将替换当前阅读版本。`, "确认发布", { type: "warning", confirmButtonText: "发布", cancelButtonText: "取消" });
    actionVersion.value = version.id;
    await adminApi.publish(documentId, version.id);
    ElMessage.success("版本已发布");
    await load();
  } catch (caught) { if (caught !== "cancel") ElMessage.error(message(caught)); }
  finally { actionVersion.value = null; }
}
async function discard(version: VersionSummary): Promise<void> {
  try {
    await ElMessageBox.confirm(`将永久丢弃草稿 v${version.versionNo}。关联的导入任务会保留，可再次提交生成草稿。`, "丢弃草稿", { type: "warning", confirmButtonText: "丢弃", cancelButtonText: "取消" });
    actionVersion.value = version.id;
    await adminApi.deleteDraft(version.id);
    ElMessage.success("草稿已丢弃");
    await load();
  } catch (caught) { if (caught !== "cancel") ElMessage.error(message(caught)); }
  finally { actionVersion.value = null; }
}
function message(value: unknown): string { return value instanceof Error ? value.message : "操作失败"; }
</script>

<template>
  <section class="admin-view document-detail-view" v-loading="loading">
    <header class="admin-view-header">
      <div class="detail-heading">
        <el-button text :icon="ArrowLeft" @click="router.push('/admin/documents')">返回文档管理</el-button>
        <p class="eyebrow">版本管理</p><h1>{{ document?.title || '文档详情' }}</h1>
        <span>{{ document?.code }} · 共 {{ document?.versionCount || 0 }} 个版本，{{ document?.draftCount || 0 }} 个草稿</span>
      </div>
      <el-button :icon="UploadFilled" @click="router.push({ path: '/admin/imports', query: { targetDocumentId: documentId } })">重新导入</el-button>
    </header>

    <el-card shadow="never" class="version-history-card">
      <template #header><div class="card-heading"><div><strong>版本历史</strong><span>一个文档始终只有一个已发布版本；任意版本均可派生新的修订草稿。</span></div></div></template>
      <el-empty v-if="!loading && !versions.length" description="当前文档还没有版本" />
      <div v-else class="version-history">
        <article v-for="version in versions" :key="version.id" class="version-row" :class="{ published: version.status === 'PUBLISHED' }">
          <div class="version-number"><strong>v{{ version.versionNo }}</strong><span>{{ formatTime(version.createdAt) }}</span></div>
          <div class="version-meta"><div><el-tag :type="version.status === 'PUBLISHED' ? 'success' : version.status === 'DRAFT' ? 'warning' : 'info'">{{ zh(version.status) }}</el-tag><el-tag effect="plain">{{ zh(version.sourceType) }}</el-tag></div><strong>{{ version.sourceFileName || '未命名来源文件' }}</strong><span v-if="revisionSourceLabel(version)">{{ revisionSourceLabel(version) }}</span></div>
          <div class="version-row-actions">
            <el-button :loading="actionVersion === version.id" :icon="Plus" @click="createRevision(version)">创建修订</el-button>
            <template v-if="version.status === 'DRAFT'"><el-button type="primary" :icon="EditPen" @click="router.push(`/admin/versions/${version.id}/edit`)">编辑</el-button><el-button type="success" :icon="Promotion" @click="publish(version)">发布</el-button><el-button type="danger" plain :icon="Delete" @click="discard(version)">丢弃</el-button></template>
          </div>
        </article>
      </div>
    </el-card>
  </section>
</template>
