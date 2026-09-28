#!/usr/bin/env bash
# init-srm-infra.sh
# 在当前项目根目录下自动创建/重建 Qoder 的全部基础设施配置文件:
#   1. .qoder/rules.md
#   2. .qoder/settings.json
#   3. .qoder/agents/coverage-fixer.json
#   4. .qoder/agents/backend-dev.json
#   5. .qoder/agents/frontend-dev.json
#   6. .qoder/skills/check-coverage.sh
#   7. .qoder/skills/test-performance.sh
#   8. .mcp/mcp.json
#
# 用法:
#   chmod +x init-srm-infra.sh
#   ./init-srm-infra.sh
#
# 已存在的文件会先备份为 <file>.bak.<timestamp>,再写入新内容。

set -euo pipefail

PROJECT_ROOT="$(pwd)"
TIMESTAMP="$(date +%Y%m%d%H%M%S)"

echo "[init-srm-infra] 项目根目录: $PROJECT_ROOT"

backup_if_exists() {
  local target="$1"
  if [[ -f "$target" ]]; then
    local backup="${target}.bak.${TIMESTAMP}"
    cp "$target" "$backup"
    echo "[init-srm-infra] 已存在,备份到: $backup"
  fi
}

mkdir -p "$PROJECT_ROOT/.qoder/agents"
mkdir -p "$PROJECT_ROOT/.qoder/skills"
mkdir -p "$PROJECT_ROOT/.mcp"

# ---------------------------------------------------------------------------
# 1. .qoder/rules.md
# ---------------------------------------------------------------------------
RULES_FILE="$PROJECT_ROOT/.qoder/rules.md"
backup_if_exists "$RULES_FILE"

cat > "$RULES_FILE" <<'EOF'
# Qoder 项目规则（供 AI Agent 遵循）

本仓库是前后端分离的双工程：`srm-backend/`（Spring Boot）+ `srm-frontend/`（Vue 3）。**任何跨前后端的接口改动，必须先改 `docs/api-spec.md`，再改代码**；代码与契约不一致时以契约为准，发现不一致要停下来修契约或修代码，不能各写各的。

## 1. 全栈技术栈规范

### 后端 (`srm-backend/`)
- Spring Boot 3.5.14，Java 17，Maven（`cd srm-backend && ./mvnw ...`）
- 持久层：**MyBatis-Plus**（`com.baomidou:mybatis-plus-spring-boot3-starter`），禁止引入 Spring Data JPA / Hibernate
  - 表结构以 `src/main/resources/schema.sql` 为唯一事实来源（MyBatis-Plus 不做自动建表），改字段先改这个文件
  - 复杂查询写在 `src/main/resources/mapper/*.xml`，SQL 避免 MySQL 专有函数（如 `IFNULL` 可用但避免 `GROUP_CONCAT`/`ON DUPLICATE KEY` 等强绑定写法），保证未来可迁移到 KingbaseES
  - 乐观锁：实体字段 `@Version private Integer version;` + `MybatisPlusConfig` 中注册 `OptimisticLockerInnerInterceptor`
  - 逻辑删除：实体字段 `@TableLogic private Integer deleted;`，全局配置已在 `application.yml` 里设置
- 数据库唯一为 MySQL（生产）/ 兼容 KingbaseES（演进），本地测试可用 H2；数据库凭据只放根目录 `.env`（参照 `.env.example`），禁止硬编码进代码或提交仓库
- 代码生成：Lombok（`@Data`/`@Builder` 等）
- 包命名：`com.srm.core`，新建类必须落在该包结构下：
  - `entity`：MyBatis-Plus `@TableName` 实体
  - `mapper`：`BaseMapper<T>` 接口
  - `service` + `service/impl`：接口 + 实现类模式
  - `controller`：构造器注入，返回 `ApiResponse<T>`（见 `common` 包的统一响应体）
  - `calculator`：`PerformanceMetricProvider` 接口、两个适配器、唯一的 `PerformanceScoreCalculator`
  - `dto`：请求/响应体，字段名与 `docs/api-spec.md` 完全一致（snake_case，不做大小写转换）
  - `security`：JWT 鉴权 + 角色校验
  - `common`：枚举、异常、统一响应体
  - `config`：MyBatis-Plus 插件、Spring Security 配置、演示账号种子

### 前端 (`srm-frontend/`)
- Vue 3 + `<script setup>` + TypeScript + Vite + Element Plus
- `src/api/` 用 axios 封装，按模块拆分请求函数；请求/响应类型必须来自 `src/types/`，禁止用 `any` 绕过契约
- `src/types/` 的接口定义必须与 `docs/api-spec.md` 逐字段对齐
- 路由级角色守卫：未登录跳转登录页；角色不匹配的页面/按钮直接隐藏（但这只是体验优化，真正的权限拦截在后端）

## 2. TDD 业务铁律

1. **先写测试，再写实现**：任何新功能/修复必须先补充或编写失败的单元测试（Red），再编写最小实现使其通过（Green），最后重构（Refactor）。
2. **禁止无测试提交业务逻辑**：Service/Controller 层的新增或修改方法，必须同批提交对应的 `*Test.java`。
3. **测试框架统一**：后端使用 JUnit 5 + Mockito（`@Mock`、`@InjectMocks`），不引入其他测试框架。
4. **边界与异常路径必须覆盖**：不仅测试 happy path，还要覆盖参数校验失败、空值、异常抛出、乐观锁冲突等分支。
5. **重构不改变可观察行为**：重构前后必须保证既有测试全部通过，不得为了让测试通过而弱化断言。

## 3. 单元测试覆盖率要求

- 后端 JaCoCo 统计的**行覆盖率**与**分支覆盖率**必须 **> 85%**。
- 覆盖率不达标的提交/PR 不允许合并。
- 新增或修改的类/方法必须使覆盖率不低于合并前水平。
- 覆盖率校验脚本：`.qoder/skills/check-coverage.sh`（后端 JaCoCo + 前端 `npm run build` 二合一）。
- 算分引擎专项测试脚本：`.qoder/skills/test-performance.sh`。
- 覆盖率自愈子代理：`.qoder/agents/coverage-fixer.json`（`coverage-fixer`），当覆盖率不达标时自动分析 JaCoCo 报告并补充测试用例。

## 4. 业务领域规则（源自 `docs/TDD.docx` 技术设计文档）

> 注意区分：本节的"TDD"指 `docs/TDD.docx`/`docs/TDD.pdf`（Technical Design Document，技术设计文档），与第 2 节"TDD 业务铁律"（Test-Driven Development）是两个不同含义的同一缩写，不要混淆。任何与下列规则冲突的代码改动，先查阅 `docs/TDD.docx` 原文和 `docs/api-spec.md` 再动手。

### 4.1 角色与 RBAC 权限矩阵

- 三大角色：`ADMIN`（系统管理员）、`STAFF`（业务员）、`AUDITOR`（审核员）。ADMIN 原则上**不参与**供应商业务数据的新增/修改/审批，管理权限与业务权限严格分离。
- 前后端必须**同时**执行 RBAC 校验，不能只依赖前端隐藏按钮：
  - 供应商信息新增/编辑：仅 `STAFF`，且仅限【草稿】/【退回】状态；【待审核】及以后状态后端强制拒绝写操作。
  - 资质审核（通过/驳回）、绩效复核、停用/淘汰最终决策：仅 `AUDITOR`。
  - 停用/恢复申请：`STAFF` 可提出申请，`AUDITOR` 才能做最终决策。
  - 用户/角色管理：仅 `ADMIN`。
  - 删除供应商草稿：仅本人 `STAFF`，且仅限本人草稿/待提交状态；正式流程记录禁止物理删除，只能逻辑删除。

### 4.2 供应商生命周期状态机与并发控制

- 主流程：草稿(DRAFT) → 提交 → 待审核(PENDING_REVIEW) → AUDITOR 审核 → 通过进入正常合作(NORMAL)；驳回则退回 STAFF 修改(RETURNED)后重新提交；NORMAL/SUSPENDED 均可进入淘汰(ELIMINATED)。
- 供应商记录处于【待审核 / PENDING_REVIEW】状态时必须加**乐观锁**（`@Version`），拒绝 STAFF 对同一记录的任何修改/删除请求，防止 AUDITOR 审批过程中的并发冲突。
- 状态变更只能按状态机合法迁移，禁止跳状态；所有状态变更必须写入状态历史与 AuditLog（操作者、时间、操作类型、旧状态、新状态、结果、审核意见）。
- 停用/恢复走独立的 `LifecycleRequest` 两步流程（STAFF 申请 + AUDITOR 终审），不允许接口直接 PATCH 供应商状态字段。
- 同一供应商同一考核周期内，只允许存在一张【草稿】或【待复核】的绩效评价单；上一张未经 AUDITOR 终审归档前，系统必须全局锁定"发起评价"入口，防止多线程算分冲突。

### 4.3 绩效评价：前后端数据隔离与防篡改（核心铁律）

- **前端只能提交客观事实，绝不允许提交或覆盖分数/评级**。前端输入结构为 `PerformanceFactRecord`，仅包含：
  - 交货维度：`total_batches`、`delayed_batches`、`avg_delay_days`
  - 质量维度：`qc_total_batches`、`qc_failed_batches`、`major_accidents`
  - 价格与服务维度：`price_deviation_rate`、`complaint_overtime_count`
  - 合规维度：由后端自动读取 `Qualification.expiry_date`，前端不传
- 综合得分、Q/D/P/S/C 五维得分、A/B/C/D 评级**必须在后端硬编码计算**，任何 Controller/Service 不得接受前端传入的分数或评级字段并直接持久化；这是接口设计与代码评审的强制检查项（`docs/api-spec.md` 第 4 节的 DTO 定义里物理上不存在分数字段）。
- 五维得分公式（硬编码，不得配置化成可被前端影响的参数），详见 `docs/api-spec.md` 第 4.1 节；`qc_total_batches = 0` 时质量维度记 100 分并打"当期无业务发生-免考"标签。
- 综合得分 `= Q*30% + D*25% + P*20% + S*15% + C*10%`；评级：`A` ≥90；`80 ≤ B < 90`；`70 ≤ C < 80`；`D` <70。
- 评级与状态机的联动：A/B/C 级不自动变更供应商状态（C 级仅打风险预警）；D 级只打风险预警，不自动 `SUSPENDED`——必须由人工发起停用申请并经 AUDITOR 审批（见 4.2 的 `LifecycleRequest`）才能落地。
- 评分提交后立即进入【待复核】且数据不可逆锁死；经 AUDITOR 复核通过后写入 `PerformanceEvaluation` 表形成永久历史，不得覆盖旧评价；复核驳回则 `RETURNED`，原 STAFF 可改后重提（沿用同一条记录）。

### 4.4 适配器模式：`PerformanceMetricProvider`

- P6（维度录入）与 P7（加权计算）统一抽象为 `PerformanceMetricProvider` 接口，当前 MVP 提供：
  - `ManualFactInputAdapter`：生产环境默认实现，STAFF 页面录入客观事实，内置评分范围与必填字段强校验。
  - `MockDataMetricAdapter`：DEMO/测试模式专用，按预设规则或随机种子自动生成履约流水数据（如总批次、逾期批次等），直接注入算分引擎，无需依赖 ERP/WMS。
- 二期演进预留 `ExternalSystemMetricAdapter`，用于零代码切换到 ERP/WMS 实时采集，新增该适配器不应要求修改 P6/P7 之外的既有代码（接口对扩展开放、对修改封闭）。
- 算分本身（P7）由唯一的 `PerformanceScoreCalculator` 完成，不是接口的一部分，任何 adapter 都只负责"取事实数据"再交给它。
- Controller 按请求 `mode` 字段从 `Map<String, PerformanceMetricProvider>`（Spring 按 bean name 注入）取 provider，禁止在 Controller/Service 中直接 if-else 判断"是否为 mock 模式"。

### 4.5 审计与不可变性

- 关键业务操作（状态变更、审核决策、绩效复核、退回重填）必须全量写入 `AuditLog`，记录操作者、时间、操作类型、旧/新状态、结果与意见。
- 正式流程记录（已提交审核、已归档的绩效评价等）禁止物理删除，只允许逻辑删除且仅限规则允许的草稿。

## 5. 其他约束

- 运行后端：`cd srm-backend && ./mvnw spring-boot:run`
- 构建后端：`cd srm-backend && ./mvnw clean package`
- 全量测试后端：`cd srm-backend && ./mvnw test`
- 单个测试类：`cd srm-backend && ./mvnw test -Dtest=SupplierServiceImplTest`
- 运行前端：`cd srm-frontend && npm run dev`
- 构建前端：`cd srm-frontend && npm run build`
- 详细业务设计原文：`docs/TDD.docx` / `docs/TDD.pdf`；需求补充：`docs/供应商管理系统需求规格说明书-补充非MVP V1.5_20260922待评审.docx`；接口契约：`docs/api-spec.md`
EOF
echo "[init-srm-infra] 已写入: $RULES_FILE"

# ---------------------------------------------------------------------------
# 2. .qoder/settings.json
# ---------------------------------------------------------------------------
SETTINGS_FILE="$PROJECT_ROOT/.qoder/settings.json"
backup_if_exists "$SETTINGS_FILE"

cat > "$SETTINGS_FILE" <<'EOF'
{
  "description": "修改后端 Java / 前端 Vue/TS 文件后自动执行编译与测试的 Hook 配置",
  "hooks": {
    "PostToolUse": [
      {
        "matcher": {
          "tools": ["Edit", "Write", "MultiEdit"],
          "filePattern": "srm-backend/**/*.java"
        },
        "hooks": [
          {
            "type": "command",
            "command": "cd srm-backend && ./mvnw -q test",
            "description": "修改后端 Java 文件后自动跑单元测试"
          },
          {
            "type": "command",
            "command": "bash .qoder/skills/check-coverage.sh",
            "description": "校验 JaCoCo 覆盖率是否 > 85%,不达标时提示调用 coverage-fixer 子代理"
          }
        ]
      },
      {
        "matcher": {
          "tools": ["Edit", "Write", "MultiEdit"],
          "filePattern": "srm-frontend/**/*.{vue,ts}"
        },
        "hooks": [
          {
            "type": "command",
            "command": "cd srm-frontend && npm run build",
            "description": "修改前端 Vue/TS 文件后自动执行类型检查与构建,确保契约类型对齐"
          }
        ]
      }
    ]
  }
}
EOF
echo "[init-srm-infra] 已写入: $SETTINGS_FILE"

# ---------------------------------------------------------------------------
# 3. .qoder/agents/coverage-fixer.json
# ---------------------------------------------------------------------------
COVERAGE_AGENT_FILE="$PROJECT_ROOT/.qoder/agents/coverage-fixer.json"
backup_if_exists "$COVERAGE_AGENT_FILE"

cat > "$COVERAGE_AGENT_FILE" <<'EOF'
{
  "name": "coverage-fixer",
  "description": "专精 JaCoCo 单元测试覆盖率自愈的 Subagent:解析覆盖率报告,定位未覆盖代码分支,遵循 TDD 铁律自动补充 JUnit5 + Mockito 测试用例,直至行覆盖率与分支覆盖率均 > 85%。",
  "model": "inherit",
  "tools": ["Read", "Edit", "Write", "Bash", "Grep", "Glob"],
  "workingDirectory": "srm-backend",
  "coverageThreshold": 85,
  "systemPrompt": "你是本项目 srm-backend(Spring Boot 3.5.14 + MyBatis-Plus + MySQL, Java 17, 包名 com.srm.core)专精 JaCoCo 覆盖率自愈的子代理。目标:让 srm-backend/target/site/jacoco 报告中所有模块的行覆盖率与分支覆盖率均超过 85%。工作方式必须遵循项目 TDD 铁律(.qoder/rules.md 第 2 节):测试框架统一为 JUnit 5 + Mockito(@Mock / @InjectMocks),Service 层遵循接口+实现类模式,Controller 遵循构造器注入。禁止为了拉高数字而写无意义的空断言测试,每个新增测试必须验证真实业务行为(正常路径 + 边界/异常路径)。补充绩效评价/供应商生命周期相关测试时,必须遵循 .qoder/rules.md 第 4 节(源自 docs/TDD.docx 与 docs/api-spec.md)的业务铁律:不得编写断言前端可直接传入或覆盖分数/评级的测试,必须校验 Q/D/P/S/C 五维公式、A/B/C/D 评级映射、PENDING_REVIEW 状态下的乐观锁(@Version)冲突,以及 PerformanceMetricProvider 各适配器(ManualFactInputAdapter/MockDataMetricAdapter)只负责取数、不做算分的边界。",
  "workflow": [
    "1. 在 srm-backend/ 下执行 ./mvnw test jacoco:report 生成最新覆盖率报告",
    "2. 解析 srm-backend/target/site/jacoco/jacoco.csv,按包/类定位覆盖率低于 85% 的目标",
    "3. 使用 Read/Grep 阅读对应源码,理解未覆盖分支的业务逻辑与边界条件",
    "4. 在对应的 *Test.java 中补充 JUnit5 + Mockito 单元测试(先确认测试失败,再确认通过)",
    "5. 重新执行 ./mvnw test jacoco:report 验证覆盖率是否达标",
    "6. 若仍未达标,重复步骤 2-5,最多迭代 5 次;若 5 次后仍未达标,汇报剩余未覆盖的类清单"
  ],
  "successCriteria": "cd srm-backend && ./mvnw test 全部通过,且所有模块行覆盖率与分支覆盖率均 > 85%",
  "invocation": {
    "manual": true,
    "autoTriggerOn": "check-coverage.sh 返回非 0 退出码"
  }
}
EOF
echo "[init-srm-infra] 已写入: $COVERAGE_AGENT_FILE"

# ---------------------------------------------------------------------------
# 4. .qoder/agents/backend-dev.json
# ---------------------------------------------------------------------------
BACKEND_AGENT_FILE="$PROJECT_ROOT/.qoder/agents/backend-dev.json"
backup_if_exists "$BACKEND_AGENT_FILE"

cat > "$BACKEND_AGENT_FILE" <<'EOF'
{
  "name": "backend-dev",
  "description": "后端专精 Subagent:只在 srm-backend/ 下工作,负责供应商生命周期状态机、RBAC、绩效算分引擎、审计日志等 SRM MVP 核心业务的实现与维护。",
  "model": "inherit",
  "tools": ["Read", "Edit", "Write", "Bash", "Grep", "Glob"],
  "workingDirectory": "srm-backend",
  "systemPrompt": "你是 SRM MVP 项目的后端专精子代理,只负责 srm-backend/(Spring Boot 3.5.14, Java 17, MyBatis-Plus, 包名 com.srm.core)。严格遵循 .qoder/rules.md 全部规则,尤其是第 4 节业务铁律与 docs/api-spec.md 的接口契约——两者不一致时先停下来核对,不能各写各的。核心红线:(1) 前端只能提交 PerformanceFactRecord 客观事实,分数/评级永远由后端 PerformanceScoreCalculator 硬编码计算,任何 DTO 不得包含可被前端写入的分数字段;(2) PENDING_REVIEW 状态的供应商记录必须靠 @Version 乐观锁 + 服务层显式拒绝双重保护;(3) 状态机只能按合法路径迁移,停用/恢复必须走 LifecycleRequest 两步流程,不允许直接 PATCH 状态字段;(4) 关键操作全部写 AuditLog;(5) PerformanceMetricProvider 的两个适配器只负责取数,算分永远交给唯一的 PerformanceScoreCalculator,Controller 用 Map<String, PerformanceMetricProvider> 按 mode 取 bean,禁止 if-else 判断 mock。任何新增/修改的 Service、Controller 方法必须遵循先写测试再写实现(Red-Green-Refactor),不允许无测试提交业务逻辑。改动会影响前端契约时,先更新 docs/api-spec.md。",
  "invocation": {
    "manual": true
  }
}
EOF
echo "[init-srm-infra] 已写入: $BACKEND_AGENT_FILE"

# ---------------------------------------------------------------------------
# 5. .qoder/agents/frontend-dev.json
# ---------------------------------------------------------------------------
FRONTEND_AGENT_FILE="$PROJECT_ROOT/.qoder/agents/frontend-dev.json"
backup_if_exists "$FRONTEND_AGENT_FILE"

cat > "$FRONTEND_AGENT_FILE" <<'EOF'
{
  "name": "frontend-dev",
  "description": "前端专精 Subagent:只在 srm-frontend/ 下工作,负责 Vue3 + TS + Vite + Element Plus 页面实现,与 docs/api-spec.md 契约保持严格一致。",
  "model": "inherit",
  "tools": ["Read", "Edit", "Write", "Bash", "Grep", "Glob"],
  "workingDirectory": "srm-frontend",
  "systemPrompt": "你是 SRM MVP 项目的前端专精子代理,只负责 srm-frontend/(Vue 3 + <script setup> + TypeScript + Vite + Element Plus)。严格遵循 .qoder/rules.md 与 docs/api-spec.md——所有 TS 接口字段名(snake_case)、请求/响应结构、分页包装 PageResult<T>、统一响应体 ApiResponse<T>、乐观锁 version 字段约定,必须与 docs/api-spec.md 逐字段对齐,不能自行改字段风格或凑合命名。核心红线:(1) 绩效录入页面(PerformanceInput)只能提交 PerformanceFactRecord 中列出的客观事实字段,前端代码里不能出现任何写入分数/评级字段的逻辑,分数/评级只能是只读展示;(2) 按钮的置灰/隐藏必须依据当前登录用户角色(ADMIN/STAFF/AUDITOR)与供应商当前状态两个维度共同判断,越权操作即使后端会拦截,前端也要提前禁用;(3) 路由需要角色守卫,未授权角色访问对应页面直接跳转或提示,而不是依赖页面内部隐藏内容。所有新增/修改的 .vue、.ts 文件必须通过 npm run build(包含类型检查)才算完成。",
  "invocation": {
    "manual": true
  }
}
EOF
echo "[init-srm-infra] 已写入: $FRONTEND_AGENT_FILE"

# ---------------------------------------------------------------------------
# 6. .qoder/skills/check-coverage.sh
# ---------------------------------------------------------------------------
CHECK_FILE="$PROJECT_ROOT/.qoder/skills/check-coverage.sh"
backup_if_exists "$CHECK_FILE"

cat > "$CHECK_FILE" <<'EOF'
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
EOF
chmod +x "$CHECK_FILE"
echo "[init-srm-infra] 已写入并授予执行权限: $CHECK_FILE"

# ---------------------------------------------------------------------------
# 7. .qoder/skills/test-performance.sh
# ---------------------------------------------------------------------------
PERF_TEST_FILE="$PROJECT_ROOT/.qoder/skills/test-performance.sh"
backup_if_exists "$PERF_TEST_FILE"

cat > "$PERF_TEST_FILE" <<'EOF'
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
EOF
chmod +x "$PERF_TEST_FILE"
echo "[init-srm-infra] 已写入并授予执行权限: $PERF_TEST_FILE"

# ---------------------------------------------------------------------------
# 8. .mcp/mcp.json
# ---------------------------------------------------------------------------
MCP_FILE="$PROJECT_ROOT/.mcp/mcp.json"
backup_if_exists "$MCP_FILE"

cat > "$MCP_FILE" <<'EOF'
{
  "mcpServers": {
    "mysql": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-mysql"],
      "env": {
        "MYSQL_HOST": "${DB_HOST}",
        "MYSQL_PORT": "${DB_PORT}",
        "MYSQL_USER": "${DB_USER}",
        "MYSQL_PASSWORD": "${DB_PASSWORD}",
        "MYSQL_DATABASE": "${DB_NAME}"
      },
      "description": "读取项目根目录 .env 中的数据库凭据,连接 srm-backend 使用的 vendor_db 执行只读/管理查询"
    },
    "git": {
      "command": "uvx",
      "args": ["mcp-server-git", "--repository", "."],
      "description": "对当前仓库(srm-backend + srm-frontend 双工程)执行 git 状态查询、diff、log 等操作"
    },
    "terminal": {
      "command": "npx",
      "args": ["-y", "mcp-server-commands"],
      "env": {
        "ALLOWED_CWD": "."
      },
      "description": "在项目根目录下执行受限终端命令(如 cd srm-backend && ./mvnw test、cd srm-frontend && npm run build),用于测试与覆盖率自愈流程"
    }
  }
}
EOF
echo "[init-srm-infra] 已写入: $MCP_FILE"

echo
echo "[init-srm-infra] 完成。已生成/更新以下文件:"
echo "  - .qoder/rules.md"
echo "  - .qoder/settings.json"
echo "  - .qoder/agents/coverage-fixer.json"
echo "  - .qoder/agents/backend-dev.json"
echo "  - .qoder/agents/frontend-dev.json"
echo "  - .qoder/skills/check-coverage.sh (可执行)"
echo "  - .qoder/skills/test-performance.sh (可执行)"
echo "  - .mcp/mcp.json"
echo
echo "[init-srm-infra] 提醒: 请根据实际 .env 中的变量名(DB_HOST/DB_PORT/DB_USER/DB_PASSWORD/DB_NAME)核对 .mcp/mcp.json 中的映射是否一致。"
