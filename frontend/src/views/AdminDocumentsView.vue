<script setup lang="ts">
import { toUserMessage } from "../utils/errorMessage";
import { Search, UploadFilled, View } from "@element-plus/icons-vue";
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus/es/components/message/index";
import { adminApi } from "../api/admin";
import { formatTime, zh } from "../shared/presentation";
import type { AdminDocumentSummary } from "../types/api";

const router = useRouter();
const query = ref("");
const page = ref(1);
const pageSize = 20;
const documents = ref<AdminDocumentSummary[]>([]);
const hasNext = ref(false);
const loading = ref(false);

onMounted(() => { void load(); });
async function load(reset = false): Promise<void> {
  if (reset) page.value = 1;
  loading.value = true;
  try {
    const result = await adminApi.documents(query.value.trim(), page.value, pageSize);
    documents.value = result.items;
    hasNext.value = result.hasNext;
  } catch (caught) {
    ElMessage.error(message(caught));
  } finally {
    loading.value = false;
  }
}
function previous(): void { if (page.value > 1) { page.value--; void load(); } }
function next(): void { if (hasNext.value) { page.value++; void load(); } }
function open(document: AdminDocumentSummary): void { void router.push(`/admin/documents/${document.id}`); }
function message(value: unknown): string { return toUserMessage(value, "加载文档失败"); }
</script>

<template>
  <section class="admin-view">
    <header class="admin-view-header">
      <div><p class="eyebrow">内容资产</p><h1>文档管理</h1><span>查看文档状态，进入版本详情完成修订、发布和草稿清理。</span></div>
      <el-button type="primary" :icon="UploadFilled" @click="router.push('/admin/imports')">导入文档</el-button>
    </header>

    <el-card shadow="never" class="admin-table-card">
      <div class="table-toolbar">
        <el-input v-model="query" clearable placeholder="按文档名称或标识搜索" :prefix-icon="Search" @keyup.enter="load(true)" @clear="load(true)" />
        <el-button :icon="Search" @click="load(true)">搜索</el-button>
      </div>
      <el-table v-loading="loading" :data="documents" row-key="id" class="document-table" @row-click="open">
        <el-table-column prop="title" label="文档" min-width="300">
          <template #default="{ row }"><div class="document-cell"><strong>{{ row.title }}</strong><span>{{ row.code }}</span></div></template>
        </el-table-column>
        <el-table-column label="当前状态" width="160"><template #default="{ row }"><el-tag :type="row.status === 'PUBLISHED' ? 'success' : row.status === 'DELETE_FAILED' ? 'danger' : 'info'">{{ zh(row.status) }}</el-tag><small v-if="row.deletionJob" class="deletion-stage-label">{{ zh(row.deletionJob.currentStage) }}</small></template></el-table-column>
        <el-table-column label="版本" width="88"><template #default="{ row }">{{ row.versionCount }}</template></el-table-column>
        <el-table-column label="草稿" width="88"><template #default="{ row }"><el-tag v-if="row.draftCount" type="warning" effect="plain">{{ row.draftCount }}</el-tag><span v-else>-</span></template></el-table-column>
        <el-table-column label="最近更新" width="190"><template #default="{ row }">{{ formatTime(row.updatedAt) }}</template></el-table-column>
        <el-table-column label="操作" width="120" fixed="right"><template #default="{ row }"><el-button text type="primary" :icon="View" @click.stop="open(row)">查看详情</el-button></template></el-table-column>
      </el-table>
      <div class="table-pager"><span>第 {{ page }} 页</span><div><el-button :disabled="page === 1" @click="previous">上一页</el-button><el-button :disabled="!hasNext" @click="next">下一页</el-button></div></div>
    </el-card>
  </section>
</template>
