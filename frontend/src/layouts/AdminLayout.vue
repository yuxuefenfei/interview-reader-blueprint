<script setup lang="ts">
import { ArrowLeft, ArrowRight, Document, UploadFilled, Reading, SwitchButton } from "@element-plus/icons-vue";
import { ref, watch } from "vue";
import { useRouter } from "vue-router";

defineProps<{ username?: string | null }>();
const emit = defineEmits<{ logout: [] }>();
const router = useRouter();
const collapsed = ref(localStorage.getItem("admin.sidebar.collapsed") === "true");

watch(collapsed, (value) => localStorage.setItem("admin.sidebar.collapsed", String(value)));
</script>

<template>
  <div class="admin-layout" :class="{ 'sidebar-collapsed': collapsed }">
    <aside class="admin-sidebar">
      <button class="admin-sidebar-toggle" type="button" :aria-label="collapsed ? '展开管理侧栏' : '折叠管理侧栏'" :title="collapsed ? '展开管理侧栏' : '折叠管理侧栏'" @click="collapsed = !collapsed"><el-icon><ArrowRight v-if="collapsed" /><ArrowLeft v-else /></el-icon></button>
      <button class="admin-brand" type="button" title="返回阅读器" @click="router.push('/reader')"><span class="brand-mark"></span><span>Interview Reader</span></button>
      <nav aria-label="管理菜单">
        <el-tooltip content="文档管理" placement="right" :disabled="!collapsed"><router-link to="/admin/documents"><el-icon><Document /></el-icon><span>文档管理</span></router-link></el-tooltip>
        <el-tooltip content="导入中心" placement="right" :disabled="!collapsed"><router-link to="/admin/imports"><el-icon><UploadFilled /></el-icon><span>导入中心</span></router-link></el-tooltip>
      </nav>
      <div class="admin-sidebar-foot">
        <el-button text :icon="Reading" aria-label="阅读器" title="阅读器" @click="router.push('/reader')">阅读器</el-button>
        <el-button text :icon="SwitchButton" aria-label="退出登录" title="退出登录" @click="emit('logout')">退出登录</el-button>
      </div>
    </aside>
    <main class="admin-main"><router-view /></main>
  </div>
</template>