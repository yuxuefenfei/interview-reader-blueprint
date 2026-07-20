<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { toUserMessage } from "./utils/errorMessage";
import { readerApi } from "./api/reader";
import { syncDeletedDocuments } from "./offline/deletionSync";
import { activateServiceWorkerUpdate, SERVICE_WORKER_UPDATE_EVENT } from "./offline/serviceWorkerRegistration";

const router = useRouter();
const ready = ref(false);
const authenticated = ref(false);
const username = ref<string | null>(null);
const form = ref({ username: "", password: "" });
const error = ref("");
const submitting = ref(false);
const online = ref(navigator.onLine);
const updateAvailable = ref(false);

onMounted(async () => {
  window.addEventListener("online", handleOnline);
  window.addEventListener("offline", handleOffline);
  window.addEventListener(SERVICE_WORKER_UPDATE_EVENT, handleUpdateAvailable);
  try {
    const session = await readerApi.session();
    authenticated.value = session.authenticated;
    username.value = session.username;
    if (session.authenticated) void syncDeletedDocuments().catch(() => undefined);
  } finally {
    ready.value = true;
  }
});

onBeforeUnmount(() => {
  window.removeEventListener("online", handleOnline);
  window.removeEventListener("offline", handleOffline);
  window.removeEventListener(SERVICE_WORKER_UPDATE_EVENT, handleUpdateAvailable);
});
async function login(): Promise<void> {
  submitting.value = true;
  error.value = "";
  try {
    const session = await readerApi.login(form.value.username, form.value.password);
    authenticated.value = session.authenticated;
    username.value = session.username;
    void syncDeletedDocuments().catch(() => undefined);
    form.value.password = "";
    await router.replace("/reader");
  } catch (caught) {
    error.value = toUserMessage(caught, "登录失败，请检查账号和密码");
  } finally {
    submitting.value = false;
  }
}

async function logout(): Promise<void> {
  await readerApi.logout().catch(() => undefined);
  authenticated.value = false;
  username.value = null;
  await router.replace("/reader");
}

function syncDeletions(): void {
  if (authenticated.value) void syncDeletedDocuments().catch(() => undefined);
}

function handleOnline(): void {
  online.value = true;
  syncDeletions();
}

function handleOffline(): void {
  online.value = false;
}

function handleUpdateAvailable(): void {
  updateAvailable.value = true;
}

function applyUpdate(): void {
  activateServiceWorkerUpdate();
}
</script>

<template>
  <a v-if="ready && authenticated" class="skip-link" href="#main-content">跳到正文</a>
  <main v-if="!ready" class="boot-screen">正在初始化阅读器</main>
  <section v-else-if="!authenticated" class="login-page">
    <form class="login-card" @submit.prevent="login">
      <div class="brand-mark" aria-hidden="true"></div>
      <div><h1>Interview Reader</h1><p>登录后继续阅读与管理文档。</p></div>
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <el-input v-model="form.username" aria-label="用户名" autocomplete="username" placeholder="用户名" size="large" />
      <el-input v-model="form.password" aria-label="密码" type="password" autocomplete="current-password" placeholder="密码" show-password size="large" />
      <el-button native-type="submit" type="primary" size="large" :loading="submitting">登录</el-button>
    </form>
  </section>
  <div v-else id="main-content" class="route-content" tabindex="-1">
    <router-view :username="username" @logout="logout" />
  </div>
  <div v-if="!online" class="app-status-banner" role="status">当前处于离线状态；已缓存内容仍可阅读，进度会在联网后同步。</div>
  <div v-if="updateAvailable" class="app-update-banner" role="status">
    <span>新版本已准备好。</span>
    <button type="button" @click="applyUpdate">刷新并更新</button>
  </div>
</template>