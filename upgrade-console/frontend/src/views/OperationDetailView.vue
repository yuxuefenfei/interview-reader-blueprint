<script lang="ts" setup>
import {computed, onMounted, ref, watch} from "vue";
import {RefreshRight} from "@element-plus/icons-vue";
import {ElMessage} from "element-plus/es/components/message/index";
import {ElMessageBox} from "element-plus/es/components/message-box/index";
import {useRoute, useRouter} from "vue-router";
import AdminPageHeader from "../components/AdminPageHeader.vue";
import OperationFeed from "../components/OperationFeed.vue";
import {consoleApi, userMessage} from "../api";
import {consoleState} from "../state";
import {formatTime, shortId, stageLabel, statusLabel, statusTag} from "../format";

const route = useRoute();
const router = useRouter();
const id = computed(() => String(route.params.id ?? ""));
const operation = computed(() => consoleState.operationDetails.get(id.value));
const loading = ref(false);
const acting = ref(false);
const blocked = computed(() => ["RUNNING", "NEEDS_OPERATOR"].includes(consoleState.dashboard.value?.latestOperation?.status ?? ""));

onMounted(() => {
  void load();
});
watch(id, () => {
  void load();
});

async function load(): Promise<void> {
  loading.value = true;
  try {
    await consoleState.loadOperation(id.value);
  } catch (caught) {
    ElMessage.error(userMessage(caught, "加载操作详情失败"));
  } finally {
    loading.value = false;
  }
}

async function restorePublished(): Promise<void> {
  const current = operation.value;
  if (!current?.backupId) return;
  try {
    await ElMessageBox.confirm(
        `确认恢复备份 ${current.backupId}？该备份之后的数据库和文件写入可能丢失。控制台会先备份当前状态。`,
        "确认人工恢复", {type: "warning", confirmButtonText: "确认恢复", cancelButtonText: "取消"}
    );
  } catch {
    return;
  }
  acting.value = true;
  try {
    const started = await consoleApi.restorePublished(current.backupId);
    ElMessage.success("人工恢复已开始");
    await consoleState.refreshDashboard();
    await router.push(`/operations/${started.id}`);
  } catch (caught) {
    ElMessage.error(userMessage(caught, "无法开始恢复"));
  } finally {
    acting.value = false;
  }
}

async function resolveInterrupted(): Promise<void> {
  const current = operation.value;
  if (!current) return;
  const withBackup = Boolean(current.backupId);
  try {
    await ElMessageBox.confirm(
        withBackup
            ? `确认用同批备份 ${current.backupId} 恢复数据库、数据文件和旧 JAR？当前状态会被覆盖。`
            : "确认检查旧版健康与任务排空，并在条件满足时恢复写入？",
        withBackup ? "确认恢复同批备份" : "确认恢复旧版写入",
        {type: "warning", confirmButtonText: "继续", cancelButtonText: "取消"}
    );
  } catch {
    return;
  }
  acting.value = true;
  try {
    if (current.backupId) await consoleApi.recover(current.id, current.backupId);
    else await consoleApi.abortInterrupted(current.id);
    ElMessage.success("恢复操作已开始");
    await consoleState.loadOperation(current.id);
    await consoleState.refreshDashboard();
  } catch (caught) {
    ElMessage.error(userMessage(caught, "恢复操作失败"));
  } finally {
    acting.value = false;
  }
}
</script>

<template>
  <section class="admin-view">
    <AdminPageHeader
        :description="operation ? `操作 ${shortId(operation.id)} · 开始于 ${formatTime(operation.startedAt)}` : '查看升级阶段与命令日志'"
        back-label="返回操作记录"
        eyebrow="升级包 / 操作记录"
        title="操作详情" @back="router.push('/operations')">
      <template #status>
        <el-tag v-if="operation" :type="statusTag(operation.status)" effect="plain">{{
            statusLabel(operation.status)
          }}
        </el-tag>
      </template>
      <template #actions>
        <el-button :icon="RefreshRight" :loading="loading" @click="load">刷新</el-button>
      </template>
    </AdminPageHeader>
    <div class="admin-content operation-detail">
      <el-card v-if="operation" class="operation-summary-card" shadow="never">
        <div class="operation-summary-grid">
          <div><span>当前阶段</span><strong>{{ stageLabel(operation.stage) }}</strong><small>{{
              operation.message
            }}</small></div>
          <div><span>升级包 ID</span><strong class="mono">{{ operation.releaseId }}</strong></div>
          <div><span>备份 ID</span><strong class="mono">{{ operation.backupId || "尚未生成" }}</strong></div>
          <div><span>最近更新</span><strong>{{ formatTime(operation.updatedAt) }}</strong></div>
        </div>
        <div v-if="operation.status === 'SUCCEEDED' && operation.backupId || operation.status === 'NEEDS_OPERATOR'"
             class="operation-actions ui-action-row">
          <el-button v-if="operation.status === 'SUCCEEDED' && operation.backupId" :disabled="blocked" :loading="acting"
                     plain type="warning" @click="restorePublished">恢复发布前备份
          </el-button>
          <el-button v-if="operation.status === 'NEEDS_OPERATOR'" :loading="acting" type="warning"
                     @click="resolveInterrupted">{{ operation.backupId ? "恢复同批备份" : "检查旧版并恢复写入" }}
          </el-button>
        </div>
      </el-card>
      <el-card v-if="operation" class="feed-card" shadow="never">
        <OperationFeed :operation-id="operation.id"/>
      </el-card>
      <div v-else-if="!loading" class="empty-state">操作不存在或暂时无法读取。</div>
    </div>
  </section>
</template>