<script setup lang="ts">
import { toUserMessage } from "../utils/errorMessage";
import { CircleCheck, DocumentAdd, Search, UploadFilled } from "@element-plus/icons-vue";
import type { UploadRawFile } from "element-plus";
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus/es/components/message/index";
import { adminApi } from "../api/admin";
import { importIssueMessage, zh } from "../shared/presentation";
import { IMPORT_POLL_BACKOFF_FACTOR, IMPORT_POLL_INITIAL_DELAY_MS, IMPORT_POLL_MAX_DELAY_MS } from "../shared/runtimePolicy";
import { importStageState, importStageSummary, processingStages } from "../utils/importProgress";
import { TERMINAL_IMPORT_STATUSES } from "../types/api";
import type { AdminDocumentSummary, ImportIssue, ImportJob } from "../types/api";

const route = useRoute();
const router = useRouter();
const documents = ref<AdminDocumentSummary[]>([]);
const targetDocumentId = ref<string>((route.query.targetDocumentId as string) || "");
const documentLoading = ref(false);
const selectedFile = ref<File | null>(null);
const job = ref<ImportJob | null>(null);
const issues = ref<ImportIssue[]>([]);
const uploading = ref(false);
let pollTimer: number | null = null;
let pollDelayMs = IMPORT_POLL_INITIAL_DELAY_MS;
let polling = false;

const recognizedType = computed(() => selectedFile.value ? inferSourceType(selectedFile.value.name) : null);
const jobStageSummary = computed(() => job.value ? importStageSummary(job.value.status, job.value.currentStage) : "");

onMounted(() => {
  document.addEventListener("visibilitychange", onVisibilityChange);
  void searchDocuments("");
});
onBeforeUnmount(() => {
  stopPolling();
  document.removeEventListener("visibilitychange", onVisibilityChange);
});
async function searchDocuments(query: string): Promise<void> {
  documentLoading.value = true;
  try { documents.value = (await adminApi.documents(query, 1, 30)).items.filter((document) => document.status !== "DELETING" && document.status !== "DELETE_FAILED"); }
  catch (caught) { ElMessage.error(message(caught)); }
  finally { documentLoading.value = false; }
}
function selectFile(file: File): boolean {
  selectedFile.value = file;
  job.value = null;
  issues.value = [];
  return false;
}
async function upload(): Promise<void> {
  if (!selectedFile.value) { ElMessage.warning("请选择导入文件"); return; }
  uploading.value = true;
  try {
    job.value = await adminApi.upload(selectedFile.value, targetDocumentId.value || undefined);
    issues.value = [];
    startPolling();
  } catch (caught) { ElMessage.error(message(caught)); }
  finally { uploading.value = false; }
}
function startPolling(): void {
  stopPolling();
  pollDelayMs = IMPORT_POLL_INITIAL_DELAY_MS;
  schedulePoll(0);
}
function stopPolling(): void {
  if (pollTimer !== null) {
    window.clearTimeout(pollTimer);
    pollTimer = null;
  }
}
function schedulePoll(delayMs: number): void {
  if (document.hidden || !job.value || TERMINAL_IMPORT_STATUSES.has(job.value.status)) return;
  pollTimer = window.setTimeout(() => {
    pollTimer = null;
    void refreshJob();
  }, delayMs);
}
async function refreshJob(): Promise<void> {
  if (!job.value || polling || document.hidden) return;
  polling = true;
  try {
    job.value = await adminApi.importJob(job.value.id);
    if (TERMINAL_IMPORT_STATUSES.has(job.value.status)) {
      stopPolling();
      issues.value = await adminApi.importIssues(job.value.id);
      return;
    }
    // 上一请求完成后再安排下一次，并逐步退避，慢网络下不会产生重叠请求。
    schedulePoll(pollDelayMs);
    pollDelayMs = Math.min(IMPORT_POLL_MAX_DELAY_MS, Math.ceil(pollDelayMs * IMPORT_POLL_BACKOFF_FACTOR));
  } catch (caught) {
    stopPolling();
    ElMessage.error(message(caught));
  } finally {
    polling = false;
  }
}
function onVisibilityChange(): void {
  if (document.hidden) {
    stopPolling();
    return;
  }
  if (job.value && !TERMINAL_IMPORT_STATUSES.has(job.value.status)) {
    pollDelayMs = IMPORT_POLL_INITIAL_DELAY_MS;
    schedulePoll(0);
  }
}
async function commit(): Promise<void> {
  if (!job.value) return;
  try {
    await adminApi.commitImport(job.value.id);
    const targetId = job.value.targetDocumentId || targetDocumentId.value;
    ElMessage.success("已生成版本草稿");
    await router.push(targetId ? `/admin/documents/${targetId}` : "/admin/documents");
  } catch (caught) { ElMessage.error(message(caught)); }
}
function issueMessage(issue: ImportIssue): string { return importIssueMessage(issue.issueCode, issue.sourcePage, issue.message); }
function inferSourceType(name: string): string {
  const suffix = name.split(".").pop()?.toLowerCase();
  return suffix === "pdf" ? "PDF" : suffix === "xlsx" ? "EXCEL" : suffix === "md" || suffix === "markdown" ? "MARKDOWN" : suffix === "json" ? "JSON_PACKAGE" : "UNKNOWN";
}
function message(value: unknown): string { return toUserMessage(value, "导入失败"); }
</script>

<template>
  <section class="admin-view import-view">
    <header class="admin-view-header"><div><p class="eyebrow">内容入库</p><h1>导入中心</h1><span>上传后自动识别文件类型，经过转换、校验后生成可编辑的版本草稿。</span></div></header>
    <div class="import-layout">
      <el-card shadow="never" class="import-form-card">
        <template #header><div class="card-heading"><div><strong>新建导入任务</strong><span>目标文档可选；留空时根据文档包信息创建或定位文档。</span></div></div></template>
        <el-form label-position="top">
          <el-form-item label="目标文档"><el-select v-model="targetDocumentId" filterable remote clearable reserve-keyword placeholder="搜索并选择目标文档" :remote-method="searchDocuments" :loading="documentLoading" :prefix-icon="Search"><el-option v-for="document in documents" :key="document.id" :label="`${document.title} · ${document.code}`" :value="document.id" /></el-select></el-form-item>
          <el-form-item label="源文件"><el-upload drag :auto-upload="false" :show-file-list="true" :limit="1" :on-change="(file: { raw?: UploadRawFile }) => file.raw && selectFile(file.raw)" :before-upload="selectFile"><el-icon class="upload-icon"><UploadFilled /></el-icon><div>拖入文件或点击选择</div><template #tip>支持 PDF、Excel、Markdown 和 JSON 文档包</template></el-upload></el-form-item>
          <div class="recognized-file"><span>识别类型</span><el-tag v-if="recognizedType" type="info" effect="plain">{{ zh(recognizedType) }}</el-tag><span v-else class="muted-text">选择文件后自动识别，服务端会再次按文件内容校验。</span></div>
          <el-button type="primary" :icon="DocumentAdd" :loading="uploading" :disabled="!selectedFile" @click="upload">开始导入</el-button>
        </el-form>
      </el-card>

      <el-card shadow="never" class="import-progress-card">
        <template #header><div class="card-heading"><div><strong>任务进度</strong><span>转换结果与校验问题会在任务完成后保留。</span></div></div></template>
        <div v-if="!job" class="empty-state">选择文件后，这里会显示导入阶段、进度和校验结果。</div>
        <template v-else>
          <div class="job-status">
            <div class="job-status-copy">
              <span class="job-status-label">当前状态</span>
              <strong>{{ zh(job.status) }}</strong>
              <span>{{ jobStageSummary }}</span>
            </div>
            <el-tag effect="plain">{{ zh(job.sourceType) }}</el-tag>
          </div>
          <div class="job-progress">
            <el-progress :percentage="job.progress" :status="job.status === 'FAILED' ? 'exception' : job.status === 'IMPORTED' ? 'success' : undefined" :stroke-width="10" :show-text="false" />
            <span>{{ job.progress }}%</span>
          </div>
          <ol class="job-stage-rail" aria-label="导入阶段">
            <li v-for="(stage, index) in processingStages" :key="stage.code" :class="importStageState(job.status, job.currentStage, index)">
              <span class="job-stage-marker">{{ index + 1 }}</span><span>{{ stage.label }}</span>
            </li>
          </ol>
          <p v-if="job.errorMessage" class="danger-text">{{ job.errorMessage }}</p>
          <div v-if="issues.length" class="issue-list"><el-alert v-for="issue in issues" :key="`${issue.issueCode}-${issue.blockKey}`" :title="issueMessage(issue)" :type="issue.severity === 'BLOCKING' ? 'error' : 'warning'" :closable="false" show-icon /></div>
          <div class="job-actions"><el-button v-if="job.status === 'READY'" type="success" :icon="CircleCheck" @click="commit">生成可编辑草稿</el-button><el-button v-if="job.status === 'REVIEW_REQUIRED'" type="warning" disabled>请处理校验问题后重试</el-button></div>
        </template>
      </el-card>
    </div>
  </section>
</template>
