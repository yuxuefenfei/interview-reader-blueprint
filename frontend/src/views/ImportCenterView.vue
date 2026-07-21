<script setup lang="ts">
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
import type { AdminDocumentSummary, ImportDocumentPreview, ImportIssue, ImportJob, ImportResolution } from "../types/api";
import { toUserMessage } from "../utils/errorMessage";

const route = useRoute();
const router = useRouter();
const documents = ref<AdminDocumentSummary[]>([]);
const targetDocumentId = ref<string>((route.query.targetDocumentId as string) || "");
const documentLoading = ref(false);
const selectedFile = ref<File | null>(null);
const job = ref<ImportJob | null>(null);
const issues = ref<ImportIssue[]>([]);
const preview = ref<ImportDocumentPreview | null>(null);
const metadataForm = ref({ title: "", description: "", tags: [] as string[] });
const resolution = ref<ImportResolution | "">("");
const uploading = ref(false);
const metadataSaving = ref(false);
const committing = ref(false);
let pollTimer: number | null = null;
let pollDelayMs = IMPORT_POLL_INITIAL_DELAY_MS;
let polling = false;

const recognizedType = computed(() => selectedFile.value ? inferSourceType(selectedFile.value.name) : null);
const jobStageSummary = computed(() => job.value ? importStageSummary(job.value.status, job.value.currentStage) : "");
const selectedTarget = computed(() => documents.value.find((document) => document.id === job.value?.targetDocumentId) ?? null);
const matchingDocumentLocked = computed(() => preview.value?.matchingDocument?.status === "DELETING" || preview.value?.matchingDocument?.status === "DELETE_FAILED");
const metadataEditable = computed(() => Boolean(preview.value?.editable) && resolution.value !== "IMPORT_AS_NEW_VERSION");
const fallbackTitle = computed(() => preview.value?.title === "Markdown Document" || preview.value?.title === "PDF Document");

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
  try {
    documents.value = (await adminApi.documents(query, 1, 30)).items
      .filter((document) => document.status !== "DELETING" && document.status !== "DELETE_FAILED");
  } catch (caught) { ElMessage.error(message(caught)); }
  finally { documentLoading.value = false; }
}
function resetTask(): void {
  stopPolling();
  job.value = null;
  issues.value = [];
  preview.value = null;
  resolution.value = "";
}
function selectFile(file: File): boolean {
  selectedFile.value = file;
  resetTask();
  return false;
}
async function upload(): Promise<void> {
  if (!selectedFile.value) { ElMessage.warning("请选择导入文件"); return; }
  uploading.value = true;
  try {
    job.value = await adminApi.upload(selectedFile.value, targetDocumentId.value || undefined);
    issues.value = [];
    preview.value = null;
    if (TERMINAL_IMPORT_STATUSES.has(job.value.status)) await loadTerminalDetails();
    else startPolling();
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
      await loadTerminalDetails();
      return;
    }
    schedulePoll(pollDelayMs);
    pollDelayMs = Math.min(IMPORT_POLL_MAX_DELAY_MS, Math.ceil(pollDelayMs * IMPORT_POLL_BACKOFF_FACTOR));
  } catch (caught) {
    stopPolling();
    ElMessage.error(message(caught));
  } finally { polling = false; }
}
async function loadTerminalDetails(): Promise<void> {
  const current = job.value;
  if (!current) return;
  issues.value = await adminApi.importIssues(current.id);
  if (!(["READY", "REVIEW_REQUIRED", "IMPORTED"] as const).includes(current.status as "READY" | "REVIEW_REQUIRED" | "IMPORTED")) return;
  preview.value = await adminApi.importDocumentMetadata(current.id);
  metadataForm.value = {
    title: preview.value.title,
    description: preview.value.description ?? "",
    tags: [...preview.value.tags]
  };
  resolution.value = preview.value.matchingDocument ? "" : "CREATE_NEW";
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
function normalizedTags(values: string[]): string[] {
  const unique = new Map<string, string>();
  for (const value of values) {
    const display = value.trim();
    if (display) unique.set(display.toLocaleLowerCase(), display);
  }
  return [...unique.values()];
}
function validatedMetadata(): { title: string; description: string | null; tags: string[] } | null {
  const title = metadataForm.value.title.trim();
  const description = metadataForm.value.description.trim();
  const tags = normalizedTags(metadataForm.value.tags);
  if (!title) { ElMessage.warning("文档标题不能为空"); return null; }
  if (title.length > 500) { ElMessage.warning("文档标题不能超过 500 个字符"); return null; }
  if (description.length > 5000) { ElMessage.warning("文档描述不能超过 5000 个字符"); return null; }
  if (tags.length > 20 || tags.some((tag) => tag.length > 50)) {
    ElMessage.warning("最多设置 20 个标签，单个标签不能超过 50 个字符");
    return null;
  }
  return { title, description: description || null, tags };
}
async function saveStagedMetadata(showSuccess = true): Promise<boolean> {
  if (!job.value || !metadataEditable.value || metadataSaving.value) return !metadataEditable.value;
  const payload = validatedMetadata();
  if (!payload) return false;
  metadataSaving.value = true;
  try {
    preview.value = await adminApi.updateImportDocumentMetadata(job.value.id, payload);
    metadataForm.value = {
      title: preview.value.title,
      description: preview.value.description ?? "",
      tags: [...preview.value.tags]
    };
    if (showSuccess) ElMessage.success("待导入文档资料已保存");
    return true;
  } catch (caught) {
    ElMessage.error(message(caught));
    return false;
  } finally { metadataSaving.value = false; }
}
async function commit(): Promise<void> {
  const currentJob = job.value;
  const currentPreview = preview.value;
  if (!currentJob || !currentPreview || committing.value) return;
  if (currentPreview.matchingDocument && !resolution.value) {
    ElMessage.warning("标识已匹配已有文档，请明确选择导入方式");
    return;
  }
  if (metadataEditable.value && !(await saveStagedMetadata(false))) return;
  if (resolution.value === "IMPORT_AS_NEW_VERSION" && matchingDocumentLocked.value) {
    ElMessage.warning("匹配文档正在永久删除，不能导入新版本");
    return;
  }
  committing.value = true;
  try {
    const result = await adminApi.commitImport(
      currentJob.id,
      currentJob.targetDocumentId ? undefined : (resolution.value || "CREATE_NEW")
    );
    ElMessage.success("已生成版本草稿");
    await router.push(`/admin/documents/${result.documentId}`);
  } catch (caught) { ElMessage.error(message(caught)); }
  finally { committing.value = false; }
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
        <template #header><div class="card-heading"><div><strong>新建导入任务</strong><span>明确选择目标文档时生成新版本；留空时创建新文档，不再静默合并。</span></div></div></template>
        <el-form label-position="top">
          <el-form-item label="目标文档"><el-select v-model="targetDocumentId" filterable remote clearable reserve-keyword placeholder="留空则创建新文档" :remote-method="searchDocuments" :loading="documentLoading" :prefix-icon="Search" :disabled="uploading || job !== null"><el-option v-for="candidate in documents" :key="candidate.id" :label="`${candidate.title} · ${candidate.code}`" :value="candidate.id" /></el-select></el-form-item>
          <el-form-item label="源文件"><el-upload drag :auto-upload="false" :show-file-list="true" :limit="1" :on-change="(file: { raw?: UploadRawFile }) => file.raw && selectFile(file.raw)" :before-upload="selectFile"><el-icon class="upload-icon"><UploadFilled /></el-icon><div>拖入文件或点击选择</div><template #tip>支持 PDF、Excel、Markdown 和 JSON 文档包</template></el-upload></el-form-item>
          <div class="recognized-file"><span>识别类型</span><el-tag v-if="recognizedType" type="info" effect="plain">{{ zh(recognizedType) }}</el-tag><span v-else class="muted-text">选择文件后自动识别，服务端会再次按文件内容校验。</span></div>
          <el-button type="primary" :icon="DocumentAdd" :loading="uploading" :disabled="!selectedFile" data-testid="start-import" @click="upload">开始导入</el-button>
        </el-form>
      </el-card>

      <el-card shadow="never" class="import-progress-card">
        <template #header><div class="card-heading"><div><strong>任务进度</strong><span>转换结果与校验问题会在任务完成后保留。</span></div></div></template>
        <div v-if="!job" class="empty-state">选择文件后，这里会显示导入阶段、进度和校验结果。</div>
        <template v-else>
          <div class="job-status"><div class="job-status-copy"><span class="job-status-label">当前状态</span><strong>{{ zh(job.status) }}</strong><span>{{ jobStageSummary }}</span></div><el-tag effect="plain">{{ zh(job.sourceType) }}</el-tag></div>
          <div class="job-progress"><el-progress :percentage="job.progress" :status="job.status === 'FAILED' ? 'exception' : job.status === 'IMPORTED' ? 'success' : undefined" :stroke-width="10" :show-text="false" /><span>{{ job.progress }}%</span></div>
          <ol class="job-stage-rail" aria-label="导入阶段"><li v-for="(stage, index) in processingStages" :key="stage.code" :class="importStageState(job.status, job.currentStage, index)"><span class="job-stage-marker">{{ index + 1 }}</span><span>{{ stage.label }}</span></li></ol>
          <p v-if="job.errorMessage" class="danger-text">{{ job.errorMessage }}</p>
          <div v-if="issues.length" class="issue-list"><el-alert v-for="issue in issues" :key="`${issue.issueCode}-${issue.blockKey}`" :title="issueMessage(issue)" :type="issue.severity === 'BLOCKING' ? 'error' : 'warning'" :closable="false" show-icon /></div>

          <section v-if="preview" class="import-document-preview" aria-labelledby="import-document-heading">
            <div class="import-document-preview-heading"><div><strong id="import-document-heading">{{ metadataEditable ? '新文档资料' : '来源文档资料' }}</strong><span>{{ metadataEditable ? '提交前可校正显示资料，标识创建后保持只读。' : '本次只导入内容版本，不会覆盖目标文档资料。' }}</span></div><el-tag :type="metadataEditable ? 'success' : 'info'" effect="plain">{{ metadataEditable ? '可编辑' : '仅参考' }}</el-tag></div>
            <el-alert v-if="selectedTarget" :title="`目标文档：${selectedTarget.title} · ${selectedTarget.code}`" type="info" :closable="false" show-icon description="来源标题、描述和标签不会写入目标文档。" />
            <el-form label-position="top" class="import-metadata-form">
              <el-form-item label="标题" required><el-input v-model="metadataForm.title" maxlength="500" show-word-limit :disabled="!metadataEditable" /></el-form-item>
              <el-form-item label="只读标识"><el-input :model-value="resolution === 'CREATE_NEW' ? preview.suggestedDocumentKey || preview.documentKey : preview.documentKey" disabled /></el-form-item>
              <el-form-item label="描述"><el-input v-model="metadataForm.description" type="textarea" :rows="3" maxlength="5000" show-word-limit :disabled="!metadataEditable" /></el-form-item>
              <el-form-item label="标签"><el-select v-model="metadataForm.tags" multiple filterable allow-create default-first-option :multiple-limit="20" :disabled="!metadataEditable" placeholder="输入标签后按回车"><el-option v-for="tag in metadataForm.tags" :key="tag" :label="tag" :value="tag" /></el-select></el-form-item>
            </el-form>
            <el-alert v-if="fallbackTitle && metadataEditable" title="未能从文档内容或文件名识别有效标题，请确认后再提交。" type="warning" :closable="false" show-icon />
            <el-alert v-if="preview.duplicateTitleCount > 0 && metadataEditable" :title="`已有 ${preview.duplicateTitleCount} 个同名文档；允许继续创建，请结合只读标识区分。`" type="warning" :closable="false" show-icon />
            <div v-if="preview.matchingDocument && preview.editable" class="import-resolution-panel">
              <el-alert :title="`标识已匹配：${preview.matchingDocument.title} · ${preview.matchingDocument.code}`" type="warning" :closable="false" show-icon description="系统不会静默合并，请明确选择本次导入方式。" />
              <el-radio-group v-model="resolution">
                <el-radio value="IMPORT_AS_NEW_VERSION" :disabled="matchingDocumentLocked">导入为匹配文档的新版本</el-radio>
                <el-radio value="CREATE_NEW">创建新文档（标识：{{ preview.suggestedDocumentKey }}）</el-radio>
              </el-radio-group>
            </div>
            <div v-if="metadataEditable" class="import-metadata-actions"><el-button :loading="metadataSaving" data-testid="save-import-metadata" @click="saveStagedMetadata">保存资料</el-button></div>
          </section>

          <div class="job-actions"><el-button v-if="job.status === 'READY'" type="success" :icon="CircleCheck" :loading="committing" :disabled="!preview" data-testid="commit-import" @click="commit">生成可编辑草稿</el-button><el-button v-if="job.status === 'REVIEW_REQUIRED'" type="warning" disabled>请处理校验问题后重试</el-button></div>
        </template>
      </el-card>
    </div>
  </section>
</template>