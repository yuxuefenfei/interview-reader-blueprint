<script lang="ts" setup>
import {ArrowLeft, ArrowRight, Document, FolderAdd, RefreshRight, SwitchButton} from "@element-plus/icons-vue";
import {ref, watch} from "vue";
import {useRouter} from "vue-router";
import {ADMIN_BRAND_ICON_URL} from "../shared/branding";

defineProps<{ username?: string | null }>();
const emit = defineEmits<{ logout: [] }>();
const router = useRouter();
const upgradeConsoleUrl = (() => {
  try {
    const url = new URL(import.meta.env.VITE_UPGRADE_CONSOLE_URL?.trim() || "");
    return url.protocol === "https:" && !url.username && !url.password ? url.href : "";
  } catch {
    return "";
  }
})();
const collapsedPreference = localStorage.getItem("admin.sidebar.collapsed");
const collapsed = ref(collapsedPreference === null ? false : collapsedPreference === "true");

watch(collapsed, (value) => localStorage.setItem("admin.sidebar.collapsed", String(value)));
</script>

<template>
  <div :class="{ 'sidebar-collapsed': collapsed }" class="admin-layout">
    <aside class="admin-sidebar">
      <button class="admin-brand" title="返回阅读器" type="button" @click="router.push('/reader')">
        <img :src="ADMIN_BRAND_ICON_URL" alt="" aria-hidden="true" class="brand-mark" height="36" width="36"/>
        <span>Interview Reader</span>
      </button>
      <nav aria-label="管理菜单">
        <span class="admin-nav-label">管理工作台</span>
        <el-tooltip :disabled="!collapsed" content="文档管理" placement="right">
          <router-link active-class="router-link-active" aria-label="文档管理" exact-active-class="router-link-active"
                       to="/admin/documents">
            <el-icon>
              <Document/>
            </el-icon>
            <span>文档管理</span>
          </router-link>
        </el-tooltip>
        <el-tooltip :disabled="!collapsed" content="导入中心" placement="right">
          <router-link active-class="router-link-active" aria-label="导入中心" exact-active-class="router-link-active"
                       to="/admin/imports">
            <el-icon>
              <FolderAdd/>
            </el-icon>
            <span>导入中心</span>
          </router-link>
        </el-tooltip>
        <el-tooltip :content="upgradeConsoleUrl ? '在独立控制台打开系统升级' : '升级控制台地址未配置或无效'"
                    :disabled="!collapsed" placement="right">
          <a :aria-disabled="!upgradeConsoleUrl" :href="upgradeConsoleUrl || undefined" :title="upgradeConsoleUrl ? '在新窗口打开升级控制台' : '升级控制台地址未配置或无效'" aria-label="系统升级"
             rel="noopener noreferrer"
             target="_blank">
            <el-icon>
              <RefreshRight/>
            </el-icon>
            <span>系统升级</span>
          </a>
        </el-tooltip>
      </nav>
      <div class="admin-sidebar-foot">
        <el-tooltip :disabled="!collapsed" content="退出登录" placement="right">
          <button title="退出登录" type="button" @click="emit('logout')">
            <el-icon>
              <SwitchButton/>
            </el-icon>
            <span>退出登录</span>
          </button>
        </el-tooltip>
      </div>
      <button
          :aria-label="collapsed ? '展开管理侧栏' : '折叠管理侧栏'"
          :title="collapsed ? '展开管理侧栏' : '折叠管理侧栏'"
          class="admin-sidebar-toggle"
          type="button"
          @click="collapsed = !collapsed"
      >
        <el-icon>
          <ArrowRight v-if="collapsed"/>
          <ArrowLeft v-else/>
        </el-icon>
        <span>{{ collapsed ? '展开导航' : '收起导航' }}</span>
      </button>
    </aside>
    <main aria-label="管理工作区" class="admin-main">
      <router-view/>
    </main>
  </div>
</template>
