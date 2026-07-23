<script setup lang="ts">
import { ArrowLeft, ArrowRight, Document, Upload, Reading, SwitchButton } from "@element-plus/icons-vue";
import { ref, watch } from "vue";
import { useRouter } from "vue-router";
import { BRAND_ICON_URL } from "../shared/branding";

defineProps<{ username?: string | null }>();
const emit = defineEmits<{ logout: [] }>();
const router = useRouter();
const collapsedPreference = localStorage.getItem("admin.sidebar.collapsed");
const collapsed = ref(collapsedPreference === null ? false : collapsedPreference === "true");

watch(collapsed, (value) => localStorage.setItem("admin.sidebar.collapsed", String(value)));
</script>

<template>
  <div class="admin-layout" :class="{ 'sidebar-collapsed': collapsed }">
    <aside class="admin-sidebar">
      <button class="admin-brand" type="button" title="返回阅读器" @click="router.push('/reader')">
        <img class="brand-mark" :src="BRAND_ICON_URL" alt="" aria-hidden="true" />
        <span>Interview Reader</span>
      </button>
      <nav aria-label="管理菜单">
        <span class="admin-nav-label">管理工作台</span>
        <el-tooltip content="文档管理" placement="right" :disabled="!collapsed">
          <router-link to="/admin/documents" aria-label="文档管理" active-class="router-link-active" exact-active-class="router-link-active">
            <el-icon><Document /></el-icon><span>文档管理</span>
          </router-link>
        </el-tooltip>
        <el-tooltip content="导入中心" placement="right" :disabled="!collapsed">
          <router-link to="/admin/imports" aria-label="导入中心" active-class="router-link-active" exact-active-class="router-link-active">
            <el-icon><Upload /></el-icon><span>导入中心</span>
          </router-link>
        </el-tooltip>
      </nav>
      <div class="admin-sidebar-foot">
        <el-tooltip content="返回阅读器" placement="right" :disabled="!collapsed">
          <button type="button" title="返回阅读器" @click="router.push('/reader')">
            <el-icon><Reading /></el-icon><span>返回阅读器</span>
          </button>
        </el-tooltip>
        <el-tooltip content="退出登录" placement="right" :disabled="!collapsed">
          <button type="button" title="退出登录" @click="emit('logout')">
            <el-icon><SwitchButton /></el-icon><span>退出登录</span>
          </button>
        </el-tooltip>
      </div>
      <button
        class="admin-sidebar-toggle"
        type="button"
        :aria-label="collapsed ? '展开管理侧栏' : '折叠管理侧栏'"
        :title="collapsed ? '展开管理侧栏' : '折叠管理侧栏'"
        @click="collapsed = !collapsed"
      >
        <el-icon><ArrowRight v-if="collapsed" /><ArrowLeft v-else /></el-icon>
        <span>{{ collapsed ? '展开导航' : '收起导航' }}</span>
      </button>
    </aside>
    <main class="admin-main"><router-view /></main>
  </div>
</template>
