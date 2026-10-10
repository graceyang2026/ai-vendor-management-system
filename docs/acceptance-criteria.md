# 验收标准（核心铁律）

> 对应 `.qoder/rules.md` 第 4.2 / 4.3 / 4.5 节的三条"核心铁律"。这三条风险最高，最容易在"开发自己写测试、自己判定通过"的模式下被绕过，因此单独补充可测试的验收标准，供 Qoder 写单元/集成测试时对照，也供人工验收时使用。

> 本文档只覆盖铁律场景，不覆盖全部接口；普通 CRUD 接口的验收依据是 `docs/api-spec.md` 本身的字段规则与错误码表。

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
