# AI 辅助开发执行过程与质量验证记录

> 审计对象：SRM 供应商管理系统（分支 `MVP-W4-develop`）2026-10-14 至 2026-10-15 的 AI 辅助开发活动
> 审计时间：2026-10-15 22:53（本机 `date` 实测）
> 审计人角色：资深全栈工程师 / AI 辅助开发流程审计 / 技术文档工程师（用户临时授权，只读取证 + 本文档产出，未修改任何业务代码、测试、Prompt、Skill、Hook 或 Agent 配置）
> 覆盖范围：模块一·供应商管理收尾与 bug 修复、模块二·状态机管理、模块三·绩效评价（后端 5 接口 + 算分引擎、前端录入/复核/审核页增量）

**证据状态图例**：🟢 有直接证据 ｜ 🟡 有部分证据 ｜ 🔵 仅能从代码推断 ｜ ⚪ 缺少证据 ｜ 🔄 后续补充验证

---

## 一、提示词约束（Prompt Constraints）

### 1.1 实际存在的约束配置

| 约束载体 | 文件 | 关键条款（实测行号） | 目的 |
| :--- | :--- | :--- | :--- |
| 项目铁律 | `.qoder/rules.md` | L8-9「门禁失败不得宣称完成、禁止删测试/弱断言/降阈值」；L47-56「后端 JaCoCo 行/分支 ≥85%、覆盖率脚本 `.qoder/skills/check-coverage.sh`」 | 防止 AI 自写自判、绕过质量 |
| PostToolUse Hook | `.qoder/settings.json` | matcher=`Edit\|Write\|MultiEdit`，命令 `bash .qoder/skills/check-coverage.sh all` | 任何代码写入后自动跑全量门禁 |
| 门禁脚本 | `.qoder/skills/check-coverage.sh` | `THRESHOLD="${COVERAGE_THRESHOLD:-85}"`；后端 `./mvnw -q test` + JaCoCo CSV 解析；前端 `npm run build` | 把铁律落成可执行断言 |
| 子代理契约 | `.qoder/agents/backend-dev.md` | L22-26 五条领域铁律（防篡改入参、双重锁、状态机合法迁移、全量审计、适配器只取数不算分）；L48-49 禁令 | 限定 AI 的实现自由度 |
| 子代理契约 | `.qoder/agents/frontend-dev.md` L52、`.qoder/agents/tech-lead.md` L11/L27 | 目录所有权边界（frontend-dev 只能改 `srm-frontend/`，`docs/record/` 留痕是唯一例外；tech-lead 为只读编排者，两代码目录绝不直改） | 防止越权改动与私自改契约 |
| 仓库级指令 | `AGENTS.md` | 质量门禁铁律四句（与 rules.md 同源） | 对任何 AI 会话生效 |

### 1.2 约束「实际生效」的证据（不以配置存在充数）

- 🟢 **TDD 先 Red 后 Green 有执行证据**：`docs/record/会话记录-backend-20261014.md` 16:31 小节「Red：测试先行 + H2 等价 DDL」、17:43「TDD 全链：Red 编译失败 → Green → 门禁 PASSED」；backend-20261015.md 18:58 小节记录本轮小改动「先只改测试 → Tests run: 2, Failures: 2 → 加实现 → 全绿」。
- 🟢 **约束被违反时有真实代价记录**：`docs/record/会话记录-lead-20261014.md` 11:43「模块一开工派发与**全量回滚**」、13:54「违规根因自查（为何未执行 rules.md 约束）」、13:56「TDD 铁律未落实归因」——证明约束体系不是摆设，违规会被人工发现、回滚并复盘。
- 🟡 **Hook 自动触发**：`.qoder/settings.json` 配置实存；各留痕中的「门禁 PASSED」字样证明门禁脚本被运行过，但**留痕无法区分是 PostToolUse Hook 自动触发还是子代理主动实跑**。且 `.git/hooks/` 为空（无 pre-commit），说明门禁只在 Qoder 工具链路径生效——**人工在 IDE 直接手改代码不经过该 Hook**。
- ⚪ **`.github/workflows/qoder-assistant.yml` / `qoder-review.yml`**：CI 配置实存（QoderAI/qoder-action），但本地材料中未找到这两条 workflow 在 10-14/10-15 的实际运行记录（需查 GitHub Actions 页，超出本地取证范围）。

---

## 二、AI 生成（AI Generation）

### 2.1 Git 提交与 AI 执行记录的对应（`git log` 实测）

| 提交 | 时间 | 内容（提交信息） | 增量 | 对应留痕证据链 | 证据状态 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `62b45b3` | 10-14 15:15 | 规则 | 13 files, +12/−963 | lead-20261014 14:06/14:34 重读 rules.md 清单 | 🟢 |
| `b70cbe8` | 10-14 15:32 | 契约修订 | 3 files | lead-20261014 14:57 查证小节 | 🟢 |
| `7b273e9` | 10-14 19:42 | 模块一·供应商管理-前后端实现 | **62 files, +8386/−129** | backend-20261014 16:31/17:01/17:43/18:08/19:06 六小节逐接口 TDD 记录；frontend-20261014 18:36「对接真实后端接口 §1.1–§1.5」 | 🟢 |
| `7065a16` | 10-15 07:56 | 模块一 bug 修复（上传后前端转圈） | 9 files, +803 | lead-20261015 凌晨报障→「报障≠实施授权」→用户批复方案 A→派发的完整决策链留痕 | 🟢 |
| `7b50425` | 10-15 13:58 | 模块二·状态机管理 | **55 files, +8646/−120** | backend-20261015 11:37「§2.1–§2.6 六接口全量落地」；13:00「§2.4 收紧仅建档人可提（用户裁决）」 | 🟢 |
| `678f677`+`a2b1213` | 10-15 16:12 | 模块二补充 | 14 files, +1561 | backend/frontend-20261015 14:23/15:58「§2.7 跨供应商终审待办聚合」 | 🟢 |
| `6865e60` | 10-15 20:24 | 模块三·绩效考核 | **40 files, +7129/−32** | backend-20261015 18:26（后端 15 主源+5 测试类）与 18:58（裁决③清空复核痕迹）；frontend-20261015 19:34（F1+F2+F3-rev 六新件四改件）；lead 18:50 派发留痕 | 🟢 |
| **未提交** | 22:36 落地 | 审核页每行「详情/日志」 | `srm-frontend/src/views/audit/ReviewListView.vue` +26/−2（工作区实测） | frontend-20261015 22:36 小节；lead-20261015 22:51 小节 | 🟢（但仅工作区，未入库） |

### 2.2 主要生成物清单（模块三为例，磁盘实存核验）

- 后端（包 `com.srm.core`）：`entity/PerformanceEvaluation.java`、`mapper/PerformanceEvaluationMapper.java`、`calculator/`（`PerformanceScoreCalculator`、`PerformanceMetricProvider`、`ManualFactInputAdapter`、`MockDataMetricAdapter`）、`dto/performance/` 7 类、`service/PerformanceEvaluationService(+Impl)`、`controller/PerformanceEvaluationController.java`。
- 前端：`src/types/performance.ts`、`src/api/performance.ts`、`src/constants/performanceValidation.ts`、`src/utils/supplierNameCache.ts`、`src/components/business/PerformanceEntryPanel.vue`（748 行）、`src/components/audit/PerformanceReviewPanel.vue`（572 行）。
- 🟡 提交执行方说明：`6865e60` 等提交的操作者在会话留痕中无记录；按项目纪律（AI 未经指示不得 commit）推断为人工操作，但**提交触发方式无法从现有记录直接确认**。

---

## 三、人工业务复核（Human Review）

### 3.1 有文字证据的人工决策（非虚构，均出自 `docs/record/会话记录-lead-20261015.md` 各小节「用户决策」行）

| 决策 | 时刻 | 内容 | 落地证据 |
| :--- | :--- | :--- | :--- |
| 模块三 B1-B5 五项裁决 | 16:42 | 删前端实时算分 / 重提记 SUBMIT 审计 / 下拉全量后端兜底 / Mock 真实提交 / 绩效域 40401 文案 | 契约回写 3 处（`docs/api-spec.md` §PUT、`docs/接口契约文档-供应商与绩效-20261013.md` §0+§3.2）；前端实现无本地算分（`PerformanceEntryPanel.vue` grep 核验） |
| 冲突①裁决 | 18:45 | 同周期归档后再发起：维持 DDL/契约现状，回写验收文档 | `docs/acceptance-criteria.md` §2.5 校正（+3/−1）；测试 `archivedEvaluationReleasesGeneratedColumnPlaceholderPerDdlSemantics` 实存 |
| 裁决②③⑦⑧ | 18:50 | 样例分数回写对齐引擎 / 重提清空 reviewed_* / 前端派发 / 临时件清理 | 契约 §3.1 样例改为 92.50/87.50/91.60/90.00/100.00/91.45；后端 `@TableField(updateStrategy=IGNORED)`+置 null 实改；`.tmp` 五件删除记录 |
| 运行裁决①③④⑤ | 19:47 前后 | 重启后端 / 50001→404 不出卡 / stash 纪律 / mock 重提 UX 确认 | 后端 PID 4711→36734 替换，`.run-logs/backend.log` L57 Tomcat started 19:42:59 |
| §2.4 仅建档人可提 | 13:00 | 生命周期申请收紧 | backend-20261015 13:00 小节 + `SupplierListView.vue` L560-563 注释 |

### 3.2 必须如实说明的边界

- 🟡 **可证明的是「人工业务决策」，不能证明「人工逐行代码复核」**。现有材料（决策留痕、回写授权、10-14 全量回滚事件）证明人持续参与方向裁决与违规干预；但没有任何材料证明人对 AI 生成的 7129+8646+8386 行代码做过系统性人工阅读。评审时不应把「决策链完备」表述成「代码已被人工审读」。
- ⚪ **需求/验收对齐**：`docs/acceptance-criteria.md` §5.2 慢请求日志告警（需新增切面）在本次开发范围内未见对应实现与测试 → **待确认是否列入验收**。
- **缺失验收依据清单（待确认事项）**：①浏览器端 UI 人工验收（登录→录入→复核全流程体验）无留痕记录；②`supplier_name`/`reviewed_by_name` 契约增列建议用户未表态（挂账）；③失败操作（40901/40902 冲突）是否写 `result=REJECTED` 审计的全局口径，与 `docs/acceptance-criteria.md` §3.1 存在张力，两轮上报未裁。

---

## 四、自动化测试（Automated Testing）

### 4.1 测试执行证据

| 项 | 内容 | 证据状态 |
| :--- | :--- | :--- |
| 最新一轮全量测试工件 | `srm-backend/target/surefire-reports/` 44 份 XML，mtime **10-15 22:52**；解析合计 **tests=632, failures=0, errors=0** | 🟢（工件实存） |
| 覆盖率工件 | `srm-backend/target/site/jacoco/jacoco.csv` mtime 22:52:55；本审计用 python 独立重算：**行 99.65%、分支 99.34%**（阈值 85%） | 🟢 |
| 留痕记录的历史执行 | backend-20261015 18:26「632 全绿 99.65/99.34」、18:58「Red(2 failures)→Green→全量 632 全绿」；backend-20261014 各小节「252/294 用例全绿、门禁 PASSED」 | 🟢（文字记录）+ 🟡（当轮工件已被 22:52 重跑覆盖，无法独立复核当时数字） |
| ⚠️ 发起方不明 | 22:52 这轮重跑发生在「详情/日志」任务（22:36，纯前端）之后，且 lead 于 22:51-22:53 取证时未运行后端测试；**22:52 测试轮次的触发者（Hook 自动/IDE/其他会话）在会话记录中无法确认** | 🟡 |
| 前端测试 | 前端无单元测试/组件测试工程（`srm-frontend/package.json` scripts 仅 dev/build/preview）。质量验证仅 `vue-tsc -b && vite build` 类型+编译门禁；build 成功仅见于留痕转述（exit=0、`✓ built in 426ms`），无持久化构建日志；dist 工件 mtime 22:49:18 为间接证据 | 🟡 / ⚪（无自动化功能测试） |

### 4.2 测试用例 ↔ 业务规则关联（模块三，surefire XML testcase 名实测提取）

| 业务规则（契约出处：`docs/接口契约文档-供应商与绩效-20261013.md` §3.1-§3.5、`docs/api-spec.md` §4.1） | 钉住它的测试用例 |
| :--- | :--- |
| 防篡改：前端夹带分数字段被静默丢弃 | `smuggledScoreFieldsAreSilentlyDroppedAndDbKeepsBackendComputedValues` |
| mock 模式忽略入参事实 | `mockModeIgnoresInboundFactRecordAndPersistsSeededDemoFacts` |
| 免考规则（0 批次除零防护、单一 exemption 标识） | `bothZeroDenominatorsExemptEveryDimensionWithoutArithmeticFailure`、`deliveryExemptionReusesSameExemptionFlagWithoutDivisionByZero` |
| 公式与评级分档硬编码（80/70/90 边界） | `calculatesFiveDimensionsPerApiSpecFormula`、`gradeBBoundaryAtExactlyEighty`、`gradeCBoundaryAtExactlySeventy`、`clampsEveryDimensionAtZeroAndNeverGoesNegative` |
| C 维度自动读 APPROVED 过期资质 | `complianceQueryFiltersApprovedOnlyAndRequiresExpiryDate`、`expiredApprovedQualificationZeroesComplianceWhileRejectedOneIsIgnored` |
| 同周期在途唯一 40902 / 归档释放占位可新建（裁决①） | `secondInFlightEvaluationOfSamePeriodIsRejectedWith40902`、`archivedEvaluationReleasesGeneratedColumnPlaceholderPerDdlSemantics`、`databaseGeneratedColumnRejectsSecondInFlightRowInsertedDirectly`（直插 DB 验证生成列兜底） |
| 乐观锁 40901（重提/复核脏版本） | `resubmitWithStaleVersionIs40901AndFreezesFactsScoreAndAudit`、`staleOrMissingVersionOnReviewIs40901WhileZeroVersionIsInputLayer40001` |
| REJECT 必填意见 40001、REJECT 沿用同 id 重算 | `rejectWithoutCommentIs40001AndLeavesEvaluationPendingReview`、`rejectThenResubmitKeepsTheSameRowAndRecomputesScoreWithoutSecondRecord` |
| §0 行级隔离（STAFF 仅见本人，AUDITOR/ADMIN 可读） | `detailOfOthersEvaluationIs40302WhileAuditorAndAdminCanReadIt`、`listEnforcesRowIsolationConvergesPagingAndStaysReadOnly` |
| RBAC（AUDITOR 不可发起、STAFF 不可复核、ADMIN 零业务写） | `auditorCannotInitiateEvaluationAndNothingIsWritten`、`staffCannotReviewTheEvaluationItSubmitted`、`adminCannotWritePerformanceEndpointsAtAll` |
| D 级不自动停用仅打标（`docs/api-spec.md` L361） | `approveWithGradeDDoesNotAutoSuspendSupplierButMarksRiskWarning`、`approveArchivesEvaluationAndWritesBackSupplierGradeWithoutRiskFlagOrStatusChange` |
| 归档不可改（40302）/ 逻辑删除释放占位 | `reviewOnArchivedEvaluationIsRejectedWith40302`、`logicallyDeletedEvaluationIsInvisibleAndReleasesItsPlaceholder` |
| 审计全链路（SUBMIT/REVIEW_* 一码多域 entity_type=PERFORMANCE_EVALUATION） | `auditChainCoversSubmitRejectResubmitApproveWithPerformanceEntityType` |
| 重提清空复核痕迹（裁决③，18:58 增补） | `rejectThenResubmit…` 集成断言 DB 三列 NULL + 单测 `updateResubmitsSameRowWithRecomputedScoreAndSubmitAudit` |

### 4.3 诚实边界

- **覆盖率 99.65%/99.34% 只度量"代码被执行过"，不等于业务正确**。业务正确性的最强证据是上表「用例名与契约规则一一对应」+ 五份契约回写的用户裁决链；但断言内容是否与契约逐字段一致，本审计抽样核对了用例名与关键断言（清空 NULL、40902、隔离），未逐条通读 130+ 例断言体，该部分为 🔵。
- 测试由 AI 编写、由 AI 运行、结果由 AI 记录——**「留痕数字」与「工件数字」在 22:52 轮次后一致（632/0/0、99.65/99.34），构成交叉验证**；更早轮次（18:26、18:58）的当时数字只能以留痕文字为据（🟡）。

---

## 五、补充边界用例（Edge-case Validation）

### 5.1 本次开发期间已实际执行的边界/异常用例

见 §4.2 表（除零、负分钳制、脏版本、越权、非法枚举码、非法日期、同周期冲突、归档终态、逻辑删除、夹带字段、REJECT 空意见——均有实存用例与 22:52 全绿工件）。

### 5.2 E2E 运行时验证（整理期间补充验证 🔄，非原始开发过程产物）

19:47 lead 在**重启后的真实服务**（PID 36734，MySQL 开发库）上以 curl 实测完整状态机，结果记录于 `docs/record/会话记录-lead-20261015.md` 19:47 小节：

| 步骤 | 实测结果 | 对应静态验证 |
| :--- | :--- | :--- |
| staff01 提交契约 §3.1 样例事实 | 总分 **91.45 / A** | 与当日契约校正回写值一致（双向锁定：文档=引擎） |
| auditor01 REJECT | RETURNED，reviewed_by=3 | 审计字段回显 |
| staff 重提 | 回 PENDING_REVIEW，**reviewed_by/at/comment 均 null**，新总分 98.33 | 裁决③运行时生效 |
| auditor01 APPROVE | APPROVED 归档 | 状态机 |
| 同周期再 POST | code=0 允许新单 | 裁决① DDL 语义运行时生效 |

该验证**无自动化脚本沉淀**（临时脚本在 /tmp 用后即删），可复现性依赖留痕文字 → 证据状态 🔄（整理期间补充验证，且输出未持久化，仅留痕转述 🟡）。

### 5.3 待补充测试 / 建议验证（未实施，仅为建议，未改任何代码）

| 场景 | 输入条件 | 预期结果 | 涉及文件 |
| :--- | :--- | :--- | :--- |
| 浏览器 UI 端到端 | 三账号人工走查录入/重提/复核/每行详情日志 | 页面行为与响应一致 | `srm-frontend/src/components/business/PerformanceEntryPanel.vue` 等 |
| 并发双复核 | 两 AUDITOR 同时对同版本单签署 | 后者 40901，审计仅一条成功 | `PerformanceEvaluationServiceImpl.review()` |
| 分页极值/非法 page | page=0、page_size=10000 | 参数钳制或 40001 | `PerformanceEvaluationController` list |
| period 跨年/闰日 | 2026-02-29 起 2027-02-28 止 | 通过；非法 2026-02-30 → 40001（已有非法日期用例，闰日组合未钉） | calculator/service 校验 |
| Mock 重提覆盖语义 | mode=mock 单 PUT 携带新 fact_record | 服务端按落库 mode 覆盖（`PerformanceEvaluationServiceImpl.java` L185 注释明文「§3.2 重提取适配器以落库 mode 为准」，**契约未明文**，前端已按提示处置） | `docs/接口契约文档-供应商与绩效-20261013.md` §3.2 |
| 前端错误路径 | 后端不可达时页面表现 | 友好报错不白屏 | `srm-frontend/src/utils/request.ts`（无任何前端自动化测试） |

---

## 六、执行过程汇总

| 流程环节 | 实际执行内容 | 对应文件或日志 | 验证结果 | 证据状态 | 备注 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| 提示词约束 | rules.md 铁律 + 4 Agent 契约 + PostToolUse Hook + 门禁脚本 | `.qoder/rules.md`、`.qoder/settings.json`、`.qoder/skills/check-coverage.sh`、`.qoder/agents/*.md` | 违规-回滚事件证明约束被实际执行 | 🟢 | Hook 自动触发 vs 手跑不可区分；git 层无 pre-commit |
| 需求拆解与裁决 | tech-lead 六卡拆解 + B1-B5/①-⑧ 共 13 项人工拍板 | `docs/record/会话记录-lead-20261015.md` 16:32→22:51 共 8 小节 | 每项有「用户决策」逐条留痕 | 🟢 | 决策证据完备（文字） |
| AI 生成（模块一/二/三） | 后端 2 万+ 行、前端组件交付，TDD 红绿 | 8 个 git 提交（§2.1 表）+ backend/frontend 留痕逐小节 | 提交与留痕一一对应 | 🟢 | 22:36 增量仅工作区未提交 |
| 契约治理 | 5 处文档回写（§0 文案/§3.2 审计/样例校正/验收 §2.5/api-spec 重提留痕） | `docs/接口契约文档-供应商与绩效-20261013.md`、`docs/api-spec.md`、`docs/acceptance-criteria.md` | 回写均带用户裁决时戳 | 🟢 | — |
| 自动化测试 | 632 例全绿 + JaCoCo 行 99.65%/分支 99.34% | `srm-backend/target/surefire-reports/`（22:52）、`jacoco.csv` | 审计独立重算一致 | 🟢 | 22:52 轮发起方不明 🟡 |
| 前端门禁 | `vue-tsc -b && vite build` exit=0（留痕三次转述） | `srm-frontend/dist/`（mtime 22:49:18） | 编译+类型通过 | 🟡 | 无持久化构建日志、无前端自动化测试 |
| 运行时 E2E | 重启后 curl 全链路（91.45/A、清空生效、归档后可新建） | `docs/record/会话记录-lead-20261015.md` 19:47、`.run-logs/backend.log` L57 | 5 步全符合预期 | 🔄/🟡 | 输出未持久化，无脚本沉淀 |
| 人工代码审读 | — | 无任何记录 | 不可主张 | ⚪ | 只有业务决策证据，无逐行审读证据 |
| 浏览器 UI 验收 | 用户可自行体验（dev server 已运行） | frontend 留痕 19:50 重启小节 | 无验收留痕 | ⚪ | 建议评审前人工走查并记录 |

---

## 七、风险与改进项（均为建议，未完成）

1. **门禁覆盖盲区（风险）**：质量门禁挂在 Qoder PostToolUse Hook，`.git/hooks/` 为空——人工/IDE 直改代码可完全绕开 `check-coverage.sh`。**建议**：补 git pre-commit 或依赖 CI（`.github/workflows/` 已配置 qoder-action，但其对 PR 的实际拦截效果未在本地取证）。
2. **子代理自报与磁盘不一致（已实际发生两例，经二轮 mtime 审计后细分为两种不同现象）**：①frontend-dev 22:36 小节自报「`_tmp*` 全部删除」，本审计实测 `srm-frontend/` 现存 5 个临时件，但性质不同：**点前缀三件**（`.tmp_fe_rec.py`、`.tmp_fe_record_section.md`、`.tmp_fe_sec_patch.py`，内容含 19:32 旧稿）mtime **19:55:45**，属上轮删除后被**快照还原**（与 19:33「六件同秒还原」同源）；**下划线前缀两件**（`_tmp_fe_record.py`、`_tmp_fe_section.md`）mtime **22:50:09**，晚于其 22:36 自报时刻——属挂起会话在报告后的**延迟写入**，而非子代理虚报删除（该定性经二轮审计修正，首轮本档曾笼统写成「自报与磁盘矛盾」）。②backend-dev 留痕时间曾需磁盘仲裁（教训已固化）。**建议**：所有"已删除/已执行"类自报，验收方一律磁盘仲裁并区分 mtime 早晚判定成因（本次审计已如此执行）；评审窗口冻结并行会话后再清理。
3. **测试运行工件不可回溯（风险）**：`target/` 工件每轮覆盖，18:26/18:58 当轮数字无法独立复核。**建议**：门禁脚本输出归档到带时间戳的日志目录（如 `.run-logs/gate/`）。
4. **前端零自动化测试（缺口）**：验证面只有类型+构建。核心防篡改在前端表现为"不发送分数字段"，该行为目前只有代码审查级保证。**建议**：为 `api/performance.ts` 请求构造与 `PerformanceReviewPanel` REJECT 必填校验补最小单测（vitest）。
5. **三项验收/口径挂账（缺失依据）**：失败操作 REJECTED 审计全局口径（与 `docs/acceptance-criteria.md` §3.1 张力）、`supplier_name`/`reviewed_by_name` 契约增列、`docs/acceptance-criteria.md` §5.2 慢查询切面实现——均无实现或裁决记录，评审时须说明为**未闭环事项**而非已完成。
6. **22:52 测试轮次发起方不明 + 留痕 mtime 22:49 写入方未定位**：取证时后端工件与 frontend 留痕仍在变动，可能存在并行会话/挂起会话延迟写入。**建议**：评审窗口内冻结并行 AI 会话，或建立「会话活跃登记表」。

---

## 八、现场演示建议（6-8 分钟）

| 时长 | 环节 | 打开的真实材料 | 讲解要点 | 能证明 / 不能证明 |
| :--- | :--- | :--- | :--- | :--- |
| 60s | 约束体系 | `.qoder/settings.json` → `.qoder/skills/check-coverage.sh` → `docs/record/会话记录-lead-20261014.md` 11:43/13:54 | 「AI 每次写代码后自动跑 85% 覆盖率门禁；违规曾导致全量回滚」| 证明约束存在且被实际执行过；**不能**证明每次触发方式 |
| 60s | 决策留痕链 | `docs/record/会话记录-lead-20261015.md` 16:32→19:47 | 滚动展示 13 项人工拍板，强调「AI 不自裁，冲突必须人裁决」 | 证明人工控制力；**不能**证明人工审读代码行数 |
| 90s | 防篡改现场 | 终端：`PerformanceEvaluationIntegrationTest.java` 的 `smuggledScoreFields…` 用例 → `srm-backend/target/surefire-reports/TEST-…IntegrationTest.xml` | 「请求里塞 total_score 会被静默丢弃，库里仍是后端算的值」+ 632 全绿工件 | 直接证据最强环节 |
| 60s | 覆盖率 | `srm-backend/target/site/jacoco/index.html`（浏览器） | 行 99.65%/分支 99.34%，绩效五类零 miss | 证明代码被执行过；**明确声明不等于业务正确** |
| 120s | 活系统演示 | `http://localhost:5173`（dev server 已在跑）：staff01 登录→量化绩效评价录入→Mock 提交→展示总分/评级只能来自响应；auditor01 登录→绩效评价复核→每行「查看详情」→驳回必填意见 | 「前后端契约 snake_case、前端全程不出现算分公式」 | 证明真实可用；注意演示会写库，用演示数据 |
| 60s | 运行事实核验 | `.run-logs/backend.log` L57（19:42:59 Tomcat started）+ lead 留痕 19:47 小节 E2E 五步 | 「样例分数 91.45 同时是文档校正值与引擎实算值」 | 证明集成过；**不能**证明（未持久化原始输出） |
| 45s | 诚实收尾 | 本文档 §5.3 与 §7 | 主动讲未覆盖面：无前端自动化测试、UI 人工验收未留痕、三项挂账 | 建立可信度 |

---

## 附：证据完整性说明

**有直接记录（可当场复核的工件）**：
- 8 个 git 提交与增量规模（`git log`/`git show --stat`）
- 632 例测试全绿与 99.65%/99.34% 覆盖率（`target/surefire-reports/` + `jacoco.csv`，mtime 22:52，本审计独立解析重算一致）
- 约束配置（`.qoder/rules.md`、`settings.json`、agents、skills 脚本）
- 两日 12 份角色留痕文件（`docs/record/会话记录-*-20261014/15.md`），含逐条用户决策
- 后端重启与 Tomcat 就绪（`.run-logs/backend.log` L57）
- 契约/验收文档 5 处回写（磁盘实存，带裁决时戳）

**仅有间接/部分证据**：
- Hook「自动触发」本身（留痕只记录门禁 PASSED 结果，不记录触发方式）
- 18:26/18:58 当轮测试数字（工件已被 22:52 覆盖，以留痕文字为据）
- 前端 build 成功（留痕转述 + dist mtime，无构建日志文件）
- 19:47 E2E smoke（留痕文字记录，原始输出未持久化；属整理期间补充验证 🔄）
- 提交操作者为人工（按纪律推断，无直接记录）
- CI workflows 的实际执行（本地无运行记录，需 GitHub Actions 端取证）

**缺少证据 / 未做（评审时必须如实呈现）**：
- 人工逐行代码复核记录
- 浏览器 UI 验收留痕
  - 前端任何自动化功能测试`                q\\\\\\\\\\\\`11
- 失败操作审计口径、契约增列、慢查询切面三项挂账的闭环
- 50001→404 缺陷的修复与回归（用户裁决「不出卡」，维持现状）
- 22:52 测试轮次与 22:49 留痕写入的发起方归属

*本文档由只读取证生成；除本文件与 lead 会话留痕外，审计过程未对仓库做任何修改。*
