# SRM 后端数据库表设计

## 版本修订记录

> 铁律：本文档是表结构/枚举取值的权威源。任何字段或生成列变更须先在此登记，再改 `schema.sql`（DDL 变更本身归后端实现任务卡）。表头沿用项目统一标准（与 `docs/TDD.docx`、需求规格说明书 V1.5 的「版本修订记录」表一致）。

| 编号 | 日期 | 版本 | 建立/修订人 | 建立/修订内容 |
|---|---|---|---|---|
|  | 2026-09-30 | V1.0 | Qoder | 初始版本：登录安全、权限、审计、供应商、绩效等全量表结构（对应同名 DOCX，依据 TDD V1.3） |
|  | 2026-10-10 | V1.1 | Qoder | §5 新增“在途唯一性”虚拟生成列 + 唯一键；§7 `lifecycle_request` 同法封堵；§6 绩效草稿表废弃说明；§8 动作码与 `AuditAction` 对齐（用户裁决：G1 封 40902 并发破功 / 方案 A 无草稿） |
|  | 2026-10-10 | V1.2 | Qoder | §3 `supplier.contact_name` 由 VARCHAR(50) 扩为 VARCHAR(100)，支撑契约侧 2~64 字符名称上限（用户裁决：扩列宽）；`schema.sql` 落地归后端实现任务卡，未执行前本文档为目标态 |
|  | 2026-10-11 | V1.3 | Qoder | 资质审核闭环与审计行级隔离所需的 DDL 增量：① §4 `supplier_qualification` 新增 `status`/`reviewed_by`/`reviewed_at`/`review_comment`（无这些列就无法承载“NORMAL 下追加待审核资质”）；② §7 `audit_log` 新增冗余列 `supplier_id` + 索引（否则“名下资产留痕”无法用单一条件实现）；③ §8 `entity_type` 新增 `SUPPLIER_QUALIFICATION`（需后端 `EntityType` 枚举同步）。均待后端任务卡执行 `schema.sql` |
|  | 2026-10-11 | V1.4 | Qoder | §3 新增“必填分层”说明（用户裁决：应用层强校验 + 数据库兜底防呆）：明确本表「必填」列与 SRS 业务必填不同层，`type`/`contact_*`/`effective_date`/`expiry_date` 保持允许 `NULL`、不收紧，`name`/`tax_no` 的 `NOT NULL` 不动；本节无任何 DDL 变更 |

## 1. 数据库说明

- 数据库：MySQL 8.x
- 表名使用 `snake_case`
- 主键统一使用 `BIGINT`
- 时间字段统一使用 `DATETIME`
- 删除采用逻辑删除的表增加 `deleted`
- 所有表默认使用 `utf8mb4`
- 金额、评分等需要小数的字段使用 `DECIMAL`
- 状态字段使用 `VARCHAR`
- 不使用数据库 ENUM，状态由后端代码维护

---

# 2. 用户表 `sys_user`

用于保存系统登录用户。

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|
| id | BIGINT | 是 | - | 主键 |
| username | VARCHAR(50) | 是 | - | 登录账号，唯一 |
| password_hash | VARCHAR(255) | 是 | - | 加密后的密码 |
| real_name | VARCHAR(50) | 是 | - | 姓名 |
| role | VARCHAR(20) | 是 | STAFF | 角色 |
| enabled | TINYINT(1) | 是 | 1 | 是否启用 |
| created_at | DATETIME | 是 | CURRENT_TIMESTAMP | 创建时间 |
| updated_at | DATETIME | 是 | CURRENT_TIMESTAMP | 更新时间 |
| deleted | TINYINT(1) | 是 | 0 | 逻辑删除 |

### role

```text
ADMIN
STAFF
AUDITOR
```

### 索引

```text
UNIQUE(username)
INDEX(role)
INDEX(enabled)
```

---

# 3. 供应商表 `supplier`

保存供应商基础信息。

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|
| id | BIGINT | 是 | - | 主键 |
| name | VARCHAR(200) | 是 | - | 供应商名称 |
| tax_no | VARCHAR(50) | 是 | - | 统一社会信用代码 |
| type | VARCHAR(50) | 否 | NULL | 供应商类型 |
| contact_name | VARCHAR(100) | 否 | NULL | 联系人（2026-10-10 由 VARCHAR(50) 扩宽；业务校验上限 64 字符，见 api-spec 第 2 节） |
| contact_phone | VARCHAR(30) | 否 | NULL | 联系电话 |
| contact_email | VARCHAR(100) | 否 | NULL | 联系邮箱 |
| address | VARCHAR(500) | 否 | NULL | 地址 |
| remark | TEXT | 否 | NULL | 备注 |
| effective_date | DATE | 否 | NULL | 合作开始日期 |
| expiry_date | DATE | 否 | NULL | 合作结束日期 |
| status | VARCHAR(30) | 是 | DRAFT | 供应商状态 |
| created_by | BIGINT | 是 | - | 创建人 |
| latest_performance_grade | VARCHAR(10) | 否 | NULL | 最新绩效等级 |
| risk_warning | TINYINT(1) | 是 | 0 | 是否有风险 |
| version | INT | 是 | 1 | 乐观锁版本 |
| created_at | DATETIME | 是 | CURRENT_TIMESTAMP | 创建时间 |
| updated_at | DATETIME | 是 | CURRENT_TIMESTAMP | 更新时间 |
| deleted | TINYINT(1) | 是 | 0 | 逻辑删除 |
| tax_no_active | VARCHAR(50) | 否 | 生成列 | `IF(deleted = 0, tax_no, NULL)`，仅用于承载下方唯一索引，不对业务代码暴露 |

> **必填分层（裁决 20261011，与 api-spec V1.7 同步）**：本表「必填」列只描述**数据库层**约束，不等于 SRS 「核心字段规范」的业务必填。两者取不同层：SRS 列为业务必填而本表为 `NULL` 的 `type`/`contact_name`/`contact_phone`/`effective_date`/`expiry_date`，由接口层 `@NotBlank`/`@NotNull` 在 Controller 拦 `40001`，**不把列收紧为 NOT NULL**（保留存量数据兼容与灰度加字段能力）；已为 `NOT NULL` 的 `name`/`tax_no` 保持不动当兜底防呆。**本裁决不产生任何 DDL 变更**。

### status

```text
DRAFT
PENDING_REVIEW
NORMAL
RETURNED
SUSPENDED
ELIMINATED
```

### latest_performance_grade

```text
A
B
C
D
```

> 取值与 `performance_evaluation.grade` 一致，见第 5 节。

### 索引

```text
UNIQUE(tax_no_active)
INDEX(name)
INDEX(status)
INDEX(created_by)
INDEX(status, deleted)
```

### 关联

```text
supplier.created_by → sys_user.id
```

### 业务规则

- `tax_no` 本身不建唯一索引，唯一性通过生成列 `tax_no_active`（`IF(deleted = 0, tax_no, NULL)`）+ `UNIQUE(tax_no_active)` 实现：MySQL 的 UNIQUE 索引允许多个 NULL 并存，逻辑删除（`deleted = 1`）的历史供应商不再占用这个税号，允许重新建档使用同一税号；未删除的供应商之间仍强制税号唯一。
- 这是为了兼容 `DELETE /suppliers/{id}`（仅本人 `DRAFT` 逻辑删除）之后重新用同一税号建档的场景——如果直接对 `tax_no` 建普通 `UNIQUE` 索引，逻辑删除的旧记录会一直占用该税号，导致重新建档失败。

---

# 4. 供应商资质表 `supplier_qualification`

保存供应商营业执照等资质文件。

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|
| id | BIGINT | 是 | - | 主键 |
| supplier_id | BIGINT | 是 | - | 供应商 ID |
| doc_type | VARCHAR(30) | 是 | - | 文件类型 |
| file_url | VARCHAR(500) | 是 | - | 文件地址 |
| effective_date | DATE | 否 | NULL | 生效日期 |
| expiry_date | DATE | 否 | NULL | 到期日期（单项证照有效期，与 §3 `supplier.expiry_date`“企业合作契约周期”同名不同义） |
| status | VARCHAR(20) | 是 | PENDING_REVIEW | 资质版本状态：`PENDING_REVIEW` / `APPROVED` / `REJECTED`（V1.3 新增） |
| reviewed_by | BIGINT | 否 | NULL | 审核人（AUDITOR）用户 ID（V1.3 新增） |
| reviewed_at | DATETIME | 否 | NULL | 审核时间（V1.3 新增） |
| review_comment | VARCHAR(500) | 否 | NULL | 审核意见，驳回时必填（V1.3 新增） |
| created_at | DATETIME | 是 | CURRENT_TIMESTAMP | 创建时间 |
| updated_at | DATETIME | 是 | CURRENT_TIMESTAMP | 更新时间 |
| deleted | TINYINT(1) | 是 | 0 | 逻辑删除 |

### doc_type

```text
BUSINESS_LICENSE
OTHER
```

### 索引

```text
INDEX(supplier_id)
INDEX(supplier_id, doc_type)
INDEX(expiry_date)
```

### 关联

```text
supplier_qualification.supplier_id → supplier.id
```

### 业务规则

是否过期由：

```text
status = 'APPROVED' AND expiry_date < 当前日期
```

实时计算。

- **仅 `APPROVED` 参与业务判定**：合规得分 `C`、`risk_warning`、准入提交（“至少一份未过期营业执照”）均只看现行有效版本；`PENDING_REVIEW`/`REJECTED` 不参与（V1.3，裁决 20261011）。
- **一个 `supplier_id` + `doc_type` 最多一条 `APPROVED`**：审核通过时同事务将旧版置 `REJECTED`（历史保留，不物理删），避免“同一类型两份有效资质”把合规算分变成不确定。应用层必须强约束；**MVP 不在 DB 建部分唯一索引**（与 `tax_no_active`/`active_period_key` 不同：那里需要 DB 兜并发，而此处审核动作本身由 AUDITOR 单人触发、并发改审核已经 `reviewed_at` 与乐观锁兼顾，加生成列收益低）。
- **上传入口状态已放宽**：`DRAFT`/`RETURNED`/**`NORMAL`** 均允许 STAFF 追加资质（解除“准入后资质无法维护”死锁），新记录一律 `PENDING_REVIEW`；旧版在审核完成前保持 `APPROVED`，不出现“无有效资质”真空。
- 逻辑删除位 `deleted` 与审核状态 `status` 是两个维度：`REJECTED` 靠 `status` 区分，不得用 `deleted = 1` 表达审核驳回（否则丢历史）。

---

# 5. 绩效评价表 `performance_evaluation`

保存供应商绩效评价及计算结果。

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|
| id | BIGINT | 是 | - | 主键 |
| supplier_id | BIGINT | 是 | - | 供应商 ID |
| period_start | DATE | 是 | - | 评价开始日期 |
| period_end | DATE | 是 | - | 评价结束日期 |
| mode | VARCHAR(20) | 是 | manual | 计算模式 |
| fact_record | JSON | 是 | - | 原始事实数据 |
| score_quality | DECIMAL(5,2) | 否 | NULL | 质量得分 |
| score_delivery | DECIMAL(5,2) | 否 | NULL | 交付得分 |
| score_price | DECIMAL(5,2) | 否 | NULL | 价格得分 |
| score_service | DECIMAL(5,2) | 否 | NULL | 服务得分 |
| score_compliance | DECIMAL(5,2) | 否 | NULL | 合规得分 |
| total_score | DECIMAL(5,2) | 否 | NULL | 总分 |
| grade | VARCHAR(10) | 否 | NULL | 评级 |
| quality_exempt | TINYINT(1) | 是 | 0 | 是否质量豁免 |
| risk_warning | TINYINT(1) | 是 | 0 | 是否风险预警 |
| status | VARCHAR(30) | 是 | PENDING_REVIEW | 状态 |
| created_by | BIGINT | 是 | - | 创建人 |
| reviewed_by | BIGINT | 否 | NULL | 审核人 |
| reviewed_at | DATETIME | 否 | NULL | 审核时间 |
| review_comment | VARCHAR(1000) | 否 | NULL | 审核意见 |
| version | INT | 是 | 1 | 乐观锁版本 |
| created_at | DATETIME | 是 | CURRENT_TIMESTAMP | 创建时间 |
| updated_at | DATETIME | 是 | CURRENT_TIMESTAMP | 更新时间 |
| deleted | TINYINT(1) | 是 | 0 | 逻辑删除 |
| active_period_key | VARCHAR(128) | 虚拟生成列 | NULL | 在途周期唯一键，DDL 见下文「在途唯一性」 |

### mode

```text
manual
mock
```

### status

```text
PENDING_REVIEW
APPROVED
RETURNED
```

### grade

```text
A
B
C
D
```

### 索引

```text
INDEX(supplier_id)
INDEX(status)
INDEX(created_by)
INDEX(supplier_id, period_start, period_end)
INDEX(supplier_id, status)
UNIQUE KEY uk_supplier_active_period (active_period_key)   -- 虚拟生成列，见下文「在途唯一性」
```

### 关联

```text
performance_evaluation.supplier_id → supplier.id
performance_evaluation.created_by → sys_user.id
performance_evaluation.reviewed_by → sys_user.id
```

### 在途唯一性（裁决 20261010：DB 层封堵 40902 并发破功）

「同 `supplier_id` + 同周期仅一张在途（`PENDING_REVIEW`/`RETURNED`）」原为应用层校验（api-spec 40902）。并发 create 双检查同时通过会插出两张在途单，故增加虚拟生成列 + 唯一索引兜底：

```sql
-- 仅当未删除且状态为在途（PENDING_REVIEW, RETURNED）时有值，其余状态为 NULL（NULL 不参与唯一约束）
`active_period_key` VARCHAR(128) GENERATED ALWAYS AS (
    CASE
        WHEN deleted = 0 AND status IN ('PENDING_REVIEW', 'RETURNED')
        THEN CONCAT(supplier_id, '_', period_start, '_', period_end)
        ELSE NULL
    END
) VIRTUAL COMMENT '在途周期唯一键防重保护',

UNIQUE KEY `uk_supplier_active_period` (`active_period_key`)
```

> 说明：
> - 表中无 `assessment_period` 列，周期由 `period_start` + `period_end` 两列表达，故键取 `supplier_id_period_start_period_end`（DATE 参与 CONCAT 自动转 `YYYY-MM-DD`，无二义性）。
> - 应用层 40902 预检**保留**（给出友好报错）；DB 唯一索引是并发兜底，命中 `DuplicateKey` 时 Service 层捕获并转译 `40902 DUPLICATE_IN_PROGRESS`。
> - `deleted = 0` 已折进生成列条件：逻辑删除的记录键位自动 NULL、不再阻塞同周期新建，无需额外处理状态。
> - **落地时机**：`schema.sql` 建表语句的本列为准 DDL 变更归后端实现任务卡（本轮仅文档）。

---

# 6. （已废弃删除）绩效评价草稿表

> **裁决（20261010，方案 A）**：绩效评价改为**一步式提交** `POST /performance/evaluations`，由算分引擎直接算分并落主表 `performance_evaluation`，**不设草稿、不再存在 `performance_evaluation_draft` 表及其 `DRAFT/SUBMITTED` 状态机**。原 TDD D4_1 `PerformanceEvaluationDraft` 实体作废。
>
> **精准区分**：`supplier.status` 的 `DRAFT`（供应商建档草稿）仍然合法保留，不受本次删除影响；被删除的仅是绩效域的 `PERFORMANCE_EVALUATION_DRAFT` 概念。审计 `EntityType` 亦不再包含 `PERFORMANCE_EVALUATION_DRAFT`。
>
> **落地时机**：`schema.sql` 的 `CREATE TABLE performance_evaluation_draft` 及后端/前端相关映射归入实现任务卡一并移除（本轮仅文档）。

---

# 7. 生命周期申请表 `lifecycle_request`

保存供应商暂停、恢复等申请。

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|
| id | BIGINT | 是 | - | 主键 |
| supplier_id | BIGINT | 是 | - | 供应商 ID |
| type | VARCHAR(20) | 是 | - | 申请类型 |
| reason | VARCHAR(1000) | 是 | - | 申请原因 |
| status | VARCHAR(20) | 是 | PENDING | 申请状态 |
| applied_by | BIGINT | 是 | - | 申请人 |
| applied_at | DATETIME | 是 | CURRENT_TIMESTAMP | 申请时间 |
| decided_by | BIGINT | 否 | NULL | 审核人 |
| decided_at | DATETIME | 否 | NULL | 审核时间 |
| decision_comment | VARCHAR(1000) | 否 | NULL | 审核意见 |
| created_at | DATETIME | 是 | CURRENT_TIMESTAMP | 创建时间 |
| updated_at | DATETIME | 是 | CURRENT_TIMESTAMP | 更新时间 |
| deleted | TINYINT(1) | 是 | 0 | 逻辑删除 |

### type

```text
SUSPEND
RESUME
ELIMINATE
```

### status

```text
PENDING
APPROVED
REJECTED
```

### 索引

```text
INDEX(supplier_id)
INDEX(status)
INDEX(supplier_id, type, status)
UNIQUE KEY uk_supplier_active_lifecycle (active_key)   -- 虚拟生成列，见下文
```

### 关联

```text
lifecycle_request.supplier_id → supplier.id
lifecycle_request.applied_by → sys_user.id
lifecycle_request.decided_by → sys_user.id
```

### 在途唯一性（裁决 20261010 连带）

backend §4.5「同 `supplier_id`+`type` 已有 `PENDING` → 40902」与绩效在途属**同类并发破功点**，同法封堵：

```sql
`active_key` VARCHAR(64) GENERATED ALWAYS AS (
    CASE WHEN deleted = 0 AND status = 'PENDING' THEN CONCAT(supplier_id, '_', type) ELSE NULL END
) VIRTUAL COMMENT '同供应商同类型仅一张进行中申请',

UNIQUE KEY `uk_supplier_active_lifecycle` (`active_key`)
```

> 应用层预检保留；命中 `DuplicateKey` 转译 40902。`schema.sql` DDL 变更同归后端实现任务卡。

---

# 8. 审计日志表 `audit_log`

记录关键业务操作。

> **与 TDD 的对齐说明**：`docs/TDD.docx` 第 6 节 DFD 中把 `D2 StatusHistory`（状态历史表）与 `D5 AuditLog`（审计日志表）画成两个独立数据存储。本设计将两者合并为这一张 `audit_log` 表：`old_status`/`new_status` 字段承担 StatusHistory 的职责，不再单独建表。这是有意的架构简化（同一次状态变更既是"状态历史"也是"审计事件"，字段完全重合，拆两张表只会增加一次多余的写入和一致性维护成本），并非遗漏；后续再对照 TDD 原文时，不需要因为找不到 `status_history` 表而当成新问题。

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|
| id | BIGINT | 是 | - | 主键 |
| entity_type | VARCHAR(50) | 是 | - | 业务对象类型 |
| entity_id | BIGINT | 是 | - | 业务对象 ID |
| supplier_id | BIGINT | 否 | NULL | 冗余归属供应商 ID（V1.3 新增）：任何与某供应商相关的动作（包括 `PERFORMANCE_EVALUATION`/`LIFECYCLE_REQUEST`/`SUPPLIER_QUALIFICATION`）均写入该供应商主键；`SUPPLIER` 类型下与 `entity_id` 同值；`USER` 类型置 NULL。服务于审计的行级隔离，避免按 `entity_type` 分别联表 |
| operator_id | BIGINT | 是 | - | 操作人 |
| operator_role | VARCHAR(20) | 是 | - | 操作人角色 |
| action | VARCHAR(50) | 是 | - | 操作类型 |
| old_status | VARCHAR(30) | 否 | NULL | 操作前状态 |
| new_status | VARCHAR(30) | 否 | NULL | 操作后状态 |
| result | VARCHAR(20) | 是 | SUCCESS | 操作结果 |
| comment | VARCHAR(1000) | 否 | NULL | 操作说明 |
| created_at | DATETIME | 是 | CURRENT_TIMESTAMP | 创建时间 |

### entity_type

```text
SUPPLIER
PERFORMANCE_EVALUATION
LIFECYCLE_REQUEST
USER
SUPPLIER_QUALIFICATION
```

> `SUPPLIER_QUALIFICATION` 为 V1.3 新增（裁决 20261011 资质审核闭环），需同步后端 `audit/EntityType.java`（现仅 4 值）与 api-spec 第 5 节；对应审核动作复用现有 `AUDIT_APPROVE` / `AUDIT_REJECT` 码，不另立新码（保持 MVP 极简）。

### action

```text
SUBMIT
AUDIT_APPROVE
AUDIT_REJECT
REVIEW_APPROVE
REVIEW_REJECT
DECISION_APPROVE
DECISION_REJECT
CREATE_USER
UPDATE_USER
ENABLE_USER
DISABLE_USER
```

> `entity_type = LIFECYCLE_REQUEST` 时，`DECISION_APPROVE`/`DECISION_REJECT` 统一表示停用/恢复/淘汰三种申请类型的决策，具体申请类型看关联的 `lifecycle_request.type`，不在 `action` 里再拆分成 `SUSPEND_APPROVE`/`RESUME_APPROVE`/`ELIMINATE_APPROVE` 等。
>
> `entity_type = USER` 时，`CREATE_USER`/`UPDATE_USER`/`ENABLE_USER`/`DISABLE_USER` 表示管理员对用户账号的创建/资料角色修改/启用/停用，`entity_id` 为目标用户 ID，`old_status`/`new_status` 记录 `sys_user.enabled` 的变化（新增时 `old_status` 为空）。
>
> `entity_type = PERFORMANCE_EVALUATION` 且一步式发起评价时，`action` **复用 `SUBMIT`**（提交送审语义），靠 `entity_type` 与供应商建档的 `SUBMIT` 区分；评价审核用 `REVIEW_APPROVE`/`REVIEW_REJECT`。

### result

```text
SUCCESS
REJECTED
```

### 索引

```text
INDEX(entity_type, entity_id)
INDEX(operator_id)
INDEX(supplier_id)
INDEX(created_at)
```

---

# 9. 表关系

```text
sys_user
   │
   ├────────────── supplier.created_by
   │
   ├────────────── performance_evaluation.created_by
   │
   ├────────────── performance_evaluation.reviewed_by
   │
   ├────────────── lifecycle_request.applied_by
   │
   ├────────────── lifecycle_request.decided_by
   │
   └────────────── audit_log.operator_id


supplier
   │
   ├── supplier_qualification
   │
   ├── performance_evaluation
   │
   └── lifecycle_request
```

---

# 10. 核心业务关系

### 供应商

一个供应商可以有：

- 多个资质
- 多个绩效评价
- 多个绩效评价草稿
- 多个生命周期申请
- 多条审计日志

### 用户

一个用户可以：

- 创建多个供应商
- 创建多个绩效评价草稿
- 创建多个绩效评价
- 创建多个生命周期申请
- 审核多个供应商
- 审核多个绩效评价
- 审核多个生命周期申请

---

# 11. 建表实现规则

Qoder 建表时遵循以下规则：

1. 所有主键使用 `BIGINT`
2. 所有表增加 `created_at`、`updated_at`；`audit_log` 是只插入不更新的表，例外只保留 `created_at`，不需要 `updated_at`。`updated_at` 的自动刷新通过 MyBatis-Plus 的 `@TableField(fill = FieldFill.INSERT_UPDATE)` + 全局 `MetaObjectHandler` 在应用层实现，不依赖数据库层 `ON UPDATE CURRENT_TIMESTAMP`（避免未来迁移 KingbaseES 时行为不一致）。
3. 需要逻辑删除的表增加 `deleted`
4. 状态字段使用 `VARCHAR`，不要使用 MySQL ENUM
5. 外键关系按本文档处理，但**不建物理 `FOREIGN KEY` 约束**——所有关联仅为逻辑关系，引用完整性由应用层（Service/Mapper）维护，不在 DDL 里写 `FOREIGN KEY`（物理外键会和逻辑删除语义打架，也不利于迁移 KingbaseES）。
6. `supplier.tax_no` 的唯一性通过生成列 `tax_no_active`（`IF(deleted = 0, tax_no, NULL)`）+ `UNIQUE(tax_no_active)` 实现，逻辑删除的记录不占用税号；不要直接对 `tax_no` 建普通 `UNIQUE` 索引，见第 3 节业务规则。
7. `sys_user.username` 唯一（当前无删除接口，暂用普通 `UNIQUE` 索引即可；若后续给用户增加删除功能，需按第 6 条同样的生成列思路改造）
8. `performance_evaluation.fact_record` 使用 JSON
9. 绩效分数字段使用 `DECIMAL(5,2)`
10. `version` 用于乐观锁
11. 查询频繁的状态、供应商 ID、创建人字段建立索引
12. 不要额外创建本文档没有定义的业务表
13. 不要把 `score`、`grade` 等计算逻辑拆成额外数据表
14. 供应商状态、绩效状态、生命周期状态由后端业务代码控制
15. 表字段必须与 API 文档中的 Request / Response 保持一致