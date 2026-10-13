# Week 4 · AI 编码上下文快照与业务约束基线文档（v1.0）

> 用途：**多轮对话上下文丢失的对冲工具**。任何新会话/新子代理开工前，先读本文件，再动手。
> 编制：SRM 架构组 leadA｜快照时刻：2026-10-13 10:43（本机实测）｜代码基线：`MVP-W4` @ `5864702`
> 维护规则：契约或裁决发生变化时**必须同步本文件**，并在文末「快照修订记录」追加一行。

---

## 0. 会话恢复协议（开工三步，顺序不可换）

1. 读 `.qoder/rules.md`（约束）→ 读 `AGENTS.md`（门禁铁律）→ 读 `.qoder/agents/<角色>.md`（目录边界）；
2. 读 `docs/api-spec.md` + `srm-backend/src/main/resources/schema.sql`（**唯一事实源**，2026-10-13 10:00 起逻辑冻结）；
3. 跑一次基线实测确认环境未漂移：`./mvnw -o test`（应 139 全绿）、`git status --short`（应仅见已知未提交项）。

> 教训固化：**严禁凭记忆报数字**。本项目已发生"214 用例"基线因卡 1 撤销而作废的事件；任何引用覆盖率/用例数的场合，必须当场重跑。

## 1. 事实源清单与版本指纹

| 事实源 | 路径 | 当前指纹（实测） | 权限 |
|---|---|---|---|
| API 契约 | `docs/api-spec.md` | 534 行，8 节；§0 含规则 A/B/C + 目标态 DDL 增量表① ② ③ | 变更需用户拍板 |
| 数据库现状 | `srm-backend/src/main/resources/schema.sql` | 6 表；**有未提交改动**（DDL④ `supplier_qualification.version` 一行） | 同上（唯一例外：用户确认后可由 tech-lead 直改） |
| 后端设计 | `docs/backend-interface-design.md` | 604 行；L203 submit 校验、L207 reviewQualification、L235 绩效 create 内部流程 | 只作核对 |
| 验收标准 | `docs/acceptance-criteria.md` | §1 状态机与并发 / §2 防篡改 / §3 审计不可变 / §4 权限与数据范围 | 只作核对 |
| 需求原件 | `docs/供应商管理系统需求规格说明书-…V1.6_20261011待评审.docx` | 封面版本号仍标 V1.5（已列修订提案 M1） | **绝对只读** |
| 设计原件 | `docs/TDD.docx` | 实测版本 **V1.4**；§1 仍引用"PRD V1.5（…V1.4_20260920）"已过时（提案 M2/M3）；免考仅 Q 分支（提案 M4） | **绝对只读** |

## 2. schema.sql 事实源快照（表 × 列数 × 关键机制）

| 表 | 列数 | 必须记住的关键点 |
|---|---|---|
| `sys_user` | 9 | `UNIQUE KEY uk_username`；`deleted` 逻辑删除位（用户名占用含已删用户 → `40903`） |
| `supplier` | 20 | 含 `version`、`risk_warning`、`latest_performance_grade`、`created_by`；**生成列 `tax_no_active = IF(deleted=0, tax_no, NULL)` + `uk_tax_no_active`**（逻辑删除即释放税号） |
| `supplier_qualification` | 15 | `status` 三态 `PENDING/APPROVED/REJECTED`（默认 PENDING）；`reviewed_by/reviewed_at/review_comment`；**生成列 `approved_key` + `uk_supplier_approved_doc`**（同供应商同证照仅一条现行版）；`version`（DDL④，**未提交**） |
| `performance_evaluation` | 24 | `mode VARCHAR(20) DEFAULT 'manual'`；五维分与总分为 `DECIMAL(5,2)`；`quality_exempt`、`risk_warning`；`status` 三态；**生成列 `active_period_key` + `uk_supplier_active_period`**（一周期一在途单） |
| `lifecycle_request` | 14 | `type` = `SUSPEND/RESUME/ELIMINATE`；`status` = `PENDING/APPROVED/REJECTED`；`version`；**生成列 `active_key` + `uk_supplier_active_lifecycle`** |
| `audit_log` | 12 | 字段名是 **`action`**（不是 operation）；**冗余列 `supplier_id BIGINT NULL` + `idx_supplier_id`（DDL② 已落）**；无外键、无物理删除路径 |

**已固化范式（照抄即可，勿另造）**：
> "逻辑删除友好的虚拟生成列 + 部分唯一索引" —— 已在 `performance_evaluation.active_period_key`、`lifecycle_request.active_key`、`supplier.tax_no_active`、`supplier_qualification.approved_key` 四处验证。**禁止使用数据库触发器。**

## 3. 乐观锁（@Version）约定与真实状态 ⚠️ 高频踩坑

| 层面 | 状态 |
|---|---|
| DDL | ✅ `supplier` / `performance_evaluation` / `lifecycle_request` / `supplier_qualification` 均有 `version` 列 |
| 拦截器 | ✅ `MybatisPlusConfig` 已注册分页 + 乐观锁拦截器 |
| 实体注解 | ⛔ **全库无任何实体标注 `@Version`**（实测 `grep -rln "@Version" src/main` 仅命中 `MybatisPlusConfig`；现存唯一实体 `User` 无 version 字段） |
| 契约要求 | 所有并发审核资源的 Response 带 `version`；写操作必须回传 `version`；不匹配 → `40901` |

**开工铁律**：卡 A / 卡 D 创建 `Supplier`、`PerformanceEvaluation`、`LifecycleRequest`、`SupplierQualification` 实体时，**必须**给 `version` 字段加 `@Version`，并在决策类接口（`audit` / `review` / `decision`）入参强制要求 `version`。否则 `40901` 分支永不触发，等于交付一条死码路径，且 40901 的边界用例也无法编写。

## 4. 防篡改算分规则（不可协商的架构内核）

```
固定权重（后端硬编码，不可配置、不受前端影响）：
  Q 质量 30%  |  D 交付 25%  |  P 价格 20%  |  S 服务 15%  |  C 合规 10%

公式：
  Q = MAX(0, 100 - qc_failed_batches/qc_total_batches*100*1.5 - major_accidents*50)
  D = MAX(0, 100 - delayed_batches/total_batches*100       - avg_delay_days*2)
  综合得分 = Q*0.30 + D*0.25 + P*0.20 + S*0.15 + C*0.10

零业务免考（双维度对称，复用单一标识）：
  qc_total_batches = 0 → Q = 100 且 quality_exempt = true
  total_batches    = 0 → D = 100 且复用同一个 quality_exempt（MVP 不拆 delivery_exempt、不加列）
  免考短路顺序：先判总批次为 0，命中则该维度直接 100.00，该维度内其它扣分项不再参与

评级边界（三处必须被测试钉住）：A ≥ 90 ｜ 80 ≤ B < 90 ｜ 70 ≤ C < 80 ｜ D < 70

合规维度 C：唯一入参是布尔 hasExpiredApprovedQualification（是否存在"已审核通过但 expiry_date 已过期"的资质）
  存在 → C = 0 ；不存在 → C = 100
  （查库职责在 Service，计算器保持纯函数、零 DB 依赖 —— 这是分支覆盖 100% 的前提）

risk_warning = 资质过期 || grade ∈ {C, D}     ← 仅打预警位，【绝不】自动变更供应商状态
```

**入参边界（`ManualFactInputAdapter` 校验域）**：非负；`qc_failed_batches ≤ qc_total_batches`；`delayed_batches ≤ total_batches`；上限域（批次类 100000、`major_accidents` 100、`complaint_overtime_count` 1000、`avg_delay_days` 365、`price_deviation_rate` 1000）。失败一律 `40001` + 固定中文文案。

**模式选择铁律**：Controller 按 `mode` 从 `Map<String, PerformanceMetricProvider>` 取 bean（bean name = 契约 mode 字面量 `manual`/`mock`），**禁止 if/else 判模式**；`mode=mock` 时前端传入的 `fact_record` **被忽略而非报错**；二期接外部系统只增实现类，不改算分与调用链。

## 5. 状态机与流程约束（六态 + 两步申请）

```
DRAFT ─submit(需未过期营业执照)→ PENDING_REVIEW ─APPROVE→ NORMAL ─┐
  ↑                      └─REJECT(comment必填)→ RETURNED ─┘        │ 停用申请→裁决
  └──────── 仅 DRAFT/RETURNED 可编辑/删除（本人）                    ▼
外部生态（企查查/天眼查）──── 资质过期扫描 ──── 触发资质审核 / risk_warning ──→ SUSPENDED ⇄ NORMAL
                                                                  ↓ 淘汰申请→裁决
                                                              ELIMINATED（终态，不可逆，禁再建申请）
```

- 不提供任何直接 PATCH `supplier.status` 的接口；`NORMAL ⇄ SUSPENDED`、`→ ELIMINATED` 必经 `LifecycleRequest` 两步。
- AUDITOR **只做裁决**，不得绕过申请流程直接改状态（SRS V1.6 已按此口径重写，与 TDD 同轨）。
- 前置态约束：`SUSPEND` 仅 `NORMAL`；`RESUME` 仅 `SUSPENDED`；`ELIMINATE` 仅 `NORMAL`/`SUSPENDED`；同类型已有 `PENDING` → `40902`。
- 资质维护状态：`POST /suppliers/{id}/qualifications` 允许 `DRAFT`/`RETURNED`/`NORMAL`（裁决 20261011 解除"准入后无法维护"死锁）；新记录一律 `PENDING` 不直接生效。
- 准入 `audit` **同事务连带**批量批复 `PENDING` 资质；存续期补传件走 `PUT /qualifications/{id}/review`（仅此场景）。

## 6. 权限与数据可见性（规则 A/B/C + 双层校验）

| 规则 | 内容 | 实现要点 |
|---|---|---|
| A 私有在途区 | `DRAFT`/`RETURNED` 强制 `created_by = 当前用户`；他人查不到、改不了；详情越权 `40302` | 查询条件拼 `created_by`，不引入部门树 |
| B 公有资产区 | `NORMAL`/`SUSPENDED`/`ELIMINATED` **不限 `created_by`**，全体登录角色只读 | 准入即公司级公共资产 |
| C 审计范围 | `STAFF` 只见 `operator_id = 当前用户 OR supplier_id IN (本人创建供应商)`；AUDITOR/ADMIN 全量 | 依赖 `audit_log.supplier_id`（DDL 已落，**代码未写未过滤**） |
| ADMIN 例外 | 不受规则 A 本人限制（系统管理需要），但**只读**，写一律 `40301` | — |

**双层校验不可省**：Controller `@PreAuthorize("hasRole(...)")`（粗粒度）+ Service `RoleGuard.requireRole/requireStatusAllowed/requireSelf`（细粒度，→ `40301`/`40302`）；当前用户 ID 取自 `CurrentUserProvider.require().getId()`。**前端隐藏按钮永远不算安全边界**（已作为真实用例 `serviceLayerRoleGuardBlocksAdminEvenIfPreAuthorizeMissing` 钉住）。

## 7. 审计留痕约束

- 字段名是 **`action`**，取值必须用后端枚举码（不是前端自造中文或动作名）。
- 写入必须 `try-catch` 全包裹、失败只 `log.error`，**绝不向上传播**（防主事务被留痕拖崩）。
- `AuditAction` 共 **11 值**：`SUBMIT`、`AUDIT_APPROVE`、`AUDIT_REJECT`、`REVIEW_APPROVE`、`REVIEW_REJECT`、`DECISION_APPROVE`、`DECISION_REJECT`、`CREATE_USER`、`UPDATE_USER`、`ENABLE_USER`、`DISABLE_USER`。
- `EntityType` 共 **5 值**：`SUPPLIER`、`PERFORMANCE_EVALUATION`、`LIFECYCLE_REQUEST`、`USER`、`SUPPLIER_QUALIFICATION`。
- **一码多域（方案甲）**：`AUDIT_*`=准入审核；`REVIEW_*`=绩效复核 **与** 存续期资质审批；`DECISION_*`=生命周期终审。前端文案必须按 `entity_type` 分流。
- 审核员日志页视角 = 业务域四实体（`SUPPLIER,PERFORMANCE_EVALUATION,LIFECYCLE_REQUEST,SUPPLIER_QUALIFICATION`）；`USER` 域日志不得出现在审核员页（防用户 ID 与供应商 ID **撞号串入**）。缺省不传 = 全量（供管理员页）。
- **已声明边界**：资质日志 `entity_id` 是资质 ID 而非供应商 ID，按供应商 ID 聚合查时该类留痕暂不命中；需新增 `supplier_id` 查询参数才能覆盖，属后续批次能力。

## 8. 错误码与响应约定

| code | 枚举名 | 含义 |
|---|---|---|
| 0 | `SUCCESS` | 成功 |
| 40001 | `VALIDATION_FAILED` | 参数校验失败（必填缺失、格式、事实数据非法） |
| 40101 | `UNAUTHORIZED` | 未登录 / token 失效 |
| 40102 | `LOGIN_FAILED` | 用户名或密码错误（**不区分具体原因，防用户枚举**） |
| 40301 | `FORBIDDEN` | 无权限（角色不匹配） |
| 40302 | `STATUS_NOT_ALLOWED` | 当前状态不允许该操作（非草稿编辑、非本人操作） |
| 40401 | `NOT_FOUND` | 资源不存在 |
| 40901 | `OPTIMISTIC_LOCK_CONFLICT` | 并发冲突（乐观锁版本不匹配） |
| 40902 | `DUPLICATE_IN_PROGRESS` | 业务冲突（在途重复申请/评价单、税号唯一） |
| 40903 | `USERNAME_DUPLICATE` | 用户名已存在（含逻辑删除占用） |
| 50001 | `INTERNAL_ERROR` | 服务器内部错误 |

- **业务错随 HTTP 200 返回**，错信息在统一响应体 `code`/`message`；本契约不以 HTTP 状态码传递业务错。
- 全站 `snake_case`：DTO 字段**显式标注 `@JsonProperty`**，前端 TS 类型字段名与 JSON 一致，不做大小写转换。
- 分页：`page`（从 1 起，默认 1）、`page_size`（默认 20，最大 100）；响应 `PageResult{list,total,page,page_size}`。

## 9. 建档字段校验契约（逐字口径，前后端同源）

| 字段 | 规则 | 正则 |
|---|---|---|
| `name` | 必填，trim 非空且非纯空格，**2~64** | `^\S.{0,62}\S$` |
| `tax_no` | 必填，GB 32100 十八位，不含 I/O/Z/S/V | `^[0-9A-HJ-NPQRTUWXY]{18}$` |
| `type` | 必填，三码之一（中文文案不接受） | `MANUFACTURING` / `SERVICE_OUTSOURCING` / `DISTRIBUTOR` |
| `contact_name` | 必填，同 `name` 口径 **2~64** | 同 `name` |
| `contact_phone` | 必填，大陆 11 位手机 | `^1[3-9]\d{9}$` |
| `contact_email` | 选填，填则须合法 | 常规邮箱 |
| `effective_date`/`expiry_date` | 必填 `yyyy-MM-dd`，`expiry > effective` | — |
| `address`/`remark` | 选填，无格式校验 | — |

必填靠**应用层强校验**（DTO `@NotBlank/@NotNull/@Pattern`，Controller 层一次完成，Service 不重复校验）；数据库侧允许 NULL 的列**不收紧**（保留历史兼容）。

## 10. 目录结构与所有权边界（不可越界）

```
srm-backend/src/main/java/com/srm/core/
  audit/      审计枚举与实体（EntityType/AuditAction/AuditLog/AuditResult）
  calculator/ 算分引擎与适配器            ← 卡 D（当前为空/已撤销）
  common/     ApiResponse/ErrorCode/BusinessException/PageResult/GlobalExceptionHandler
  config/     SecurityConfig/MybatisPlusConfig 等
  controller/ 当前仅 AuditLog/Auth/Test/User   ← 卡 A/D 增 Supplier/Qualification/Performance/Lifecycle
  dto/        auditlog auth common lifecycle performance qualification supplier user
              ← lifecycle / qualification / supplier / performance 目前均为空目录
  entity/ mapper/ security/（9 类，含 RoleGuard/Jwt*/CurrentUserProvider） service/
```

| 角色 | 可写范围 | 唯一例外 |
|---|---|---|
| backend-dev | `srm-backend/` | 可追加 `docs/record/会话记录-backend-*.md` |
| frontend-dev | `srm-frontend/` | 可追加 `docs/record/会话记录-frontend-*.md` |
| coverage-fixer | 测试目录 | 可追加 `docs/record/会话记录-coverage-*.md` |
| tech-lead / lead / leadA | **只读代码**；契约文档经拍板后可改 | `docs/record/`；`schema.sql` 仅在用户确认后由 tech-lead 直改 |

## 11. 已裁决且不可回退的清单

1. 取消纯手工打分，改系统算分（SRS V1.5）；
2. 双维度对称免考 + 复用单一 `quality_exempt`，不拆列；
3. 资质审核留痕**方案甲**：零新增动作码，靠 `entity_type` 区分域；
4. 审核员日志页**四实体**视角（旧三实体作废）；
5. 供应商类型**三码 + 传码显中文**分层，后端枚举为权威源；
6. 全量**逻辑删除**，正式档案禁物理删除；
7. 数据权限只做"本人 + 状态"两维，**不引入部门树/权限组**；
8. 业务错走 HTTP 200 + `body.code`；
9. 需求书/TDD docx **原件只读**；文档台账机制（版本修订表）已于 2026-10-11 废止，变更历史由 git 承载；
10. 绩效 Response **含 `mode`**（16 字段终态，方案 a）。

## 12. 协作陷阱清单（血泪教训，务必读）

| 陷阱 | 表现 | 规避动作 |
|---|---|---|
| IDE 附件 diff 失真 | 反复出现"小节被删除"的 diff，磁盘实读仍在 | **用 `cat`/`wc`/`grep` 直读磁盘仲裁**，不凭 diff 判断文件状态 |
| Read 缓存过期 | 读到的内容比盘上旧 | 关键核验走命令行 |
| 子代理虚报"已删临时脚本" | 留痕称已删，盘上复现 | **`git status` + `ls` 双命令实测复核**；本包生成脚本一律放 `/tmp` 不进仓库 |
| 留痕整文件重写覆盖 | Read→Write 整写导致历史内容被截断覆盖 | 留痕**只追加**（`cat >>` 或定点插入），禁止整写 |
| 时间戳拍脑袋 | 会话时间标到比真实时刻还晚 | 一律 `date "+%Y-%m-%d %H:%M"` 实测；跨天另起文件 |
| 记忆引用旧覆盖率 | "214 用例"类已作废数字被再次引用 | 引用前必重跑 `./mvnw -o test` |
| LambdaQueryWrapper 单测两坑 | TableInfo 未预热；参数惰性填充未物化导致断言为空 | 测试内先 `TableInfoHelper` 预热、先物化参数再断言 |
| 契约先行≠库已就绪 | 因"库里还没这列"而改接口定义 | 契约按目标态描述接口，DDL 缺失走"待落地增量"登记 |

## 13. 快照修订记录

| 版本 | 时刻 | 变更 |
|---|---|---|
| v1.0 | 2026-10-13 10:43 | 首版固化：SSOT 指纹、6 表列数与生成列范式、@Version 真空洞告警、防篡改算分全套、状态机与规则 A/B/C、错误码 11 值、校验正则、10 条不可回退裁决、8 类协作陷阱 |

**待回填项**：①外部评审意见（H12+）；②卡 A/D 落地后的实体 `@Version` 状态；③DDL④ 提交后的 schema 指纹；④`docs/review/` 其余三份文档的版本联动。
