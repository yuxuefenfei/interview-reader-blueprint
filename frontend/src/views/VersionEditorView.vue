<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { adminApi } from "../api/admin";
import type { EditableVersion } from "../types/api";
import { zh } from "../shared/presentation";

const route = useRoute();
const router = useRouter();
const editor = ref<EditableVersion | null>(null);
const draftText = ref("");
const saving = ref(false);
const versionId = route.params.versionId as string;
onMounted(() => void load());
async function load(): Promise<void> { try { editor.value = await adminApi.editor(versionId); draftText.value = JSON.stringify(editor.value.documentPackage, null, 2); } catch (caught) { ElMessage.error(message(caught)); } }
async function save(): Promise<void> { if (!editor.value) return; let parsed: EditableVersion["documentPackage"]; try { parsed = JSON.parse(draftText.value); } catch { ElMessage.error("文档包 JSON 格式无效"); return; } saving.value = true; try { editor.value = await adminApi.saveEditor(versionId, editor.value.version.draftRevision, parsed); draftText.value = JSON.stringify(editor.value.documentPackage, null, 2); ElMessage.success("草稿已保存"); } catch (caught) { ElMessage.error(message(caught)); } finally { saving.value = false; } }
async function remove(): Promise<void> { try { await ElMessageBox.confirm("将永久删除这份草稿，无法恢复。", "丢弃草稿", { type: "warning" }); await adminApi.deleteDraft(versionId); ElMessage.success("草稿已丢弃"); await router.push("/admin/documents"); } catch (caught) { if (caught !== "cancel") ElMessage.error(message(caught)); } }
function message(value: unknown): string { return value instanceof Error ? value.message : "操作失败"; }
</script>

<template>
  <section class="admin-view editor-view"><header class="admin-view-header"><div><p class="eyebrow">版本修订</p><h1>草稿编辑器</h1><span v-if="editor">v{{ editor.version.versionNo }} · {{ zh(editor.version.status) }} · 修订 {{ editor.version.draftRevision }}</span></div><div class="header-buttons"><el-button @click="router.push('/admin/documents')">返回</el-button><el-button type="danger" plain @click="remove">丢弃草稿</el-button><el-button type="primary" :loading="saving" @click="save">保存修订</el-button></div></header><div v-if="editor" class="editor-workspace"><aside><h2>文档结构</h2><el-tree :data="editor.documentPackage.sections" node-key="sectionKey" :props="{ label: 'title' }" default-expand-all /><p>{{ editor.documentPackage.blocks.length }} 个内容块</p></aside><section><div class="editor-hint"><strong>结构化文档包</strong><span>可直接修改节点、块类型、顺序、语言和内容；保存时服务端会校验树结构与块序号。</span></div><el-input v-model="draftText" type="textarea" :rows="28" class="package-editor" spellcheck="false" /></section></div></section>
</template>