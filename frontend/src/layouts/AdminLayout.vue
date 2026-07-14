<script setup lang="ts">
import { Document, UploadFilled, Reading, SwitchButton } from "@element-plus/icons-vue";
import { useRouter } from "vue-router";

defineProps<{ username?: string | null }>();
const emit = defineEmits<{ logout: [] }>();
const router = useRouter();
</script>

<template>
  <div class="admin-layout">
    <aside class="admin-sidebar">
      <button class="admin-brand" type="button" @click="router.push('/reader')"><span class="brand-mark"></span><span>Interview Reader</span></button>
      <nav aria-label="管理菜单">
        <router-link to="/admin/documents"><el-icon><Document /></el-icon><span>文档管理</span></router-link>
        <router-link to="/admin/imports"><el-icon><UploadFilled /></el-icon><span>导入中心</span></router-link>
      </nav>
      <div class="admin-sidebar-foot">
        <el-button text :icon="Reading" @click="router.push('/reader')">阅读器</el-button>
        <el-button text :icon="SwitchButton" @click="emit('logout')">退出登录</el-button>
      </div>
    </aside>
    <main class="admin-main"><router-view /></main>
  </div>
</template>