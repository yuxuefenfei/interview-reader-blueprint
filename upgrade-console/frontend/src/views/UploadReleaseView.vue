<script lang="ts" setup>
import {computed, ref} from "vue";
import {UploadFilled} from "@element-plus/icons-vue";
import type {UploadFile} from "element-plus";
import {ElMessage} from "element-plus/es/components/message/index";
import {useRouter} from "vue-router";
import AdminPageHeader from "../components/AdminPageHeader.vue";
import {consoleApi, userMessage, type UploadProgress} from "../api";
import {formatBytes, shortId} from "../format";

const router = useRouter();
const selectedFile = ref<File | null>(null);
const runIdText = ref("");
const uploading = ref(false);
type UploadPhase = UploadProgress["phase"] | "idle" | "failed" | "succeeded";
const uploadPhase = ref<UploadPhase>("idle");
const transfer = ref<UploadProgress>({phase: "uploading", loaded: 0, total: null});
const phaseLabels: Record<UploadPhase, string> = {
  idle: "", uploading: "正在上传升级包", verifying: "正在校验升级包",
  failed: "上传或校验失败，可重试", succeeded: "升级包已验证"
};
const percentage = computed(() => {
  if (transfer.value.phase === "verifying" || uploadPhase.value === "succeeded") return 100;
  const {loaded, total} = transfer.value;
  // Reserve 100% for upload completion rather than rounding an in-flight transfer up.
  return total && total > 0 ? Math.min(99, Math.floor(loaded / total * 100)) : 0;
});

function resetProgress(): void {
  uploadPhase.value = "idle";
  transfer.value = {phase: "uploading", loaded: 0, total: selectedFile.value?.size ?? null};
}

function choose(file: UploadFile): void {
  selectedFile.value = file.raw ?? null;
  resetProgress();
}

function remove(): void {
  selectedFile.value = null;
  resetProgress();
}

function exceed(): void {
  ElMessage.warning("每次只能上传一个 JAR 文件");
}

async function upload(): Promise<void> {
  if (uploading.value) return;
  const runId = Number(runIdText.value);
  if (!selectedFile.value) {
    ElMessage.warning("请选择正式构建的 JAR 文件");
    return;
  }
  if (!Number.isSafeInteger(runId) || runId <= 0) {
    ElMessage.warning("请输入有效的 GitHub Actions 运行 ID");
    return;
  }
  if (!selectedFile.value.name.toLowerCase().endsWith(".jar")) {
    ElMessage.warning("只接受 JAR 文件");
    return;
  }
  if (selectedFile.value.size > 150_000_000) {
    ElMessage.warning("JAR 文件不能超过 150 MB");
    return;
  }
  resetProgress();
  uploading.value = true;
  uploadPhase.value = "uploading";
  try {
    const release = await consoleApi.upload(selectedFile.value, runId, progress => {
      transfer.value = progress;
      uploadPhase.value = progress.phase;
    });
    uploadPhase.value = "succeeded";
    ElMessage.success(`升级包已验证：Actions #${release.runId} · ${shortId(release.commit)}`);
    await router.push("/releases");
  } catch (caught) {
    uploadPhase.value = "failed";
    ElMessage.error(userMessage(caught, "上传验证失败"));
  } finally {
    uploading.value = false;
  }
}
</script>

<template>
  <section class="admin-view">
    <AdminPageHeader description="上传 main 分支 verify 构建的正式 JAR，并与 GitHub Actions 校验产物核对。"
                     eyebrow="升级包"
                     title="上传升级包">
      <template #actions>
        <el-button @click="router.push('/releases')">查看升级包列表</el-button>
      </template>
    </AdminPageHeader>
    <div class="admin-content upload-layout">
      <el-card class="upload-card" shadow="never">
        <template #header>
          <div class="card-heading"><strong>上传并验证</strong><span>验证完成后，升级包会出现在列表中。</span></div>
        </template>
        <el-form label-position="top">
          <el-form-item label="GitHub Actions 运行 ID" required>
            <el-input v-model="runIdText" :disabled="uploading" inputmode="numeric" min="1" name="run-id"
                      placeholder="例如 123456789" type="number"/>
          </el-form-item>
          <el-form-item label="正式 JAR 文件" required>
            <el-upload :auto-upload="false" :disabled="uploading" :limit="1" :on-change="choose" :on-exceed="exceed"
                       :on-remove="remove"
                       :show-file-list="true" accept=".jar" class="jar-upload" drag>
              <el-icon class="upload-icon">
                <UploadFilled/>
              </el-icon>
              <div>拖入 JAR 文件或点击选择</div>
              <template #tip>
                <div class="el-upload__tip">文件大小上限 150 MB；服务端会再次验证 JAR、构建来源和 SHA-256。</div>
              </template>
            </el-upload>
          </el-form-item>
          <div
            v-if="uploadPhase !== 'idle'"
            class="upload-progress"
            :class="{'is-failed': uploadPhase === 'failed'}"
            aria-label="升级包上传状态"
          >
            <div class="upload-progress-meta">
              <span class="upload-progress-state" role="status" aria-live="polite">{{ phaseLabels[uploadPhase] }}</span>
              <span class="upload-progress-size">
                已传 {{ formatBytes(transfer.loaded) }}<template v-if="transfer.total !== null">
                  / {{ formatBytes(transfer.total) }} · {{ percentage }}%
                </template>
              </span>
            </div>
            <el-progress
              :percentage="percentage"
              :stroke-width="8"
              :show-text="false"
              :indeterminate="uploadPhase === 'verifying' || (uploading && transfer.total === null)"
              :status="uploadPhase === 'failed' ? 'exception' : uploadPhase === 'succeeded' ? 'success' : ''"
              aria-label="升级包上传进度"
            />
          </div>
          <el-button :disabled="!selectedFile" :loading="uploading" type="primary" @click="upload">
            {{ uploadPhase === 'verifying' ? '正在校验' : '上传并验证' }}
          </el-button>
        </el-form>
      </el-card>
      <aside class="upload-guide">
        <span class="section-kicker">验证依据</span>
        <h2>与正式构建逐项核对</h2>
        <p>控制台会确认 Actions 运行来自 main 分支的 verify 工作流、构建已成功完成，且上传文件与发布校验值一致。</p>
        <p>验证成功只会保存升级包。主程序需要在升级包列表中由管理员明确发起升级。</p>
      </aside>
    </div>
  </section>
</template>