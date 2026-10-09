<script lang="ts" setup>
import {computed, nextTick, ref, watch, type Ref} from "vue";
import {ElMessage} from "element-plus/es/components/message/index";
import {userMessage} from "../api";
import {formatTime, stageLabel, statusTag} from "../format";
import {consoleState} from "../state";

const props = defineProps<{ operationId: string; compact?: boolean }>();
const operation = computed(() => consoleState.operationDetails.get(props.operationId)
    ?? (consoleState.dashboard.value?.latestOperation?.id === props.operationId
        ? consoleState.dashboard.value.latestOperation : undefined));
const running = computed(() => operation.value?.status === "RUNNING");
const entries = computed(() => consoleState.feeds.get(props.operationId) ?? []);
const stages = computed(() => entries.value.filter((entry) => entry.kind === "STAGE"));
const olderCursor = computed(() => consoleState.olderCursors.get(props.operationId));
const loadingOlder = ref(false);
const stageTrack = ref<HTMLElement | null>(null);
const terminal = ref<HTMLElement | null>(null);
const stageScroll = useFollowScroll(stageTrack);
const logScroll = useFollowScroll(terminal);
const FOLLOW_DISTANCE_PX = 48;

/** Each pane follows independently, so reading earlier stages never moves the command log. */
function useFollowScroll(node: Ref<HTMLElement | null>) {
  const following = ref(false);

  function scrollToLatest(): void {
    if (node.value) node.value.scrollTop = node.value.scrollHeight;
  }

  function onScroll(): void {
    if (!node.value || loadingOlder.value) return;
    following.value = node.value.scrollHeight - node.value.scrollTop - node.value.clientHeight <= FOLLOW_DISTANCE_PX;
  }

  function resume(): void {
    following.value = true;
    scrollToLatest();
  }

  return {following, scrollToLatest, onScroll, resume};
}

watch(() => props.operationId, async (id) => {
  stageScroll.following.value = running.value;
  logScroll.following.value = running.value;
  if (id) void consoleState.ensureFeed(id);
  await nextTick();
  if (id !== props.operationId) return;
  for (const pane of [stageTrack, terminal]) {
    if (pane.value) pane.value.scrollTop = running.value ? pane.value.scrollHeight : 0;
  }
}, {immediate: true});

// Manual recovery resumes the same operation ID; a new run should follow by default as well.
watch(running, (active) => {
  if (!active) return;
  stageScroll.resume();
  logScroll.resume();
}, {flush: "post"});

// Tail IDs change for live appends, but not when older records are prepended or SSE replays duplicates.
watch(() => stages.value.at(-1)?.id, () => {
  if (!loadingOlder.value && stageScroll.following.value) stageScroll.scrollToLatest();
}, {flush: "post"});
watch(() => entries.value.at(-1)?.id, () => {
  if (!loadingOlder.value && logScroll.following.value) logScroll.scrollToLatest();
}, {flush: "post"});

async function loadOlder(): Promise<void> {
  const id = props.operationId;
  const positions = [stageTrack.value, terminal.value].map((node) => {
    const anchor = node?.querySelector<HTMLElement>("[data-entry-id]");
    return {node, anchor, offset: anchor?.offsetTop ?? 0, top: node?.scrollTop ?? 0};
  });
  loadingOlder.value = true;
  try {
    await consoleState.loadOlder(id);
    await nextTick();
    if (id !== props.operationId) return;
    // Anchor to an existing row: live output appended during the request must not shift the viewport.
    for (const {node, anchor, offset, top} of positions) {
      if (node && anchor) node.scrollTop = top + anchor.offsetTop - offset;
    }
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
    <div v-if="!compact" class="operation-feed-heading">
      <div><strong>升级阶段与日志</strong><span>{{ running ? "实时更新" : "按实际操作记录展示" }}</span></div>
      <el-button v-if="olderCursor" :loading="loadingOlder" text @click="loadOlder">加载更早记录</el-button>
    </div>
    <div v-if="!entries.length" class="empty-state">升级开始后，这里会实时显示阶段与启停命令输出。</div>
    <div v-else class="operation-feed-grid">
      <section aria-label="升级阶段" class="feed-pane">
        <div class="feed-pane-heading">
          <strong>升级阶段</strong>
          <div class="feed-scroll-actions">
            <span v-if="running">{{ stageScroll.following.value ? "跟随最新" : "已暂停跟随" }}</span>
            <el-button
              v-if="!stageScroll.following.value" size="small" text type="primary"
              @click="stageScroll.resume"
            >回到最新</el-button>
          </div>
        </div>
        <div ref="stageTrack" aria-label="升级阶段记录" class="stage-track" tabindex="0" @scroll="stageScroll.onScroll">
          <ol>
            <li
              v-for="(entry, index) in stages" :key="entry.id" :data-entry-id="entry.id"
              :class="{
                current: running && index === stages.length - 1,
                ['result-' + statusTag(operation?.status ?? '')]: !running && index === stages.length - 1
              }"
            >
              <span aria-hidden="true" class="stage-dot" />
              <div><strong>{{ stageLabel(entry.stage) }}</strong>
                <p>{{ entry.message }}</p>
                <time>{{ formatTime(entry.at) }}</time>
              </div>
            </li>
          </ol>
          <p v-if="!stages.length" class="stage-empty">当前记录中暂无阶段，可加载更早记录查看。</p>
        </div>
      </section>
      <section aria-label="命令日志" class="feed-pane">
        <div class="feed-pane-heading">
          <strong>命令与阶段日志</strong>
          <div class="feed-scroll-actions">
            <el-button
              v-if="compact && olderCursor" :loading="loadingOlder" size="small" text type="primary"
              @click="loadOlder"
            >加载更早记录</el-button>
            <span v-if="running">{{ logScroll.following.value ? "跟随最新" : "已暂停跟随" }}</span>
            <el-button
              v-if="!logScroll.following.value" size="small" text type="primary"
              @click="logScroll.resume"
            >回到最新</el-button>
          </div>
        </div>
        <div
          ref="terminal" aria-label="操作日志记录" aria-live="polite" class="log-terminal" role="log"
          tabindex="0" @scroll="logScroll.onScroll"
        >
          <div v-for="entry in entries" :key="entry.id" :data-entry-id="entry.id" :class="entry.kind.toLowerCase()" class="log-line">
            <time>{{ formatTime(entry.at) }}</time>
            <span class="log-kind">{{ entryLabel(entry.kind, entry.stage) }}</span><span>{{ entry.message }}</span>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>
