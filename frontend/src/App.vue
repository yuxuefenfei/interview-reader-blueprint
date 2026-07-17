<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { readerApi } from "./api/reader";
import { syncDeletedDocuments } from "./offline/deletionSync";

const router = useRouter();
const ready = ref(false);
const authenticated = ref(false);
const username = ref<string | null>(null);
const form = ref({ username: "", password: "" });
const error = ref("");
const submitting = ref(false);

onMounted(async () => {
  window.addEventListener("online", syncDeletions);
  try {
    const session = await readerApi.session();
    authenticated.value = session.authenticated;
    username.value = session.username;
    if (session.authenticated) void syncDeletedDocuments().catch(() => undefined);
  } finally {
    ready.value = true;
  }
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
    error.value = caught instanceof Error ? caught.message : "登录失败，请检查账号和密码";
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
</script>

<template>
  <main v-if="!ready" class="boot-screen">正在初始化阅读器</main>
  <section v-else-if="!authenticated" class="login-page">
    <form class="login-card" @submit.prevent="login">
      <div class="brand-mark" aria-hidden="true"></div>
      <div><h1>Interview Reader</h1><p>登录后继续阅读与管理文档。</p></div>
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <el-input v-model="form.username" autocomplete="username" placeholder="用户名" size="large" />
      <el-input v-model="form.password" type="password" autocomplete="current-password" placeholder="密码" show-password size="large" />
      <el-button native-type="submit" type="primary" size="large" :loading="submitting">登录</el-button>
    </form>
  </section>
  <router-view v-else :username="username" @logout="logout" />
</template>