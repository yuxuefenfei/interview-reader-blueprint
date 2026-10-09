<script lang="ts" setup>
import {computed} from "vue";
import {RefreshRight, Right} from "@element-plus/icons-vue";
import {useRouter} from "vue-router";
import AdminPageHeader from "../components/AdminPageHeader.vue";
import OperationFeed from "../components/OperationFeed.vue";
import {formatBytes, formatTime, shortId, stageLabel, statusLabel, statusTag} from "../format";
import {consoleState, HEALTH_REFRESH_INTERVAL_MS} from "../state";

const router = useRouter();
const dashboard = consoleState.dashboard;
const health = computed(() => dashboard.value?.health);
const latest = computed(() => dashboard.value?.latestOperation);
const running = computed(() => latest.value?.status === "RUNNING");
const drain = computed(() => health.value?.drain);
// Keyed panels move without remounting the feed, preserving scroll positions on completion.
const panelOrder = computed(() => running.value ? ["latest", "metrics"] : ["metrics", "latest"]);
const restarting = computed(() => running.value && [
  "STOPPING", "SWITCHING", "STARTING", "READINESS", "ROLLING_BACK",
  "RESTORING_DB", "RESTORING_FILES", "STARTING_OLD", "RECOVERING"
].includes(latest.value?.stage ?? ""));
const upgradeCondition = computed(() => {
  if (running.value) return {type: "info" as const, label: "操作进行中，暂不可再次升级"};
  if (latest.value?.status === "NEEDS_OPERATOR") return {type: "danger" as const, label: "需先处理上次操作"};
  if (consoleState.error.value) return {type: "warning" as const, label: "数据更新异常，请刷新"};
  if (!health.value) return {type: "info" as const, label: "等待状态检查"};
  return health.value.eligible
      ? {type: "success" as const, label: "当前满足升级条件"}
      : {type: "warning" as const, label: "当前暂不满足升级条件"};
});
const metrics = computed(() => [
  {
    label: "进行中的写入", value: String(drain.value?.activeWrites ?? "—"),
    description: "主程序当前仍在处理的写入请求数量。"
  },
  {
    label: "后台任务",
    value: "导入 " + (drain.value?.importJobs ?? "—") + " · 删除 " + (drain.value?.deletionJobs ?? "—"),
    description: "导入和删除任务各自尚未结束的数量，包含等待与执行中的任务。", tasks: true
  },
  {
    label: "主程序 JVM 已用内存", value: formatBytes(drain.value?.jvmUsedBytes),
    description: "主程序 JVM 各内存池的已用内存之和，不包含升级控制台或其他进程。"
  },
  {
    label: "活跃数据库连接", value: String(drain.value?.dbConnectionsActive ?? "—"),
    description: "主程序数据库连接池当前正在使用的连接数量。"
  },
  {
    label: "HTTP 累计平均耗时",
    value: drain.value?.httpMeanMs === undefined ? "—" : Math.round(drain.value.httpMeanMs) + " ms",
    description: "自主程序启动以来，请求总耗时除以请求总数得到的累计平均值。"
  }
]);

function status(value: string | undefined): string {
  return value === "UP" ? "正常" : value ? "不可用" : "未响应";
}

function healthClass(value: string | undefined): string {
  return value === "UP" ? "good" : value ? "bad" : "";
}
</script>

<template>
  <section class="admin-view dashboard-view">
    <AdminPageHeader description="查看主程序状态，跟踪最近一次升级或恢复。" eyebrow="系统运维" title="系统升级">
      <template #actions>
        <el-button :icon="RefreshRight" :loading="consoleState.loading.value" @click="consoleState.refreshDashboard()">
          刷新状态
        </el-button>
        <el-button type="primary" @click="router.push('/upload')">上传升级包</el-button>
      </template>
    </AdminPageHeader>
    <div class="admin-content">
      <el-alert v-if="consoleState.error.value" :closable="false" :title="consoleState.error.value" show-icon type="error">
        <template #default>
          <el-button text type="primary" @click="consoleState.refreshDashboard()">重试</el-button>
        </template>
      </el-alert>
      <div v-loading="consoleState.loading.value" class="dashboard-stack">
        <section class="status-panel">
          <div class="status-summary">
            <div class="status-identity">
              <span class="section-kicker">主程序概况</span>
              <strong>主程序 {{ status(health?.overall.status) }}</strong>
            </div>
            <div class="status-build">当前构建 <code :title="drain?.releaseCommit">{{ shortId(drain?.releaseCommit) }}</code></div>
            <el-tag :type="upgradeCondition.type" class="upgrade-condition" effect="plain">
              {{ upgradeCondition.label }}
            </el-tag>
          </div>
          <div class="status-details">
            <div class="health-checks">
              <span>存活 <b :class="healthClass(health?.liveness.status)">{{ status(health?.liveness.status) }}</b></span>
              <span>就绪 <b :class="healthClass(health?.readiness.status)">{{ status(health?.readiness.status) }}</b></span>
              <span>数据库 <b :class="healthClass(health?.readiness.components?.db?.status)">{{
                status(health?.readiness.components?.db?.status)
              }}</b></span>
              <span>磁盘 <b :class="healthClass(health?.readiness.components?.diskSpace?.status)">{{
                status(health?.readiness.components?.diskSpace?.status)
              }}</b></span>
              <span>写入 <b :class="{ good: drain?.maintenance === false, paused: drain?.maintenance === true }">{{
                drain?.maintenance === true ? "已关闭" : drain?.maintenance === false ? "开放" : "待确认"
              }}</b></span>
            </div>
            <small class="status-checked">最近检查 {{ formatTime(health?.checkedAt) }}</small>
          </div>
          <p v-if="restarting" class="status-context">
            当前处于“{{ stageLabel(latest?.stage) }}”阶段，主程序在启停期间可能暂时未响应。
          </p>
          <p v-if="health?.blockers.length" class="status-blockers">{{ health.blockers.join(" · ") }}</p>
        </section>

        <template v-for="panel in panelOrder" :key="panel">
          <section v-if="panel === 'metrics'" aria-label="运行指标" class="metrics-section">
            <div class="section-title"><h2>运行指标</h2><span>主程序 · 每 {{ HEALTH_REFRESH_INTERVAL_MS / 1000 }} 秒更新</span></div>
            <div class="metrics-grid">
              <div v-for="metric in metrics" :key="metric.label" class="metric-card">
                <el-tooltip :content="metric.description" placement="top">
                  <span class="metric-label" tabindex="0">{{ metric.label }}</span>
                </el-tooltip>
                <strong :class="{ 'metric-tasks': metric.tasks }" :title="metric.value">{{ metric.value }}</strong>
              </div>
            </div>
          </section>

          <section v-else class="latest-section">
            <div class="section-title">
              <h2>{{ running ? "当前操作" : "最近一次操作" }}</h2>
              <el-button v-if="latest" :icon="Right" text type="primary" @click="router.push('/operations/' + latest.id)">
                查看完整记录
              </el-button>
            </div>
            <div v-if="latest" :class="'result-' + statusTag(latest.status)" class="latest-summary">
              <div class="latest-summary-main">
                <div class="latest-summary-title">
                  <strong>{{ running ? stageLabel(latest.stage) : statusLabel(latest.status) }}</strong>
                  <el-tag v-if="running" effect="plain" size="small" type="info">进行中</el-tag>
                </div>
                <p>{{ latest.message }}</p>
              </div>
              <div class="latest-summary-times">
                <span>开始 <time>{{ formatTime(latest.startedAt) }}</time></span>
                <span>更新 <time>{{ formatTime(latest.updatedAt) }}</time></span>
              </div>
            </div>
            <div v-if="latest" class="feed-surface">
              <OperationFeed :operation-id="latest.id" compact />
            </div>
            <div v-else class="empty-state latest-empty">尚无升级操作。上传并验证升级包后，可从升级包列表发起升级。</div>
          </section>
        </template>
      </div>
    </div>
  </section>
</template>
