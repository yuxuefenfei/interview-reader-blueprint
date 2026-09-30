<script lang="ts" setup>
import {ref} from "vue";
import {UploadFilled} from "@element-plus/icons-vue";
import type {UploadFile} from "element-plus";
import {ElMessage} from "element-plus/es/components/message/index";
import {useRouter} from "vue-router";
import AdminPageHeader from "../components/AdminPageHeader.vue";
import {consoleApi, userMessage} from "../api";
import {shortId} from "../format";

const router = useRouter();
const selectedFile = ref<File | null>(null);
const runIdText = ref("");
const uploading = ref(false);

function choose(file: UploadFile): void {
  selectedFile.value = file.raw ?? null;
}

function remove(): void {
  selectedFile.value = null;
}

function exceed(): void {
  ElMessage.warning("每次只能上传一个 JAR 文件");
}

async function upload(): Promise<void> {
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
  uploading.value = true;
  try {
    const release = await consoleApi.upload(selectedFile.value, runId);
    ElMessage.success(`升级包已验证：Actions #${release.runId} · ${shortId(release.commit)}`);
    await router.push("/releases");
  } catch (caught) {
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
          <el-button :disabled="!selectedFile" :loading="uploading" type="primary" @click="upload">上传并验证
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