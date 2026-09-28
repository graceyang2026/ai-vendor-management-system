#!/usr/bin/env bash
# check-coverage.sh
# 后端 JaCoCo 覆盖率校验 + 前端 npm build 二合一质量门禁。
# 退出码: 0 = 全部达标, 1 = 任一项未达标或报告缺失。

set -uo pipefail

THRESHOLD="${COVERAGE_THRESHOLD:-85}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
BACKEND_DIR="$PROJECT_ROOT/srm-backend"
FRONTEND_DIR="$PROJECT_ROOT/srm-frontend"
JACOCO_CSV="$BACKEND_DIR/target/site/jacoco/jacoco.csv"

OVERALL_STATUS=0

echo "[check-coverage] ===== 后端 JaCoCo 覆盖率 ====="
(cd "$BACKEND_DIR" && ./mvnw -q test)
BACKEND_TEST_EXIT=$?

if [[ ! -f "$JACOCO_CSV" ]]; then
  echo "[check-coverage] 未找到覆盖率报告: $JACOCO_CSV" >&2
  echo "[check-coverage] 请确认 srm-backend/pom.xml 中已配置 jacoco-maven-plugin 并生成 CSV 格式报告" >&2
  OVERALL_STATUS=1
else
  read -r LINE_MISSED LINE_COVERED BRANCH_MISSED BRANCH_COVERED < <(
    awk -F, 'NR>1 { lm+=$8; lc+=$9; bm+=$6; bc+=$7 } END { print lm, lc, bm, bc }' "$JACOCO_CSV"
  )
  LINE_TOTAL=$((LINE_MISSED + LINE_COVERED))
  BRANCH_TOTAL=$((BRANCH_MISSED + BRANCH_COVERED))

  if [[ "$LINE_TOTAL" -eq 0 ]]; then
    echo "[check-coverage] 覆盖率数据为空,无法计算" >&2
    OVERALL_STATUS=1
  else
    LINE_COVERAGE=$(awk -v c="$LINE_COVERED" -v t="$LINE_TOTAL" 'BEGIN { printf "%.2f", (c/t)*100 }')
    if [[ "$BRANCH_TOTAL" -gt 0 ]]; then
      BRANCH_COVERAGE=$(awk -v c="$BRANCH_COVERED" -v t="$BRANCH_TOTAL" 'BEGIN { printf "%.2f", (c/t)*100 }')
    else
      BRANCH_COVERAGE="N/A"
    fi
    echo "[check-coverage] 行覆盖率: ${LINE_COVERAGE}% | 分支覆盖率: ${BRANCH_COVERAGE}% (要求 >= ${THRESHOLD}%)"

    LINE_PASS=$(awk -v cov="$LINE_COVERAGE" -v th="$THRESHOLD" 'BEGIN { print (cov >= th) ? "1" : "0" }')
    if [[ "$BRANCH_TOTAL" -gt 0 ]]; then
      BRANCH_PASS=$(awk -v cov="$BRANCH_COVERAGE" -v th="$THRESHOLD" 'BEGIN { print (cov >= th) ? "1" : "0" }')
    else
      BRANCH_PASS=1
    fi

    if [[ "$LINE_PASS" -eq 1 && "$BRANCH_PASS" -eq 1 && "$BACKEND_TEST_EXIT" -eq 0 ]]; then
      echo "[check-coverage] 后端覆盖率达标"
    else
      echo "[check-coverage] 后端覆盖率未达标或测试未通过,建议调用 coverage-fixer 子代理自动补测" >&2
      OVERALL_STATUS=1
    fi
  fi
fi

echo
echo "[check-coverage] ===== 前端 npm build ====="
if [[ -d "$FRONTEND_DIR" ]]; then
  (cd "$FRONTEND_DIR" && npm run build)
  if [[ $? -eq 0 ]]; then
    echo "[check-coverage] 前端 build 通过"
  else
    echo "[check-coverage] 前端 build 失败" >&2
    OVERALL_STATUS=1
  fi
else
  echo "[check-coverage] 未找到 srm-frontend/ 目录,跳过前端检查" >&2
fi

exit "$OVERALL_STATUS"
