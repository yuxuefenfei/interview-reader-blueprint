<script lang="ts" setup>
import {computed} from "vue";
import {RefreshRight, Right} from "@element-plus/icons-vue";
import {useRouter} from "vue-router";
import AdminPageHeader from "../components/AdminPageHeader.vue";
import OperationFeed from "../components/OperationFeed.vue";
import {formatBytes, formatTime, shortId, stageLabel, statusLabel, statusTag} from "../format";
import {consoleState} from "../state";

const router = useRouter();
const dashboard = consoleState.dashboard;
const health = computed(() => dashboard.value?.health);
const latest = computed(() => dashboard.value?.latestOperation);
const drain = computed(() => health.value?.drain);
const metrics = computed(() => [
  {label: "当前版本", value: shortId(drain.value?.releaseCommit)},
  {label: "活跃写入", value: String(drain.value?.activeWrites ?? "—")},
  {
    label: "后台任务",
    value: drain.value?.importJobs === undefined ? "—" : `${drain.value.importJobs} / ${drain.value.deletionJobs ?? "—"}`
  },
  {label: "JVM 内存", value: formatBytes(drain.value?.jvmUsedBytes)},
  {label: "数据库连接", value: String(drain.value?.dbConnectionsActive ?? "—")},
  {
    label: "HTTP 平均耗时",
    value: drain.value?.httpMeanMs === undefined ? "—" : `${Math.round(drain.value.httpMeanMs)} ms`
  }
]);

function status(value: string | undefined): string {
  return value === "UP" ? "正常" : value ? "不可用" : "未响应";
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
      <el-alert v-if="consoleState.error.value" :closable="false" :title="consoleState.error.value" show-icon
                type="error">
        <template #default>
          <el-button text type="primary" @click="consoleState.refreshDashboard()">重试</el-button>
        </template>
      </el-alert>
      <div v-loading="consoleState.loading.value" class="dashboard-stack">
        <section class="status-panel">
          <div class="status-summary">
            <div><span class="section-kicker">运行状态与升级依据</span><strong>主程序 {{
                status(health?.overall.status)
              }}</strong></div>
            <el-tag :type="health?.eligible ? 'success' : 'warning'" effect="plain">
              {{ health?.eligible ? "当前满足升级条件" : "当前暂不满足升级条件" }}
            </el-tag>
          </div>
          <div class="health-checks">
            <span>存活 <b :class="{ good: health?.liveness.status === 'UP' }">{{ status(health?.liveness.status) }}</b></span>
            <span>就绪 <b :class="{ good: health?.readiness.status === 'UP' }">{{
                status(health?.readiness.status)
              }}</b></span>
            <span>数据库 <b :class="{ good: health?.readiness.components?.db?.status === 'UP' }">{{
                status(health?.readiness.components?.db?.status)
              }}</b></span>
            <span>磁盘 <b :class="{ good: health?.readiness.components?.diskSpace?.status === 'UP' }">{{
                status(health?.readiness.components?.diskSpace?.status)
              }}</b></span>
            <span>写入 <b>{{
                drain?.maintenance === true ? "已关闭" : drain?.maintenance === false ? "开放" : "待确认"
              }}</b></span>
          </div>
          <p v-if="health?.blockers.length" class="status-blockers">{{ health.blockers.join(" · ") }}</p>
          <small class="status-checked">最近检查 {{ formatTime(health?.checkedAt) }}</small>
        </section>

        <section aria-label="运行指标" class="metrics-section">
          <div class="section-title"><h2>运行指标</h2><span>主程序实时采样</span></div>
          <div class="metrics-grid">
            <div v-for="metric in metrics" :key="metric.label" class="metric-card"><span>{{
                metric.label
              }}</span><strong>{{ metric.value }}</strong></div>
          </div>
        </section>

        <section class="latest-section">
          <div class="section-title"><h2>最近一次操作</h2>
            <el-button v-if="latest" :icon="Right" text type="primary" @click="router.push(`/operations/${latest.id}`)">
              查看完整记录
            </el-button>
          </div>
          <div v-if="latest" class="latest-summary">
            <div><span class="section-kicker">{{
                formatTime(latest.startedAt)
              }}</span><strong>{{ stageLabel(latest.stage) }}</strong>
              <p>{{ latest.message }}</p></div>
            <el-tag :type="statusTag(latest.status)" effect="plain">{{ statusLabel(latest.status) }}</el-tag>
          </div>
          <div v-if="latest" class="feed-surface">
            <OperationFeed :operation-id="latest.id" compact/>
          </div>
          <div v-else class="empty-state latest-empty">尚无升级操作。上传并验证升级包后，可从升级包列表发起升级。</div>
        </section>
      </div>
    </div>
  </section>
</template>