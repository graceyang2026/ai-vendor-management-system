# SRM 后端数据库表设计

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
| contact_name | VARCHAR(50) | 否 | NULL | 联系人 |
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
| expiry_date | DATE | 否 | NULL | 到期日期 |
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
expiry_date < 当前日期
```

实时计算。

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
```

### 关联

```text
performance_evaluation.supplier_id → supplier.id
performance_evaluation.created_by → sys_user.id
performance_evaluation.reviewed_by → sys_user.id
```

---

# 6. 绩效评价草稿表 `performance_evaluation_draft`

保存绩效评价提交前的客观事实明细草稿，对应 TDD 6.2 节 D4_1 `PerformanceEvaluationDraft`。STAFF 发起评价、录入事实数据阶段落此表；提交后由算分引擎读取草稿计算得分，写入 `performance_evaluation`，草稿随即逻辑删除。

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|
| id | BIGINT | 是 | - | 主键 |
| supplier_id | BIGINT | 是 | - | 供应商 ID |
| period_start | DATE | 是 | - | 评价开始日期 |
| period_end | DATE | 是 | - | 评价结束日期 |
| mode | VARCHAR(20) | 是 | manual | 计算模式 |
| fact_record | JSON | 否 | NULL | 原始事实数据 |
| status | VARCHAR(20) | 是 | DRAFT | 草稿状态 |
| created_by | BIGINT | 是 | - | 创建人 |
| created_at | DATETIME | 是 | CURRENT_TIMESTAMP | 创建时间 |
| updated_at | DATETIME | 是 | CURRENT_TIMESTAMP | 更新时间 |
| deleted | TINYINT(1) | 是 | 0 | 逻辑删除 |

### mode

```text
manual
mock
```

### status

```text
DRAFT
SUBMITTED
```

### 索引

```text
INDEX(supplier_id)
INDEX(created_by)
INDEX(status)
INDEX(supplier_id, period_start, period_end)
```

### 关联

```text
performance_evaluation_draft.supplier_id → supplier.id
performance_evaluation_draft.created_by → sys_user.id
```

### 业务规则

- 草稿不做同周期唯一性校验，唯一性校验在提交（生成 `performance_evaluation` 记录）时进行。
- `mode = mock` 时，`fact_record` 在创建草稿时由后端自动生成，不允许编辑。
- 提交成功后草稿逻辑删除，不可再编辑或再次提交。

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
```

### 关联

```text
lifecycle_request.supplier_id → supplier.id
lifecycle_request.applied_by → sys_user.id
lifecycle_request.decided_by → sys_user.id
```

---

# 8. 审计日志表 `audit_log`

记录关键业务操作。

> **与 TDD 的对齐说明**：`docs/TDD.docx` 第 6 节 DFD 中把 `D2 StatusHistory`（状态历史表）与 `D5 AuditLog`（审计日志表）画成两个独立数据存储。本设计将两者合并为这一张 `audit_log` 表：`old_status`/`new_status` 字段承担 StatusHistory 的职责，不再单独建表。这是有意的架构简化（同一次状态变更既是"状态历史"也是"审计事件"，字段完全重合，拆两张表只会增加一次多余的写入和一致性维护成本），并非遗漏；后续再对照 TDD 原文时，不需要因为找不到 `status_history` 表而当成新问题。

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|
| id | BIGINT | 是 | - | 主键 |
| entity_type | VARCHAR(50) | 是 | - | 业务对象类型 |
| entity_id | BIGINT | 是 | - | 业务对象 ID |
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
PERFORMANCE_EVALUATION_DRAFT
LIFECYCLE_REQUEST
USER
```

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

### result

```text
SUCCESS
REJECTED
```

### 索引

```text
INDEX(entity_type, entity_id)
INDEX(operator_id)
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
   ├────────────── performance_evaluation_draft.created_by
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
   ├── performance_evaluation_draft
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
8. `performance_evaluation.fact_record`、`performance_evaluation_draft.fact_record` 使用 JSON
9. 绩效分数字段使用 `DECIMAL(5,2)`
10. `version` 用于乐观锁
11. 查询频繁的状态、供应商 ID、创建人字段建立索引
12. 不要额外创建本文档没有定义的业务表
13. 不要把 `score`、`grade` 等计算逻辑拆成额外数据表
14. 供应商状态、绩效状态、生命周期状态由后端业务代码控制
15. 表字段必须与 API 文档中的 Request / Response 保持一致