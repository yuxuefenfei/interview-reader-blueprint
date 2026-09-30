<script lang="ts" setup>
import {computed, onMounted, ref} from "vue";
import {RefreshRight, Search, Upload} from "@element-plus/icons-vue";
import {ElMessage} from "element-plus/es/components/message/index";
import {ElMessageBox} from "element-plus/es/components/message-box/index";
import {useRouter} from "vue-router";
import AdminPageHeader from "../components/AdminPageHeader.vue";
import {consoleApi, userMessage} from "../api";
import {formatBytes, formatTime, shortId} from "../format";
import {consoleState} from "../state";
import type {Release} from "../types";

const router = useRouter();
const query = ref("");
const page = ref(1);
const releases = ref<Release[]>([]);
const hasNext = ref(false);
const total = ref(0);
const loading = ref(false);
const deploying = ref<string | null>(null);
const eligible = computed(() => consoleState.dashboard.value?.health.eligible === true);
const blocked = computed(() => ["RUNNING", "NEEDS_OPERATOR"].includes(consoleState.dashboard.value?.latestOperation?.status ?? ""));
onMounted(() => {
  void load();
});

async function load(reset = false): Promise<void> {
  if (reset) page.value = 1;
  loading.value = true;
  try {
    const result = await consoleApi.releases(query.value.trim(), page.value);
    releases.value = result.items;
    hasNext.value = result.hasNext;
    total.value = result.total;
  } catch (caught) {
    ElMessage.error(userMessage(caught, "加载升级包失败"));
  } finally {
    loading.value = false;
  }
}

function previous(): void {
  if (page.value > 1) {
    page.value--;
    void load();
  }
}

function next(): void {
  if (hasNext.value) {
    page.value++;
    void load();
  }
}

async function deploy(release: Release): Promise<void> {
  try {
    await ElMessageBox.confirm(
        `确认升级至 Actions #${release.runId}（提交 ${shortId(release.commit)}）？控制台将停写、排空、备份、切换 JAR 并检查新版本。`,
        "确认开始升级",
        {type: "warning", confirmButtonText: "开始升级", cancelButtonText: "取消"}
    );
  } catch {
    return;
  }
  deploying.value = release.id;
  try {
    const operation = await consoleApi.deploy(release.id);
    ElMessage.success("升级已开始");
    await consoleState.refreshDashboard();
    await router.push(`/operations/${operation.id}`);
  } catch (caught) {
    ElMessage.error(userMessage(caught, "无法开始升级"));
  } finally {
    deploying.value = null;
  }
}

function records(release: Release): void {
  void router.push({path: "/operations", query: {releaseId: release.id}});
}
</script>

<template>
  <section class="admin-view">
    <AdminPageHeader description="所有条目均已与正式构建校验；在这里选择要发布的版本。" eyebrow="升级包"
                     title="升级包列表">
      <template #actions>
        <div class="document-header-tools ui-action-row">
          <el-input v-model="query" :prefix-icon="Search" class="document-header-search" clearable
                    name="release-search" placeholder="搜索提交或运行 ID…" @clear="load(true)"
                    @keyup.enter="load(true)"/>
          <el-tooltip content="刷新升级包" placement="bottom">
            <el-button :icon="RefreshRight" :loading="loading" aria-label="刷新升级包" circle @click="load(true)"/>
          </el-tooltip>
          <el-button :icon="Upload" type="primary" @click="router.push('/upload')">上传升级包</el-button>
        </div>
      </template>
    </AdminPageHeader>
    <div class="admin-content">
      <el-card class="admin-table-card" shadow="never">
        <el-table v-loading="loading" :data="releases" empty-text="暂无已验证的升级包" row-key="id">
          <el-table-column label="升级包" min-width="250">
            <template #default="{ row }">
              <div class="document-cell"><strong>Actions #{{ row.runId }}</strong><span>提交 {{
                  shortId(row.commit)
                }}</span></div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default>
              <el-tag effect="plain" type="success">已验证</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="大小" width="120">
            <template #default="{ row }">{{ formatBytes(row.bytes) }}</template>
          </el-table-column>
          <el-table-column label="验证时间" width="190">
            <template #default="{ row }">{{ formatTime(row.stagedAt) }}</template>
          </el-table-column>
          <el-table-column fixed="right" label="操作" width="250">
            <template #default="{ row }">
              <div class="ui-action-row ui-action-row--nowrap">
                <el-button text type="primary" @click.stop="records(row as Release)">操作记录</el-button>
                <el-button :disabled="!eligible || blocked" :loading="deploying === row.id" text type="primary"
                           @click.stop="deploy(row as Release)">开始升级
                </el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
        <div class="table-pager"><span>共 {{ total }} 个升级包 · 第 {{ page }} 页</span>
          <div class="ui-action-row ui-action-row--nowrap">
            <el-button :disabled="page === 1" @click="previous">上一页</el-button>
            <el-button :disabled="!hasNext" @click="next">下一页</el-button>
          </div>
        </div>
      </el-card>
      <p v-if="!eligible" class="table-help">
        主程序当前不满足升级条件时，“开始升级”按钮会停用。请先在控制台首页查看检查结果。</p>
    </div>
  </section>
</template>