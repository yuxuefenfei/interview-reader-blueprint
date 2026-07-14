<script setup lang="ts">
import { Plus, UploadFilled } from "@element-plus/icons-vue";
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { adminApi } from "../api/admin";
import { formatTime, zh } from "../shared/presentation";
import type { AdminDocumentSummary, VersionSummary } from "../types/api";

const router = useRouter();
const page = ref(1);
const documents = ref<AdminDocumentSummary[]>([]);
const selected = ref<AdminDocumentSummary | null>(null);
const versions = ref<VersionSummary[]>([]);
const drawer = ref(false);
const loading = ref(false);

onMounted(() => { void load(); });
async function load(): Promise<void> { loading.value = true; try { documents.value = (await adminApi.documents(page.value)).items; } catch (caught) { ElMessage.error(message(caught)); } finally { loading.value = false; } }
async function open(document: AdminDocumentSummary): Promise<void> { selected.value = document; drawer.value = true; try { versions.value = await adminApi.versions(document.id); } catch (caught) { ElMessage.error(message(caught)); } }
async function revision(version: VersionSummary): Promise<void> { if (!selected.value) return; try { const draft = await adminApi.createRevision(selected.value.id, version.id); ElMessage.success(`已创建修订草稿 v${draft.versionNo}`); await router.push(`/admin/versions/${draft.id}/edit`); } catch (caught) { ElMessage.error(message(caught)); } }
async function publish(version: VersionSummary): Promise<void> { if (!selected.value) return; try { await ElMessageBox.confirm(`发布 v${version.versionNo} 会替换当前阅读版本，并重置该文档的阅读位置。`, "确认发布", { type: "warning", confirmButtonText: "发布", cancelButtonText: "取消" }); await adminApi.publish(selected.value.id, version.id); ElMessage.success("版本已发布"); await open(selected.value); await load(); } catch (caught) { if (caught !== "cancel") ElMessage.error(message(caught)); } }
function message(value: unknown): string { return value instanceof Error ? value.message : "操作失败"; }
</script>

<template>
  <section class="admin-view">
    <header class="admin-view-header"><div><p class="eyebrow">内容资产</p><h1>文档管理</h1><span>管理发布版本、草稿修订与文档导入。</span></div><el-button type="primary" :icon="UploadFilled" @click="router.push('/admin/imports')">导入文档</el-button></header>
    <el-card shadow="never" class="admin-table-card"><el-table v-loading="loading" :data="documents" row-key="id"><el-table-column prop="title" label="文档" min-width="260"><template #default="{ row }"><div class="document-cell"><strong>{{ row.title }}</strong><span>{{ row.code }}</span></div></template></el-table-column><el-table-column label="状态" width="120"><template #default="{ row }"><el-tag :type="row.status === 'PUBLISHED' ? 'success' : 'info'">{{ zh(row.status) }}</el-tag></template></el-table-column><el-table-column label="版本" width="100"><template #default="{ row }">{{ row.versionCount }}</template></el-table-column><el-table-column label="草稿" width="100"><template #default="{ row }">{{ row.draftCount }}</template></el-table-column><el-table-column label="更新时间" width="190"><template #default="{ row }">{{ formatTime(row.updatedAt) }}</template></el-table-column><el-table-column label="操作" width="110" fixed="right"><template #default="{ row }"><el-button text type="primary" @click="open(row)">版本管理</el-button></template></el-table-column></el-table></el-card>
    <el-drawer v-model="drawer" size="min(680px, 92vw)" :title="selected?.title || '版本管理'"><div class="version-drawer-actions"><el-button :icon="UploadFilled" @click="router.push({ path: '/admin/imports', query: { targetDocumentId: selected?.id } })">重新导入</el-button></div><el-timeline><el-timeline-item v-for="version in versions" :key="version.id" :type="version.status === 'PUBLISHED' ? 'primary' : version.status === 'DRAFT' ? 'warning' : 'info'" :timestamp="formatTime(version.createdAt)"><div class="version-card"><div><strong>v{{ version.versionNo }}</strong><el-tag size="small">{{ zh(version.status) }}</el-tag><span>{{ zh(version.sourceType) }} · {{ version.sourceFileName || '未命名来源' }}</span></div><div class="version-actions"><el-button v-if="version.status === 'DRAFT'" size="small" type="primary" @click="router.push(`/admin/versions/${version.id}/edit`)">编辑</el-button><el-button v-if="version.status === 'DRAFT'" size="small" type="success" @click="publish(version)">发布</el-button><el-button size="small" :icon="Plus" @click="revision(version)">创建修订</el-button></div></div></el-timeline-item></el-timeline></el-drawer>
  </section>
</template>