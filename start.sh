#!/usr/bin/env bash
# start.sh
# 一键拉起 srm-backend (Spring Boot) + srm-frontend (Vite) 本地开发环境。
# 用法: ./start.sh          启动前后端
#       ./start.sh backend  只启动后端
#       ./start.sh frontend 只启动前端
# 停止: Ctrl+C（会自动清理已拉起的子进程）

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$SCRIPT_DIR/srm-backend"
FRONTEND_DIR="$SCRIPT_DIR/srm-frontend"
LOG_DIR="$SCRIPT_DIR/.run-logs"
mkdir -p "$LOG_DIR"

MODE="${1:-all}"
PIDS=()

cleanup() {
  echo
  echo "[start.sh] 正在停止已启动的进程..."
  for pid in "${PIDS[@]:-}"; do
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null
    fi
  done
  wait 2>/dev/null
  echo "[start.sh] 已退出"
}
trap cleanup EXIT INT TERM

ensure_env_file() {
  if [[ ! -f "$SCRIPT_DIR/.env" ]]; then
    if [[ -f "$SCRIPT_DIR/.env.example" ]]; then
      cp "$SCRIPT_DIR/.env.example" "$SCRIPT_DIR/.env"
      echo "[start.sh] 未找到 .env，已从 .env.example 生成一份，请先填写 DB_PASSWORD / JWT_SECRET 等真实值后重新运行。"
      exit 1
    else
      echo "[start.sh] 未找到 .env 且没有 .env.example 可参考，请先在项目根目录创建 .env（DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD/JWT_SECRET 等）。" >&2
      exit 1
    fi
  fi
  set -a
  # shellcheck disable=SC1091
  source "$SCRIPT_DIR/.env"
  set +a
}

check_backend_source() {
  if ! find "$BACKEND_DIR/src/main/java" -name '*.java' -print -quit 2>/dev/null | grep -q .; then
    echo "[start.sh] 警告: srm-backend/src/main/java 下还没有任何 .java 源码（当前只是空目录骨架）。" >&2
    echo "[start.sh]       后端业务代码由 Qoder 负责实现，请先让 Qoder 按 docs/api-spec.md + docs/backend-interface-design.md + .qoder/rules.md 完成 srm-backend 的实现，再运行本脚本。" >&2
    return 1
  fi
  return 0
}

start_backend() {
  if ! check_backend_source; then
    echo "[start.sh] 跳过后端启动。"
    return
  fi
  echo "[start.sh] 启动后端 (srm-backend, 端口 ${SERVER_PORT:-8080}) ..."
  (cd "$BACKEND_DIR" && ./mvnw spring-boot:run) >"$LOG_DIR/backend.log" 2>&1 &
  PIDS+=("$!")
  echo "[start.sh] 后端日志: $LOG_DIR/backend.log"
}

start_frontend() {
  if [[ ! -d "$FRONTEND_DIR/node_modules" ]]; then
    echo "[start.sh] srm-frontend/node_modules 不存在，先执行 npm install ..."
    (cd "$FRONTEND_DIR" && npm install) || {
      echo "[start.sh] npm install 失败，请手动检查 srm-frontend" >&2
      return 1
    }
  fi
  echo "[start.sh] 启动前端 (srm-frontend, Vite 默认端口 5173) ..."
  (cd "$FRONTEND_DIR" && npm run dev) >"$LOG_DIR/frontend.log" 2>&1 &
  PIDS+=("$!")
  echo "[start.sh] 前端日志: $LOG_DIR/frontend.log"
}

ensure_env_file

case "$MODE" in
  backend)
    start_backend
    ;;
  frontend)
    start_frontend
    ;;
  all)
    start_backend
    start_frontend
    ;;
  *)
    echo "用法: $0 [all|backend|frontend]" >&2
    exit 1
    ;;
esac

if [[ "${#PIDS[@]}" -eq 0 ]]; then
  echo "[start.sh] 没有成功启动任何服务。"
  exit 1
fi

echo
echo "[start.sh] 已启动，按 Ctrl+C 停止。"
echo "[start.sh]   后端: http://localhost:${SERVER_PORT:-8080}"
echo "[start.sh]   前端: http://localhost:5173"
wait
