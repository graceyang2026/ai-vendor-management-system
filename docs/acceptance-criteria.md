# 验收标准（核心铁律）

> 对应 `.qoder/rules.md` 第 4.2 / 4.3 / 4.5 节的三条"核心铁律"。这三条风险最高，最容易在"开发自己写测试、自己判定通过"的模式下被绕过，因此单独补充可测试的验收标准，供 Qoder 写单元/集成测试时对照，也供人工验收时使用。

> 本文档只覆盖铁律场景，不覆盖全部接口；普通 CRUD 接口的验收依据是 `docs/api-spec.md` 本身的字段规则与错误码表。

## 版本修订记录

> 铁律：每条铁律验收用例的新增/修订均须先在此登记，再写测试；契约（api-spec / 数据库设计）变动时，必须同步检查本文档是否需补用例。表头沿用项目统一标准（与 `docs/TDD.docx`、需求规格说明书 V1.5 的「版本修订记录」表一致）。

| 编号 | 日期 | 版本 | 建立/修订人 | 建立/修订内容 |
|---|---|---|---|---|
|  | 2026-10-09 | V1.0 | Qoder | 初始版本：三条核心铁律（rules.md 4.2/4.3/4.5）对应 §1~§3 验收用例 |
|  | 2026-10-10 | V1.1 | Qoder | 新增 §1.5 资质文件上传白名单与限额用例（用户裁决 K2：契约有接口、验收无用例） |
|  | 2026-10-10 | V1.2 | Qoder | 新增 §1.6 供应商 `tax_no` 全局唯一用例，含软删释放、并发兜底、幂等编辑（用户裁决 ⑤：必须补，TDD 门禁第一道防线） |
|  | 2026-10-10 | V1.3 | Qoder | 新增 §1.7 建档字段格式校验用例：税号/手机号/名称长度/校验顺序/前后端正则同源（用户给定直接可落地校验标准） |
|  | 2026-10-10 | V1.4 | Qoder | §1.7 的 `contact_name` 长度用例随契约改为 64 上限（列宽扩至 VARCHAR(100)）（用户裁决：扩列宽） |

---

## §1 供应商生命周期状态机与并发控制（对应 rules.md 4.2）

### 1.1 状态机只能合法迁移，禁止跳状态

- **Given** 供应商当前状态为 `DRAFT`
- **When** 调用 `POST /suppliers/{id}/audit`（该接口只允许在 `PENDING_REVIEW` 时调用）
- **Then** 返回 `40302`，供应商状态不变

- **Given** 供应商当前状态为 `NORMAL`
- **When** 尝试用任何接口直接把 `status` 改成 `SUSPENDED`（而不是走 `LifecycleRequest`）
- **Then** 不存在这样的接口；`PUT /suppliers/{id}` 的 `SupplierUpdateRequest` 里没有 `status` 字段，无法绕过

### 1.2 PENDING_REVIEW 期间乐观锁拒绝 STAFF 并发写

- **Given** 供应商状态为 `PENDING_REVIEW`，`version = 3`
- **When** STAFF 调用 `PUT /suppliers/{id}` 或 `DELETE /suppliers/{id}`
- **Then** 返回 `40302`（当前状态不允许该操作），不因为携带了旧 `version` 而放行

- **Given** AUDITOR 正在审核同一供应商（已读取 `version = 3`），另一个审核请求已先提交并把 `version` 改成了 `4`
- **When** AUDITOR 用 `version = 3` 调用 `POST /suppliers/{id}/audit`
- **Then** 返回 `40901`（并发冲突），供应商状态不发生变化，前端需重新拉取详情后重试

#### 生命周期裁决并发冲突（停用/恢复/淘汰 decision）

- **Given** 一条 `PENDING` 生命周期申请关联的供应商 `version = 5`，AUDITOR 已读取该 `version`；期间另一并发操作已将供应商 `version` 改为 `6`
- **When** AUDITOR 用 `version = 5` 调用 `POST /lifecycle-requests/{id}/decision`（`LifecycleDecisionRequest` 携带 `version`）
- **Then** 返回 `40901`（`OPTIMISTIC_LOCK_CONFLICT`，并发冲突），供应商状态与申请记录均不发生变化，生命周期状态不联动，前端需重新拉取详情后重试

### 1.3 停用/恢复/淘汰必须走两步 LifecycleRequest，不能一步到位

- **Given** 供应商状态为 `NORMAL`
- **When** STAFF 调用 `POST /suppliers/{id}/lifecycle-requests`，`type=SUSPEND`
- **Then** 创建一条 `status=PENDING` 的 `LifecycleRequest`，供应商状态仍为 `NORMAL`（不因为申请而立刻变化）

- **Given** 同一供应商已有一条 `type=SUSPEND` 且 `status=PENDING` 的申请
- **When** STAFF 再次提交 `type=SUSPEND` 申请
- **Then** 返回 `40902`（业务冲突）

- **Given** 一条 `type=SUSPEND` 的 `PENDING` 申请
- **When** AUDITOR 调用 `POST /lifecycle-requests/{id}/decision`，`decision=APPROVE`
- **Then** 申请状态变为 `APPROVED`，供应商状态变为 `SUSPENDED`，且写入一条 `AuditLog`

- **Given** 供应商已处于终态 `ELIMINATED`
- **When** STAFF 对该供应商发起任何新的 `lifecycle-requests`
- **Then** 请求被拒绝（终态不可逆，不允许再创建申请）

### 1.4 同一供应商同一考核周期只允许一张在途绩效评价单

- **Given** 供应商已存在一张 `status=PENDING_REVIEW` 的正式绩效评价（`supplier_id` + `period_start`/`period_end` 相同）
- **When** STAFF 对同一供应商、同一周期再次调用 `POST /performance/evaluations`
- **Then** 返回 `40902`，不会生成第二张评价单，也不会触发第二次算分

### 1.5 资质文件上传白名单与限额（支撑 `POST /files/upload`，api-spec 第 2 节）

- **Given** 已登录的 STAFF
- **When** 上传 `pdf`/`jpg`/`jpeg`/`png` 且≤10MB 的文件（multipart 字段名 `file`）
- **Then** 返回 `file_url`/`file_name`/`size`，文件以 UUID 重命名落盘；`file_url` 可经 `/uploads/**` 静态映射访问；不写任何业务表、不记审计
- **When** 扩展名/MIME 任一不在白名单（如 `.exe`、伪装扩展名）或超过 10MB 或空文件
- **Then** 返回 `40001`，磁盘无残留；含 `../` 的原始文件名不得参与落盘路径拼接（防路径穿越）
- **When** 未登录或 AUDITOR/ADMIN 调用
- **Then** 分别返回 `40101`/`40301`（契约限定 STAFF）

### 1.6 供应商 `tax_no` 全局唯一（对应 api-spec 第 2 节校验规则 + 数据库表设计第 3 节）

- **Given** 已存在未删除供应商 A，其 `tax_no = T`
- **When** STAFF 调用 `POST /suppliers` 对另一家供应商提交同一 `tax_no = T`
- **Then** 返回 `40902`（业务冲突·税号唯一性）；不产生新记录；失败不写成功审计
- **Given** 供应商 A 已被逻辑删除（`deleted = 1`，生成列 `tax_no_active` 置 NULL）
- **When** STAFF 用同一 `tax_no = T` 重新建档
- **Then** 创建成功，返回新供应商且 `status = DRAFT`（键位已释放，允许沿用）
- **Given** 两个并发 `POST /suppliers` 携带同一 `tax_no = T`，且都已通过应用层预检
- **When** 两条 INSERT 先后到达数据库
- **Then** 仅一条成功，另一条由 `UNIQUE(tax_no_active)` 拦截，Service 捕获 `DuplicateKeyException` 并转译 `40902`（不得原抛出为 `50001`，也不得露出 SQL 细节）
- **When** STAFF 通过 `PUT /suppliers/{id}` 把自己供应商的 `tax_no` 改成另一家在用供应商的税号
- **Then** 返回 `40902`；改成自己当前税号（幂等）仍返回成功

### 1.7 建档字段格式校验（对应 api-spec 第 2 节校验规则表）

失败均为 `code=40001`（随 HTTP 200 返回），且 `message` 必须逐字等于契约固定文案（不得把正则或字段名直输出给前端）。

- **When** `tax_no` 为 17 位、19 位、含小写字母，或含 GB 32100 禁用字符 `I`/`O`/`Z`/`S`/`V`
- **Then** `40001`，message：`统一社会信用代码格式不合法（必须为18位有效字符）`；不落库、不记审计
- **When** `contact_phone` 不以 1 开头、第二位不是 3-9、或长度非 11 位（如 `01088886666` 座机号）
- **Then** `40001`，message：`联系电话格式不合法（必须为11位有效手机号）`
- **When** `name` 为空、纯空格、或长度 1 字符 / 65 字符
- **Then** `40001`，message：`供应商名称格式不合法（不得为空格且长度需为2~64字符）`
- **When** `contact_name` 长度 65 字符（超出契约上限 64）
- **Then** `40001`；而 64 字符必须能完整写入 `supplier.contact_name VARCHAR(100)`，**不得因列宽不足被截断或转成 `50001`**
- **Given** 一个格式非法且税号与已有供应商重复的请求
- **When** `POST /suppliers`
- **Then** 返回 `40001`（格式校验先行），而不是 `40902`；两者不得同时返回
- **Given** 同一非法输入
- **When** 分别经前端表单校验与后端 Bean Validation
- **Then** 两侧使用契约里的同一正则（`^[0-9A-HJ-NPQRTUWXY]{18}$` / `^1[3-9]\d{9}$` / `^\S.{0,62}\S$`），判定结果一致，不得出现“前端放行、后端报错”或反之

---

## §2 绩效评价防篡改（对应 rules.md 4.3）

### 2.1 前端物理上无法提交或覆盖分数/评级

- **Given** 任意 `PerformanceEvaluationCreateRequest` 或 `PUT /performance/evaluations/{id}` 的请求体
- **When** 请求体中包含 `total_score`、`grade`、`score_quality` 等结果字段
- **Then** 这些字段不属于 `PerformanceFactRecord` 的定义，后端 DTO 反序列化时应被忽略；即使客户端强行塞入，落库的评分结果也必须是由 `PerformanceScoreCalculator` 重新计算的值，不是请求体里的值（验收时对比请求体与落库结果不一致才算通过）

### 2.2 mock 模式下客户端传入的 fact_record 被忽略而非报错

- **Given** `POST /performance/evaluations`，`mode=mock`，请求体里仍然带了一份自定义 `fact_record`
- **When** 评价创建成功
- **Then** 返回的 `fact_record` 是 `MockDataMetricAdapter` 生成的数据，不是请求体里传入的值；接口不报错（不是 `40001`）

### 2.3 算分公式硬编码，不可被参数化影响

- **Given** 一组 `fact_record`：`qc_total_batches=0`
- **When** 调用 `POST /performance/evaluations` 一步提交
- **Then** `score_quality=100` 且 `quality_exempt=true`（不因为传入的其他字段变化而改变这条硬编码规则）

- **Given** 一份 `fact_record` 对应四个维度的边界值组合
- **When** 触发算分
- **Then** 综合得分严格等于 `Q*0.3 + D*0.25 + P*0.2 + S*0.15 + C*0.1`（浮点误差在允许范围内），且评级映射严格按 `A≥90 / 80≤B<90 / 70≤C<80 / D<70` 分档，不存在可配置的权重覆盖入口

### 2.4 D 级不自动变更供应商状态，必须走人工申请

- **Given** 一次评价复核通过（`review` → `APPROVE`）后计算出的 `grade=D`
- **When** 归档完成
- **Then** 供应商 `risk_warning=true`，但 `status` 保持不变（不会被评价接口自动置为 `SUSPENDED`）；只有后续人工通过 `POST /suppliers/{id}/lifecycle-requests`（`type=SUSPEND`）并经 AUDITOR 批准，供应商状态才会变化

### 2.5 提交后立即锁死，复核通过后不可覆盖旧记录

- **Given** `POST /performance/evaluations` 成功，生成 `status=PENDING_REVIEW` 的正式评价
- **When** 原 STAFF 尝试再次编辑该评价的 `fact_record`
- **Then** 返回 `40302`（仅 `RETURNED` 状态可编辑）

- **Given** 一条评价已被 AUDITOR `APPROVE` 归档
- **When** 任何角色尝试修改该记录的 `fact_record` 或结果字段
- **Then** 该记录不可再编辑，也不允许被新的评价覆盖（同周期已有归档记录时，`POST /performance/evaluations` 应通过 §1.4 的 `40902` 规则被拦截在更早的环节）

### 2.6 REJECT 沿用同一条记录，不新建

- **Given** 一条正式评价被 AUDITOR `REJECT`
- **When** 查询该评价记录
- **Then** `status=RETURNED`，`id` 不变；原 STAFF 通过 `PUT /performance/evaluations/{id}` 改事实重提后，仍是同一个 `id`，重新触发算分，不产生第二条记录

---

## §3 审计与不可变性（对应 rules.md 4.5）

### 3.1 关键操作必须全量写入 AuditLog

以下每一类操作发生后，必须能在 `GET /audit-logs?entity_type=&entity_id=` 查到一条对应记录，且 `operator_id`/`operator_role`/`action`/`old_status`/`new_status`/`result` 均不为空（`REJECT` 类操作 `comment` 不为空）：

- `POST /suppliers/{id}/audit`（`APPROVE`/`REJECT`）→ `entity_type=SUPPLIER`
- `POST /lifecycle-requests/{id}/decision`（`APPROVE`/`REJECT`）→ `entity_type=LIFECYCLE_REQUEST`
- `POST /performance/evaluations/{id}/review`（`APPROVE`/`REJECT`）→ `entity_type=PERFORMANCE_EVALUATION`
- 并发冲突（`40901`）或业务冲突（`40902`）导致操作失败时，也应写入一条 `result=REJECTED` 的记录，而不是静默失败不留痕

### 3.2 正式记录禁止物理删除

- **Given** 一条已提交审核（`PENDING_REVIEW` 及以后）的供应商记录，或已归档的绩效评价
- **When** 尝试删除
- **Then** 不存在物理删除该记录的接口/路径；供应商草稿删除仅允许 `DRAFT` 状态且逻辑删除（`deleted` 字段），数据库中记录仍存在

- **Given** 一条已生成的正式绩效评价（`PENDING_REVIEW`/`APPROVED`/`RETURNED`）
- **When** 任何角色尝试删除
- **Then** 不存在删除正式评价的接口/路径（绩效无对外草稿态）；正式评价一旦生成即不可物理删除，`RETURNED` 只能通过 `PUT /performance/evaluations/{id}` 改事实重提（沿用同 id）
