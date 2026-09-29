#!/usr/bin/env bash

set -Eeuo pipefail
umask 027

APP_NAME="ireader"
APP_USER="ireader"
APP_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR_FILE="${APP_DIR}/interview-reader.jar"
DATA_DIR="${APP_DIR}/data"
LOG_DIR="${APP_DIR}/logs"
PID_DIR="${APP_DIR}/tmp"
CONF_DIR="${APP_DIR}/conf"
ENV_FILE="${CONF_DIR}/application.env"

PID_FILE="${PID_DIR}/${APP_NAME}.pid"
LOCK_FILE="${PID_DIR}/${APP_NAME}.lock"
LOG_FILE="${LOG_DIR}/${APP_NAME}.log"
STOP_TIMEOUT_SECONDS=30

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
}

die() {
  log "ERROR: $*" >&2
  exit 1
}

require_runtime_user() {
  local current_user
  current_user="$(id -un)"
  [[ "${current_user}" == "${APP_USER}" ]] || \
    die "必须以 ${APP_USER} 用户运行，当前用户为 ${current_user}"
}

check_secure_env_file() {
  [[ -f "${ENV_FILE}" ]] || die "环境文件不存在：${ENV_FILE}"
  [[ ! -L "${ENV_FILE}" ]] || die "环境文件不能是符号链接：${ENV_FILE}"

  local owner mode
  owner="$(stat -c '%U' "${ENV_FILE}")"
  mode="$(stat -c '%a' "${ENV_FILE}")"

  [[ "${owner}" == "root" ]] || die "环境文件所有者必须是 root，当前为 ${owner}"
  (( (8#${mode} & 0022) == 0 )) || \
    die "环境文件不能被 group/other 写入，当前权限为 ${mode}"
}

load_environment() {
  check_secure_env_file

  set -a
  # shellcheck disable=SC1090
  . "${ENV_FILE}"
  set +a

  JAVA_BIN="${JAVA_BIN:-/usr/bin/java}"
  HTTP_PORT="${HTTP_PORT:-28080}"
  JAVA_OPTS="${JAVA_OPTS:--Djava.awt.headless=true}"
  EXTRA_APP_OPTS="${EXTRA_APP_OPTS:-}"

  # 没有在环境文件中配置时，固定使用本机数据目录。
  export INTERVIEW_READER_STORAGE_DIR="${INTERVIEW_READER_STORAGE_DIR:-${DATA_DIR}}"
}

check_runtime_layout() {
  [[ -x "${JAVA_BIN}" ]] || die "Java 不可执行：${JAVA_BIN}"
  [[ -f "${JAR_FILE}" ]] || die "应用 JAR 不存在：${JAR_FILE}"
  [[ ! -L "${JAR_FILE}" ]] || die "应用 JAR 不能是符号链接：${JAR_FILE}"
  [[ -d "${DATA_DIR}" ]] || die "数据目录不存在：${DATA_DIR}"
  [[ -d "${LOG_DIR}" ]] || die "日志目录不存在：${LOG_DIR}"
  [[ -d "${PID_DIR}" ]] || die "PID 目录不存在：${PID_DIR}"
  [[ -w "${DATA_DIR}" ]] || die "数据目录不可写：${DATA_DIR}"
  [[ -w "${LOG_DIR}" ]] || die "日志目录不可写：${LOG_DIR}"
  [[ -w "${PID_DIR}" ]] || die "PID 目录不可写：${PID_DIR}"
}

read_pid() {
  [[ -r "${PID_FILE}" ]] || return 1

  local pid
  read -r pid < "${PID_FILE}"
  [[ "${pid}" =~ ^[0-9]+$ ]] || return 1
  printf '%s\n' "${pid}"
}

is_running() {
  local pid
  pid="$(read_pid)" || return 1

  kill -0 "${pid}" 2>/dev/null || return 1
  [[ -r "/proc/${pid}/cmdline" ]] || return 1

  # 防止 PID 被其他进程复用时误判或误杀。
  tr '\0' '\n' < "/proc/${pid}/cmdline" | grep -Fxq -- "${JAR_FILE}"
}

start_app() {
  check_runtime_layout

  if is_running; then
    log "${APP_NAME} 已运行，pid=$(read_pid)"
    return 0
  fi

  rm -f -- "${PID_FILE}"

  local -a java_opts=()
  local -a extra_app_opts=()
  read -r -a java_opts <<< "${JAVA_OPTS}"

  if [[ -n "${EXTRA_APP_OPTS}" ]]; then
    # 仅做参数拆分，不使用 eval，避免命令注入。
    read -r -a extra_app_opts <<< "${EXTRA_APP_OPTS}"
  fi

  touch "${LOG_FILE}"
  chmod 0640 "${LOG_FILE}"

  log "启动 ${APP_NAME}"
  log "JAR_FILE=${JAR_FILE}"
  log "DATA_DIR=${DATA_DIR}"
  log "HTTP_PORT=${HTTP_PORT}"
  log "LOG_FILE=${LOG_FILE}"

  cd "${DATA_DIR}"

  nohup "${JAVA_BIN}" \
    "${java_opts[@]}" \
    -jar "${JAR_FILE}" \
    --server.port="${HTTP_PORT}" \
    --spring.profiles.active=prod \
    "${extra_app_opts[@]}" \
    >> "${LOG_FILE}" 2>&1 < /dev/null 9>&- &

  local pid=$!
  printf '%s\n' "${pid}" > "${PID_FILE}.tmp"
  mv -f -- "${PID_FILE}.tmp" "${PID_FILE}"

  sleep 2

  if is_running; then
    log "${APP_NAME} 启动成功，pid=${pid}"
    return 0
  fi

  rm -f -- "${PID_FILE}"
  log "${APP_NAME} 启动失败，最近日志如下：" >&2
  tail -n 100 "${LOG_FILE}" >&2 || true
  return 1
}

stop_app() {
  if ! is_running; then
    log "${APP_NAME} 未运行"
    rm -f -- "${PID_FILE}"
    return 0
  fi

  local pid
  pid="$(read_pid)"

  log "停止 ${APP_NAME}，pid=${pid}"
  kill -TERM "${pid}"

  local i
  for ((i = 0; i < STOP_TIMEOUT_SECONDS; i++)); do
    if ! kill -0 "${pid}" 2>/dev/null; then
      rm -f -- "${PID_FILE}"
      log "${APP_NAME} 已停止"
      return 0
    fi
    sleep 1
  done

  log "优雅停止超时，发送 SIGKILL，pid=${pid}"
  kill -KILL "${pid}" 2>/dev/null || true

  for ((i = 0; i < 5; i++)); do
    if ! kill -0 "${pid}" 2>/dev/null; then
      rm -f -- "${PID_FILE}"
      log "${APP_NAME} 已强制停止"
      return 0
    fi
    sleep 1
  done

  die "无法停止进程 pid=${pid}"
}

status_app() {
  if is_running; then
    log "${APP_NAME} 正在运行，pid=$(read_pid)，port=${HTTP_PORT}"
    return 0
  fi

  rm -f -- "${PID_FILE}"
  log "${APP_NAME} 未运行"
  return 3
}

main() {
  require_runtime_user
  load_environment

  [[ -d "${PID_DIR}" ]] || die "PID 目录不存在：${PID_DIR}"

  exec 9>"${LOCK_FILE}"
  flock -w 30 9 || die "无法取得服务操作锁"

  case "${1:-}" in
    start)
      start_app
      ;;
    stop)
      stop_app
      ;;
    restart)
      stop_app
      sleep 2
      start_app
      ;;
    status)
      status_app
      ;;
    *)
      echo "Usage: $0 {start|stop|restart|status}" >&2
      exit 2
      ;;
  esac
}

main "$@"
