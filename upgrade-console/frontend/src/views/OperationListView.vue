<script lang="ts" setup>
import {onMounted, ref, watch} from "vue";
import {RefreshRight, View} from "@element-plus/icons-vue";
import {ElMessage} from "element-plus/es/components/message/index";
import {useRoute, useRouter} from "vue-router";
import AdminPageHeader from "../components/AdminPageHeader.vue";
import {consoleApi, userMessage} from "../api";
import {formatTime, shortId, stageLabel, statusLabel, statusTag} from "../format";
import type {Operation, Release} from "../types";

const route = useRoute();
const router = useRouter();
const page = ref(1);
const operations = ref<Operation[]>([]);
const release = ref<Release | null>(null);
const total = ref(0);
const hasNext = ref(false);
const loading = ref(false);
const releaseId = () => typeof route.query.releaseId === "string" ? route.query.releaseId : undefined;

onMounted(() => {
  void load();
});
watch(() => route.query.releaseId, () => {
  page.value = 1;
  void load();
});

async function load(): Promise<void> {
  loading.value = true;
  try {
    const id = releaseId();
    const result = await consoleApi.operations(page.value, id);
    operations.value = result.items;
    total.value = result.total;
    hasNext.value = result.hasNext;
    release.value = id ? await consoleApi.release(id) : null;
  } catch (caught) {
    ElMessage.error(userMessage(caught, "加载操作记录失败"));
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

function open(row: Operation): void {
  void router.push(`/operations/${row.id}`);
}

function allOperations(): void {
  void router.push("/operations");
}
</script>

<template>
  <section class="admin-view">
    <AdminPageHeader :back-label="release ? '返回升级包列表' : ''"
                     :description="release ? `Actions #${release.runId} 的升级与恢复记录` : '查看全部升级、回滚与人工恢复操作。'"
                     eyebrow="升级包 / 历史"
                     title="操作记录" @back="router.push('/releases')">
      <template #meta><span v-if="release">Actions #{{ release.runId }}</span></template>
      <template #actions>
        <div class="ui-action-row">
          <el-button v-if="release" @click="allOperations">全部记录</el-button>
          <el-button :icon="RefreshRight" :loading="loading" @click="load">刷新</el-button>
        </div>
      </template>
    </AdminPageHeader>
    <div class="admin-content">
      <el-card class="admin-table-card" shadow="never">
        <el-table v-loading="loading" :data="operations" empty-text="暂无操作记录" row-key="id" @row-click="open">
          <el-table-column label="操作" min-width="190">
            <template #default="{ row }">
              <div class="document-cell"><strong>{{ shortId(row.id) }}</strong><span>升级包 {{
                  shortId(row.releaseId)
                }}</span></div>
            </template>
          </el-table-column>
          <el-table-column label="结果" width="150">
            <template #default="{ row }">
              <el-tag :type="statusTag(row.status)" effect="plain">{{ statusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="最近阶段" min-width="220">
            <template #default="{ row }">
              <div class="document-cell"><strong>{{ stageLabel(row.stage) }}</strong><span>{{ row.message }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="开始时间" width="190">
            <template #default="{ row }">{{ formatTime(row.startedAt) }}</template>
          </el-table-column>
          <el-table-column fixed="right" label="操作" width="130">
            <template #default="{ row }">
              <el-button :icon="View" text type="primary" @click.stop="open(row as Operation)">查看详情</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="table-pager"><span>共 {{ total }} 条记录 · 第 {{ page }} 页</span>
          <div class="ui-action-row ui-action-row--nowrap">
            <el-button :disabled="page === 1" @click="previous">上一页</el-button>
            <el-button :disabled="!hasNext" @click="next">下一页</el-button>
          </div>
        </div>
      </el-card>
    </div>
  </section>
</template>