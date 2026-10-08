#!/usr/bin/env bash
# check-coverage.sh
#
# SRM 项目质量门禁
#
# 用法：
#   bash check-coverage.sh backend
#   bash check-coverage.sh frontend
#   bash check-coverage.sh all
#
# 退出码：
#   0 = 检查通过
#   1 = 检查失败

set -uo pipefail

MODE="${1:-all}"

THRESHOLD="${COVERAGE_THRESHOLD:-85}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

BACKEND_DIR="$PROJECT_ROOT/srm-backend"
FRONTEND_DIR="$PROJECT_ROOT/srm-frontend"

JACOCO_CSV="$BACKEND_DIR/target/site/jacoco/jacoco.csv"

OVERALL_STATUS=0


# ============================================================
# Backend
# ============================================================

check_backend() {
  echo
  echo "=========================================="
  echo " 后端质量检查"
  echo "=========================================="

  if [[ ! -d "$BACKEND_DIR" ]]; then
    echo "[check-coverage] 未找到后端目录: $BACKEND_DIR" >&2
    return 1
  fi

  echo "[check-coverage] 执行 Maven 测试..."

  (
    cd "$BACKEND_DIR" &&
    ./mvnw -q test
  )

  BACKEND_TEST_EXIT=$?

  if [[ "$BACKEND_TEST_EXIT" -ne 0 ]]; then
    echo "[check-coverage] ❌ 后端测试失败" >&2
    return 1
  fi

  echo "[check-coverage] ✅ 后端测试通过"


  # ----------------------------------------------------------
  # JaCoCo
  # ----------------------------------------------------------

  echo
  echo "[check-coverage] 检查 JaCoCo 覆盖率..."

  if [[ ! -f "$JACOCO_CSV" ]]; then
    echo "[check-coverage] ❌ 未找到覆盖率报告:"
    echo "                $JACOCO_CSV"
    echo "[check-coverage] 请确认 pom.xml 已配置 jacoco-maven-plugin 并生成 CSV 报告" >&2
    return 1
  fi

  read -r LINE_MISSED LINE_COVERED BRANCH_MISSED BRANCH_COVERED < <(
    awk -F, '
      NR > 1 {
        lm += $8
        lc += $9
        bm += $6
        bc += $7
      }
      END {
        print lm+0, lc+0, bm+0, bc+0
      }
    ' "$JACOCO_CSV"
  )

  LINE_TOTAL=$((LINE_MISSED + LINE_COVERED))
  BRANCH_TOTAL=$((BRANCH_MISSED + BRANCH_COVERED))

  if [[ "$LINE_TOTAL" -eq 0 ]]; then
    echo "[check-coverage] ❌ 覆盖率数据为空，无法计算" >&2
    return 1
  fi

  LINE_COVERAGE=$(
    awk \
      -v covered="$LINE_COVERED" \
      -v total="$LINE_TOTAL" \
      'BEGIN { printf "%.2f", (covered / total) * 100 }'
  )

  if [[ "$BRANCH_TOTAL" -gt 0 ]]; then
    BRANCH_COVERAGE=$(
      awk \
        -v covered="$BRANCH_COVERED" \
        -v total="$BRANCH_TOTAL" \
        'BEGIN { printf "%.2f", (covered / total) * 100 }'
    )
  else
    BRANCH_COVERAGE="N/A"
  fi

  echo "[check-coverage] 行覆盖率: ${LINE_COVERAGE}%"
  echo "[check-coverage] 分支覆盖率: ${BRANCH_COVERAGE}%"
  echo "[check-coverage] 要求: >= ${THRESHOLD}%"

  LINE_PASS=$(
    awk \
      -v coverage="$LINE_COVERAGE" \
      -v threshold="$THRESHOLD" \
      'BEGIN { print (coverage >= threshold) ? "1" : "0" }'
  )

  if [[ "$BRANCH_TOTAL" -gt 0 ]]; then
    BRANCH_PASS=$(
      awk \
        -v coverage="$BRANCH_COVERAGE" \
        -v threshold="$THRESHOLD" \
        'BEGIN { print (coverage >= threshold) ? "1" : "0" }'
    )
  else
    BRANCH_PASS=1
  fi

  if [[ "$LINE_PASS" -eq 1 && "$BRANCH_PASS" -eq 1 ]]; then
    echo "[check-coverage] ✅ 后端覆盖率达标"
    return 0
  fi

  echo "[check-coverage] ❌ 后端覆盖率未达标"
  echo "[check-coverage] 建议调用 coverage-fixer 子代理补充测试" >&2

  return 1
}


# ============================================================
# Frontend
# ============================================================

check_frontend() {
  echo
  echo "=========================================="
  echo " 前端质量检查"
  echo "=========================================="

  if [[ ! -d "$FRONTEND_DIR" ]]; then
    echo "[check-coverage] ❌ 未找到前端目录:"
    echo "                $FRONTEND_DIR" >&2
    return 1
  fi

  if [[ ! -f "$FRONTEND_DIR/package.json" ]]; then
    echo "[check-coverage] ❌ 未找到 package.json" >&2
    return 1
  fi

  echo "[check-coverage] 执行 npm run build..."

  (
    cd "$FRONTEND_DIR" &&
    npm run build
  )

  FRONTEND_EXIT=$?

  if [[ "$FRONTEND_EXIT" -ne 0 ]]; then
    echo "[check-coverage] ❌ 前端 build 失败" >&2
    return 1
  fi

  echo "[check-coverage] ✅ 前端 build 通过"

  return 0
}


# ============================================================
# Main
# ============================================================

case "$MODE" in

  backend)
    check_backend || OVERALL_STATUS=1
    ;;

  frontend)
    check_frontend || OVERALL_STATUS=1
    ;;

  all)
    check_backend || OVERALL_STATUS=1
    check_frontend || OVERALL_STATUS=1
    ;;

  *)
    echo "用法:"
    echo "  $0 backend"
    echo "  $0 frontend"
    echo "  $0 all"
    exit 1
    ;;

esac


echo
echo "=========================================="

if [[ "$OVERALL_STATUS" -eq 0 ]]; then
  echo " [check-coverage] ✅ Quality Gate PASSED"
else
  echo " [check-coverage] ❌ Quality Gate FAILED"
fi

echo "=========================================="

exit "$OVERALL_STATUS"