const $ = (id) => document.getElementById(id);
const stageNames = {
  PRECHECKING: "预检中", STOP_WRITES: "停写中", DRAINING: "排空中", BACKING_UP: "备份中",
  STOPPING: "停止主进程", SWITCHING: "切换 JAR", STARTING: "启动新版本",
  READINESS: "就绪检查中", OPENING: "恢复写入", SUCCEEDED: "发布成功",
  ROLLING_BACK: "自动回滚中", RESTORING_DB: "恢复数据库", RESTORING_FILES: "恢复文件与旧 JAR",
  STARTING_OLD: "验证旧版", ROLLED_BACK: "已回滚", FAILED: "发布失败",
  NEEDS_OPERATOR: "需要人工处理", INTERRUPTED: "控制台中断", GATED_FAILURE: "维护门禁保留",
  ROLLBACK_FAILED: "回滚失败", RECOVERING: "人工恢复中", RECOVERY_FAILED: "恢复失败",
  POST_OPEN_ERROR: "状态需核实", ABORTING: "恢复旧版写入中", RESTORED: "人工恢复完成"
};
const stages = ["STOP_WRITES", "DRAINING", "BACKING_UP", "STOPPING", "SWITCHING", "READINESS"];
let refreshing = false;

function notice(message) {
  const node = $("notice");
  node.textContent = message;
  node.hidden = !message;
}
function formatTime(value) { return value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "—"; }
function formatBytes(value) {
  if (typeof value !== "number" || !Number.isFinite(value)) return "—";
  return value >= 1024 ** 3 ? `${(value / 1024 ** 3).toFixed(1)} GB` : `${(value / 1024 ** 2).toFixed(0)} MB`;
}
function short(value) { return value ? value.slice(0, 12) : "—"; }
function setCheck(id, status) {
  const element = $(id);
  const good = status === "UP";
  element.textContent = good ? "正常" : status ? "不可用" : "未响应";
  element.className = `status-text ${good ? "good" : "bad"}`;
}
function create(tag, className, content) {
  const element = document.createElement(tag);
  if (className) element.className = className;
  if (content !== undefined) element.textContent = content;
  return element;
}
function renderHealth(health) {
  $("checked-at").textContent = `最近检查 ${formatTime(health.checkedAt)}`;
  setCheck("liveness", health.liveness?.status);
  setCheck("readiness", health.readiness?.status);
  setCheck("database", health.readiness?.components?.db?.status);
  setCheck("disk", health.readiness?.components?.diskSpace?.status);
  const badge = $("overall-badge");
  badge.textContent = health.eligible ? "符合升级条件" : "升级条件未满足";
  badge.className = `badge ${health.eligible ? "good" : "bad"}`;
  const gate = $("gate-status");
  const maintenance = health.drain?.maintenance;
  gate.textContent = maintenance === true ? "主应用停写中" : maintenance === false ? "写入开放" : "维护状态不可确认";
  gate.className = `gate ${maintenance === true ? "bad" : maintenance === false ? "good" : ""}`;
  $("health-blockers").textContent = health.blockers?.join(" · ") || "所有升级门禁检查通过";
  const drain = health.drain || {};
  $("current-commit").textContent = drain.releaseCommit && drain.releaseCommit !== "unknown" ? short(drain.releaseCommit) : "—";
  $("active-writes").textContent = Number.isInteger(drain.activeWrites) ? drain.activeWrites : "—";
  $("background-jobs").textContent = Number.isInteger(drain.importJobs) ? `${drain.importJobs} / ${drain.deletionJobs ?? "—"}` : "—";
  $("jvm-memory").textContent = formatBytes(drain.jvmUsedBytes);
  $("db-connections").textContent = Number.isFinite(drain.dbConnectionsActive) ? drain.dbConnectionsActive : "—";
  $("http-latency").textContent = Number.isFinite(drain.httpMeanMs) ? `${drain.httpMeanMs.toFixed(0)} ms` : "—";
}
function renderFlow(operation) {
  $("operation-stage").textContent = operation ? stageNames[operation.stage] || operation.stage : "尚无操作";
  $("operation-message").textContent = operation?.message || "通过健康检查后可上传构建产物。";
  const current = stages.indexOf(operation?.stage);
  document.querySelectorAll(".steps li").forEach((step, index) => {
    step.classList.toggle("active", current === index && operation?.status === "RUNNING");
    step.classList.toggle("done", current > index || operation?.status === "SUCCEEDED");
  });
}
function renderReleases(releases, health, blocked) {
  const list = $("release-list");
  list.replaceChildren();
  if (!releases.length) { list.append(create("p", "empty", "暂无已验证产物")); return; }
  for (const release of [...releases].reverse()) {
    const row = create("div", "release-row");
    const info = create("div");
    info.append(create("strong", "", `提交 ${short(release.commit)} · Actions #${release.runId}`));
    info.append(create("small", "", `SHA-256 ${release.sha256} · ${formatBytes(release.bytes)} · ${formatTime(release.stagedAt)}`));
    const button = create("button", "primary-button", "开始升级");
    button.type = "button";
    button.disabled = !health.eligible || blocked;
    button.title = button.disabled ? "健康门禁未通过或已有操作待处理" : "按照受控流程开始发布";
    button.addEventListener("click", () => deploy(release));
    row.append(info, button);
    list.append(row);
  }
}
function renderOperations(operations) {
  const list = $("operation-list");
  list.replaceChildren();
  if (!operations.length) { list.append(create("p", "empty", "暂无操作")); return; }
  for (const operation of [...operations].reverse()) {
    const row = create("div", "operation-row");
    const info = create("div");
    info.append(create("strong", "", `${stageNames[operation.stage] || operation.stage} · ${operation.message}`));
    info.append(create("small", "", `操作 ${short(operation.id)} · 开始 ${formatTime(operation.startedAt)} · 更新 ${formatTime(operation.updatedAt)}${operation.backupId ? ` · 备份 ${short(operation.backupId)}` : ""}`));
    if (operation.events?.length) {
      const details = create("details", "event-detail");
      details.append(create("summary", "", `查看 ${operation.events.length} 条阶段记录`));
      const events = create("ol");
      for (const item of operation.events) {
        events.append(create("li", "", `${formatTime(item.at)} · ${stageNames[item.stage] || item.stage} · ${item.message}`));
      }
      details.append(events);
      info.append(details);
    }
    const actions = create("div", "row-actions");
    actions.append(create("span", `state ${operation.status}`, operation.status));
    if (operation.status === "SUCCEEDED" && operation.backupId) {
      const button = create("button", "quiet-button", "恢复发布前备份");
      button.type = "button";
      button.addEventListener("click", () => restorePublished(operation));
      actions.append(button);
    }
    if (operation.status === "NEEDS_OPERATOR" && !operation.backupId) {
      const button = create("button", "quiet-button", "检查旧版并恢复写入");
      button.type = "button";
      button.addEventListener("click", () => abortInterrupted(operation));
      actions.append(button);
    }
    if (operation.status === "NEEDS_OPERATOR" && operation.backupId) {
      const button = create("button", "quiet-button", "恢复同批备份");
      button.type = "button";
      button.addEventListener("click", () => recover(operation));
      actions.append(button);
    }
    row.append(info, actions);
    list.append(row);
  }
}
async function request(path, options = {}) {
  const response = await fetch(path, { cache: "no-store", ...options });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.error || `请求失败（HTTP ${response.status}）`);
  return body;
}
async function refresh() {
  if (refreshing) return;
  refreshing = true;
  try {
    const overview = await request("/api/overview");
    renderHealth(overview.health);
    const operations = overview.state.operations || [];
    const latest = operations.at(-1);
    renderFlow(latest);
    renderReleases(overview.state.releases || [], overview.health, operations.some((op) => ["RUNNING", "NEEDS_OPERATOR"].includes(op.status)));
    renderOperations(operations);
  } catch (error) { notice(error.message); }
  finally { refreshing = false; }
}
async function deploy(release) {
  if (!window.confirm(`确认升级至提交 ${short(release.commit)}？系统会依次停写、排空、备份、停进程、切换并检查就绪。`)) return;
  try {
    const operation = await request(`/api/releases/${encodeURIComponent(release.id)}/deployments`, { method: "POST" });
    notice(`升级操作 ${short(operation.id)} 已开始。`);
    await refresh();
  } catch (error) { notice(error.message); }
}
async function restorePublished(operation) {
  if (!window.confirm(`确认恢复至备份 ${operation.backupId}？该备份之后的所有数据库与文件写入都可能丢失。控制台会先备份当前状态。`)) return;
  try {
    const result = await request("/api/recoveries", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ backupId: operation.backupId, confirmDataRestore: true })
    });
    notice(`人工恢复操作 ${short(result.id)} 已开始。`);
    await refresh();
  } catch (error) { notice(error.message); }
}
async function abortInterrupted(operation) {
  if (!window.confirm("确认检查旧版健康与后台任务排空，并在条件满足时恢复写入？")) return;
  try {
    await request(`/api/operations/${encodeURIComponent(operation.id)}/abort`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ backupId: null, confirmDataRestore: true })
    });
    notice("正在核验旧版并尝试恢复写入。");
    await refresh();
  } catch (error) { notice(error.message); }
}
async function recover(operation) {
  if (!window.confirm(`确认用备份 ${operation.backupId} 恢复数据库、数据文件和旧 JAR？当前状态会被覆盖。`)) return;
  try {
    await request(`/api/operations/${encodeURIComponent(operation.id)}/recover`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ backupId: operation.backupId, confirmDataRestore: true })
    });
    notice("恢复已开始；维护门禁会保持到旧版验证通过。");
    await refresh();
  } catch (error) { notice(error.message); }
}
$("refresh").addEventListener("click", refresh);
$("upload-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const button = $("upload-button");
  button.disabled = true;
  button.textContent = "验证中…";
  try {
    const release = await request("/api/releases", { method: "POST", body: new FormData(form) });
    notice(`产物已验证：Actions #${release.runId}，提交 ${short(release.commit)}。`);
    form.reset();
    await refresh();
  } catch (error) { notice(error.message); }
  finally { button.disabled = false; button.textContent = "上传并验证"; }
});
refresh();
setInterval(refresh, 3000);
