<script lang="ts" setup>
import {computed, nextTick, ref, watch} from "vue";
import {ElMessage} from "element-plus/es/components/message/index";
import {userMessage} from "../api";
import {formatTime, stageLabel} from "../format";
import {consoleState} from "../state";

const props = defineProps<{ operationId: string; compact?: boolean }>();
const entries = computed(() => consoleState.feeds.get(props.operationId) ?? []);
const stages = computed(() => entries.value.filter((entry) => entry.kind === "STAGE"));
const olderCursor = computed(() => consoleState.olderCursors.get(props.operationId));
const loadingOlder = ref(false);
const terminal = ref<HTMLElement | null>(null);
let followTail = true;

watch(() => props.operationId, (id) => {
  if (id) void consoleState.ensureFeed(id);
}, {immediate: true});
watch(entries, async () => {
  if (!followTail) return;
  await nextTick();
  if (terminal.value) terminal.value.scrollTop = terminal.value.scrollHeight;
});

function onScroll(): void {
  if (!terminal.value) return;
  followTail = terminal.value.scrollHeight - terminal.value.scrollTop - terminal.value.clientHeight < 48;
}

async function loadOlder(): Promise<void> {
  const node = terminal.value;
  const oldHeight = node?.scrollHeight ?? 0;
  const oldTop = node?.scrollTop ?? 0;
  loadingOlder.value = true;
  try {
    await consoleState.loadOlder(props.operationId);
    await nextTick();
    if (node) node.scrollTop = oldTop + node.scrollHeight - oldHeight;
  } catch (caught) {
    ElMessage.error(userMessage(caught, "读取更早的日志失败"));
  } finally {
    loadingOlder.value = false;
  }
}

function entryLabel(kind: string, stage: string | null): string {
  if (kind === "COMMAND_STDERR") return "命令错误";
  if (kind === "COMMAND_STDOUT") return "命令输出";
  return stageLabel(stage);
}
</script>

<template>
  <div :class="{ compact }" class="operation-feed">
    <div class="operation-feed-heading">
      <div><strong>升级阶段与实时日志</strong><span>按实际操作记录更新</span></div>
      <el-button v-if="olderCursor" :loading="loadingOlder" text @click="loadOlder">加载更早记录</el-button>
    </div>
    <div v-if="!entries.length" class="empty-state">升级开始后，这里会实时显示阶段与启停命令输出。</div>
    <div v-else class="operation-feed-grid">
      <div aria-label="升级阶段" class="stage-track">
        <ol>
          <li v-for="(entry, index) in stages" :key="entry.id" :class="{ current: index === stages.length - 1 }">
            <span aria-hidden="true" class="stage-dot"/>
            <div><strong>{{ stageLabel(entry.stage) }}</strong>
              <p>{{ entry.message }}</p>
              <time>{{ formatTime(entry.at) }}</time>
            </div>
          </li>
        </ol>
      </div>
      <div ref="terminal" aria-live="polite" class="log-terminal" role="log" @scroll="onScroll">
        <div v-for="entry in entries" :key="entry.id" :class="entry.kind.toLowerCase()" class="log-line">
          <time>{{ formatTime(entry.at) }}</time>
          <span class="log-kind">{{ entryLabel(entry.kind, entry.stage) }}</span><span>{{ entry.message }}</span>
        </div>
      </div>
    </div>
  </div>
</template>