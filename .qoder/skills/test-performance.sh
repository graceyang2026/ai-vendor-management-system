#!/usr/bin/env bash
# test-performance.sh
# 算分引擎专项测试脚本：只跑 com.srm.core.calculator 包下的测试,
# 用于快速验证 Q/D/P/S/C 五维公式、评级边界(90/80/70)与 0 批次免考等关键场景,
# 不必等全量 mvn test 跑完就能拿到反馈。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
BACKEND_DIR="$PROJECT_ROOT/srm-backend"

echo "[test-performance] 运行算分引擎(calculator 包)专项测试..."
(cd "$BACKEND_DIR" && ./mvnw -q test -Dtest="com.srm.core.calculator.**")

echo "[test-performance] 通过。若新增/修改了 Q/D/P/S/C 公式或评级阈值,请确认以下边界场景均有对应测试:"
echo "  - qc_total_batches = 0 (质量维度免考,打'当期无业务发生-免考'标签)"
echo "  - 综合得分恰好落在 90 / 80 / 70 边界上下的评级判定"
echo "  - total_batches = 0 (交付维度除零保护)"
echo "  - price_deviation_rate / complaint_overtime_count 极大值时得分被 MAX(0, ...) 截断为 0"
echo "  - 资质过期触发合规维度 C=0 与 risk_warning"
echo "  - ManualFactInputAdapter 对非法事实数据(负数、qc_failed_batches > qc_total_batches 等)的拒绝路径"
echo "  - MockDataMetricAdapter 生成的数据同样经过 PerformanceScoreCalculator 计算,而不是绕过它"
