<script lang="ts" setup>
import {ArrowLeft, ArrowRight, Box, DocumentCopy, Monitor, UploadFilled} from "@element-plus/icons-vue";
import {computed, ref, watch} from "vue";
import {useRoute} from "vue-router";
import {consoleState} from "../state";

const route = useRoute();
const collapsed = ref(localStorage.getItem("upgrade.sidebar.collapsed") === "true");
const operationsActive = computed(() => route.path.startsWith("/operations"));
const connectionLabel = computed(() => ({
  connecting: "实时日志连接中", live: "实时日志已连接", reconnecting: "实时日志正在重连"
}[consoleState.streamStatus.value]));
watch(collapsed, (value) => localStorage.setItem("upgrade.sidebar.collapsed", String(value)));
</script>

<template>
  <div :class="{ 'sidebar-collapsed': collapsed }" class="admin-layout">
    <aside class="admin-sidebar">
      <router-link class="admin-brand" title="升级控制台首页" to="/">
        <img alt="" class="brand-mark" height="36" src="/admin-brand-icon.svg" width="36" />
        <span>Interview Reader<small>升级控制台</small></span>
      </router-link>
      <nav aria-label="升级控制台菜单">
        <span class="admin-nav-label">管理工作台</span>
        <el-tooltip :disabled="!collapsed" content="控制台首页" placement="right">
          <router-link aria-label="控制台首页" exact-active-class="router-link-active" to="/">
            <el-icon>
              <Monitor />
            </el-icon>
            <span>控制台首页</span></router-link>
        </el-tooltip>
        <el-tooltip :disabled="!collapsed" content="上传升级包" placement="right">
          <router-link active-class="router-link-active" aria-label="上传升级包" to="/upload">
            <el-icon>
              <UploadFilled />
            </el-icon>
            <span>上传升级包</span></router-link>
        </el-tooltip>
        <el-tooltip :disabled="!collapsed" content="升级包列表" placement="right">
          <router-link active-class="router-link-active" aria-label="升级包列表" to="/releases">
            <el-icon>
              <Box />
            </el-icon>
            <span>升级包列表</span></router-link>
        </el-tooltip>
        <el-tooltip :disabled="!collapsed" content="操作记录" placement="right">
          <router-link
            :class="{ 'router-link-active': operationsActive }" aria-label="操作记录" class="admin-nav-child"
            to="/operations"
          >
            <el-icon>
              <DocumentCopy />
            </el-icon>
            <span>操作记录</span></router-link>
        </el-tooltip>
      </nav>
      <div class="admin-sidebar-foot">
        <span :class="consoleState.streamStatus.value" class="connection-indicator">{{
          connectionLabel
        }}</span>
      </div>
      <button
        :aria-label="collapsed ? '展开管理侧栏' : '折叠管理侧栏'" class="admin-sidebar-toggle" type="button"
        @click="collapsed = !collapsed"
      >
        <el-icon>
          <ArrowRight v-if="collapsed" />
          <ArrowLeft v-else />
        </el-icon>
        <span>{{ collapsed ? "展开导航" : "收起导航" }}</span>
      </button>
    </aside>
    <main aria-label="升级管理工作区" class="admin-main">
      <router-view />
    </main>
  </div>
</template>