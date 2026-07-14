<script setup lang="ts">
import { UploadFilled } from "@element-plus/icons-vue";
import type { UploadRawFile } from "element-plus";
import { onBeforeUnmount, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { adminApi } from "../api/admin";
import { zh } from "../shared/presentation";
import type { AdminDocumentSummary, ImportIssue, ImportJob, SourceType } from "../types/api";

const route = useRoute();
const router = useRouter();
const documents = ref<AdminDocumentSummary[]>([]);
const targetDocumentId = ref<string>((route.query.targetDocumentId as string) || "");
const sourceType = ref<SourceType>("PDF");
const selectedFile = ref<File | null>(null);
const job = ref<ImportJob | null>(null);
const issues = ref<ImportIssue[]>([]);
const uploading = ref(false);
let pollTimer: number | null = null;
const terminal = new Set(["READY", "REVIEW_REQUIRED", "IMPORTED", "FAILED", "CANCELED"]);

onMounted(async () => { documents.value = (await adminApi.documents()).items; });
onBeforeUnmount(stopPolling);
function selectFile(file: File): boolean { selectedFile.value = file; sourceType.value = inferSourceType(file.name); return false; }
async function upload(): Promise<void> { if (!selectedFile.value) { ElMessage.warning("请选择导入文件"); return; } uploading.value = true; try { job.value = await adminApi.upload(selectedFile.value, sourceType.value, targetDocumentId.value || undefined); issues.value = []; startPolling(); } catch (caught) { ElMessage.error(message(caught)); } finally { uploading.value = false; } }
function startPolling(): void { stopPolling(); void refreshJob(); pollTimer = window.setInterval(() => void refreshJob(), 900); }
function stopPolling(): void { if (pollTimer !== null) { window.clearInterval(pollTimer); pollTimer = null; } }
async function refreshJob(): Promise<void> { if (!job.value) return; try { job.value = await adminApi.importJob(job.value.id); if (terminal.has(job.value.status)) { stopPolling(); issues.value = await adminApi.importIssues(job.value.id); } } catch (caught) { stopPolling(); ElMessage.error(message(caught)); } }
async function commit(): Promise<void> { if (!job.value) return; try { await adminApi.commitImport(job.value.id); ElMessage.success("已生成版本草稿"); if (job.value.targetDocumentId) await router.push(`/admin/documents`); } catch (caught) { ElMessage.error(message(caught)); } }
function inferSourceType(name: string): SourceType { const suffix = name.split(".").pop()?.toLowerCase(); return suffix === "pdf" ? "PDF" : suffix === "xlsx" || suffix === "xls" ? "EXCEL" : suffix === "md" || suffix === "markdown" ? "MARKDOWN" : "JSON_PACKAGE"; }
function message(value: unknown): string { return value instanceof Error ? value.message : "导入失败"; }
</script>

<template>
  <section class="admin-view import-view"><header class="admin-view-header"><div><p class="eyebrow">内容入库</p><h1>导入中心</h1><span>统一处理 PDF、Excel、Markdown 与 JSON 文档包，并始终生成草稿。</span></div></header><div class="admin-two-column"><el-card shadow="never"><template #header>新建导入任务</template><el-form label-position="top"><el-form-item label="目标文档"><el-select v-model="targetDocumentId" clearable placeholder="留空则按文档标识创建或定位"><el-option v-for="document in documents" :key="document.id" :label="document.title" :value="document.id" /></el-select></el-form-item><el-form-item label="源文件"><el-upload drag :auto-upload="false" :show-file-list="true" :on-change="(file: { raw?: UploadRawFile }) => selectFile(file.raw!)" :before-upload="selectFile"><el-icon class="upload-icon"><UploadFilled /></el-icon><div>拖入文件或点击选择</div><template #tip>PDF、Excel、Markdown、JSON 文档包</template></el-upload></el-form-item><el-form-item label="识别类型"><el-radio-group v-model="sourceType"><el-radio-button label="PDF">PDF</el-radio-button><el-radio-button label="EXCEL">Excel</el-radio-button><el-radio-button label="MARKDOWN">Markdown</el-radio-button><el-radio-button label="JSON_PACKAGE">JSON</el-radio-button></el-radio-group></el-form-item><el-button type="primary" :loading="uploading" @click="upload">开始导入</el-button></el-form></el-card><el-card shadow="never" class="import-progress-card"><template #header>任务进度</template><div v-if="!job" class="empty-state">选择文件后，转换和校验状态会在这里实时展示。</div><template v-else><div class="job-status"><strong>{{ zh(job.status) }}</strong><span>{{ zh(job.currentStage) }}</span></div><el-progress :percentage="job.progress" :status="job.status === 'FAILED' ? 'exception' : job.status === 'IMPORTED' ? 'success' : undefined" :stroke-width="12" /><p v-if="job.errorMessage" class="danger-text">{{ job.errorMessage }}</p><el-alert v-for="issue in issues" :key="`${issue.issueCode}-${issue.blockKey}`" :title="issue.message" :type="issue.severity === 'BLOCKING' ? 'error' : 'warning'" :closable="false" show-icon /><el-button v-if="job.status === 'READY'" type="success" @click="commit">生成草稿</el-button><el-button v-if="job.status === 'REVIEW_REQUIRED'" type="warning" disabled>请在复核后提交</el-button></template></el-card></div></section>
</template>