<script lang="ts" setup>
import {onBeforeUnmount, onMounted} from "vue";
import AdminLayout from "./components/AdminLayout.vue";
import {consoleState} from "./state";

onMounted(() => {
  void consoleState.start();
});
onBeforeUnmount(() => consoleState.stop());
</script>

<template>
  <AdminLayout/>
</template>