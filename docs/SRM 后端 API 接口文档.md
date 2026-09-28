# SRM 后端 API 接口文档

> Base URL：`/api/v1`
>
> 除登录接口外，均需携带 `Authorization: Bearer <token>`。
>
> 请求和响应 JSON 使用 `snake_case`。
>
> 详细业务规则以 `.qoder/rules.md` 为准。

---

## 1. 统一响应

### 成功

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

### 失败

```json
{
  "code": 40001,
  "message": "错误信息",
  "data": null
}
```

### 分页

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "list": [],
    "total": 0,
    "page": 1,
    "page_size": 20
  }
}
```

---

# 2. 登录

## POST `/auth/login`

**权限：无需登录**

### Request

```json
{
  "username": "staff01",
  "password": "123456"
}
```

### Response

```json
{
  "token": "jwt-token",
  "user": {
    "id": 1,
    "username": "staff01",
    "real_name": "张三",
    "role": "STAFF",
    "enabled": true
  }
}
```

> 不返回 `password` 或 `password_hash`。

---

# 3. 供应商

## POST `/suppliers`

**权限：STAFF**

创建供应商。

### Request

```json
{
  "name": "供应商名称",
  "tax_no": "91110000XXXXXXXXXX",
  "type": "制造商",
  "contact_name": "张三",
  "contact_phone": "13800000000",
  "contact_email": "test@example.com",
  "address": "北京市",
  "remark": "备注",
  "effective_date": "2026-01-01",
  "expiry_date": "2027-01-01"
}
```

### Response

返回 `SupplierResponse`。

### 规则

- 新建状态固定为 `DRAFT`
- `created_by` 使用当前登录用户
- 前端不能指定供应商状态

---

## PUT `/suppliers/{id}`

**权限：STAFF**

修改供应商。

### Request

字段与创建供应商基本一致。

### 规则

仅允许：

- 状态为 `DRAFT` 或 `RETURNED`
- 当前用户是创建人

否则返回 `40302`。

### Response

返回 `SupplierResponse`。

---

## GET `/suppliers/{id}`

**权限：登录用户**

获取供应商详情。

### Response

```json
{
  "id": 1,
  "name": "供应商名称",
  "tax_no": "91110000XXXXXXXXXX",
  "type": "制造商",
  "contact_name": "张三",
  "contact_phone": "13800000000",
  "contact_email": "test@example.com",
  "address": "北京市",
  "remark": "备注",
  "effective_date": "2026-01-01",
  "expiry_date": "2027-01-01",
  "status": "NORMAL",
  "created_by": 1,
  "created_by_name": "张三",
  "business_license_url": "https://example.com/license.pdf",
  "latest_performance_grade": "A",
  "risk_warning": false,
  "version": 1,
  "created_at": "2026-01-01T10:00:00",
  "updated_at": "2026-01-01T10:00:00"
}
```

---

## GET `/suppliers`

**权限：登录用户**

查询供应商列表。

### Query

| 参数 | 类型 | 必填 | 默认值 |
|---|---|---|---|
| status | string | 否 | - |
| keyword | string | 否 | - |
| page | int | 否 | 1 |
| page_size | int | 否 | 20 |

`keyword` 同时匹配：

- `name`
- `tax_no`

### Response

分页 `SupplierResponse`。

---

## DELETE `/suppliers/{id}`

**权限：STAFF**

删除供应商。

### 规则

仅允许：

- 当前用户是创建人
- 当前状态为 `DRAFT`

使用逻辑删除。

---

## POST `/suppliers/{id}/submit`

**权限：STAFF**

提交供应商审核。

### 规则

必须存在至少一条**未过期的营业执照**。

成功后：

`DRAFT → PENDING_REVIEW`

同时记录审计日志。

### Response

返回 `SupplierResponse`。

---

## POST `/suppliers/{id}/audit`

**权限：AUDITOR**

审核供应商。

### Request

```json
{
  "decision": "APPROVE",
  "comment": "审核通过",
  "version": 1
}
```

### 参数

| 参数 | 类型 | 必填 |
|---|---|---|
| decision | string | 是 |
| comment | string | REJECT 时必填 |
| version | int | 是 |

### decision

- `APPROVE`
- `REJECT`

### 状态变化

| decision | 状态 |
|---|---|
| APPROVE | `PENDING_REVIEW → NORMAL` |
| REJECT | `PENDING_REVIEW → RETURNED` |

`version` 不匹配返回 `40901`。

---

# 4. 供应商资质

## POST `/suppliers/{supplier_id}/qualifications`

**权限：STAFF**

添加供应商资质。

### Request

```json
{
  "doc_type": "BUSINESS_LICENSE",
  "file_url": "https://example.com/license.pdf",
  "effective_date": "2026-01-01",
  "expiry_date": "2027-01-01"
}
```

### doc_type

- `BUSINESS_LICENSE`
- `OTHER`

### Response

```json
{
  "id": 1,
  "supplier_id": 1,
  "doc_type": "BUSINESS_LICENSE",
  "file_url": "https://example.com/license.pdf",
  "effective_date": "2026-01-01",
  "expiry_date": "2027-01-01",
  "expired": false,
  "created_at": "2026-01-01T10:00:00"
}
```

---

## GET `/suppliers/{supplier_id}/qualifications`

**权限：登录用户**

获取供应商资质列表。

### Response

返回 `QualificationResponse[]`。

`expired` 根据当前日期实时计算，不需要前端传递。

---

# 5. 绩效评价

## POST `/performance/evaluations`

**权限：STAFF**

创建绩效评价。

### Request

```json
{
  "supplier_id": 1,
  "period_start": "2026-01-01",
  "period_end": "2026-03-31",
  "mode": "manual",
  "fact_record": {
    "total_batches": 100,
    "delayed_batches": 5,
    "avg_delay_days": 1.5,
    "qc_total_batches": 100,
    "qc_failed_batches": 2,
    "major_accidents": 0,
    "price_deviation_rate": 1.2,
    "complaint_overtime_count": 1
  }
}
```

### mode

- `manual`
- `mock`

### manual

必须提供 `fact_record`。

### mock

忽略 `fact_record`，由后端生成事实数据。

### 禁止字段

Request 中不得出现：

- `score_quality`
- `score_delivery`
- `score_price`
- `score_service`
- `score_compliance`
- `total_score`
- `grade`
- `risk_warning`

这些字段全部由后端计算。

### 规则

同一供应商 + 同一评价周期，如果已有：

- `PENDING_REVIEW`
- `RETURNED`

则返回：

`40902 DUPLICATE_IN_PROGRESS`

创建成功后：

`status = PENDING_REVIEW`

---

## PUT `/performance/evaluations/{id}`

**权限：STAFF**

修改退回的绩效评价。

### 规则

仅允许：

- 当前状态为 `RETURNED`
- 当前用户是创建人

修改后：

`RETURNED → PENDING_REVIEW`

使用原评价 ID，不创建新记录。

重新计算全部评分。

### Response

返回 `PerformanceEvaluationResponse`。

---

## GET `/performance/evaluations/{id}`

**权限：登录用户**

获取绩效评价详情。

### Response

```json
{
  "id": 1,
  "supplier_id": 1,
  "period_start": "2026-01-01",
  "period_end": "2026-03-31",
  "mode": "manual",
  "fact_record": {
    "total_batches": 100,
    "delayed_batches": 5,
    "avg_delay_days": 1.5,
    "qc_total_batches": 100,
    "qc_failed_batches": 2,
    "major_accidents": 0,
    "price_deviation_rate": 1.2,
    "complaint_overtime_count": 1
  },
  "score_quality": 95,
  "score_delivery": 90,
  "score_price": 92,
  "score_service": 95,
  "score_compliance": 100,
  "total_score": 93.8,
  "grade": "A",
  "quality_exempt": false,
  "risk_warning": false,
  "status": "PENDING_REVIEW",
  "created_by": 1,
  "created_at": "2026-04-01T10:00:00",
  "reviewed_by": null,
  "reviewed_at": null,
  "review_comment": null,
  "version": 1
}
```

---

## GET `/performance/evaluations`

**权限：登录用户**

查询绩效评价。

### Query

| 参数 | 类型 | 必填 | 默认值 |
|---|---|---|---|
| supplier_id | long | 否 | - |
| status | string | 否 | - |
| page | int | 否 | 1 |
| page_size | int | 否 | 20 |

### Response

分页 `PerformanceEvaluationResponse`。

---

## POST `/performance/evaluations/{id}/review`

**权限：AUDITOR**

审核绩效评价。

### Request

```json
{
  "decision": "APPROVE",
  "comment": "审核通过",
  "version": 1
}
```

### decision

- `APPROVE`
- `REJECT`

### 规则

`APPROVE`：

`PENDING_REVIEW → APPROVED`

同时更新供应商：

- `latest_performance_grade`
- `risk_warning`

`REJECT`：

`PENDING_REVIEW → RETURNED`

`REJECT` 时 `comment` 必填。

`version` 不匹配返回 `40901`。

---

# 6. 生命周期申请

## POST `/suppliers/{supplier_id}/lifecycle-requests`

**权限：STAFF**

创建生命周期申请。

### Request

```json
{
  "type": "SUSPEND",
  "reason": "业务调整"
}
```

### type

- `SUSPEND`
- `RESUME`

### 规则

`SUSPEND`：

供应商必须为 `NORMAL`。

`RESUME`：

供应商必须为 `SUSPENDED`。

同一供应商存在相同类型的 `PENDING` 申请时，返回：

`40902`

---

## GET `/suppliers/{supplier_id}/lifecycle-requests`

**权限：登录用户**

查询供应商生命周期申请。

### Response

```json
[
  {
    "id": 1,
    "supplier_id": 1,
    "type": "SUSPEND",
    "reason": "业务调整",
    "status": "PENDING",
    "applied_by": 2,
    "applied_by_name": "张三",
    "applied_at": "2026-04-01T10:00:00",
    "decided_by": null,
    "decided_at": null,
    "decision_comment": null
  }
]
```

---

## POST `/lifecycle-requests/{id}/decision`

**权限：AUDITOR**

审批生命周期申请。

### Request

```json
{
  "decision": "APPROVE",
  "comment": "同意"
}
```

### 状态变化

`SUSPEND + APPROVE`：

供应商 → `SUSPENDED`

`RESUME + APPROVE`：

供应商 → `NORMAL`

`REJECT`：

只修改申请状态，供应商状态不变。

---

# 7. 审计日志

## GET `/audit-logs`

**权限：登录用户**

查询审计日志。

### Query

| 参数 | 类型 | 必填 | 默认值 |
|---|---|---|---|
| entity_type | string | 否 | - |
| entity_id | long | 否 | - |
| page | int | 否 | 1 |
| page_size | int | 否 | 20 |

### entity_type

- `SUPPLIER`
- `PERFORMANCE_EVALUATION`
- `LIFECYCLE_REQUEST`

### Response

分页 `AuditLogResponse`。

```json
{
  "id": 1,
  "entity_type": "SUPPLIER",
  "entity_id": 10,
  "operator_id": 2,
  "operator_role": "AUDITOR",
  "action": "AUDIT_APPROVE",
  "old_status": "PENDING_REVIEW",
  "new_status": "NORMAL",
  "result": "SUCCESS",
  "comment": "审核通过",
  "created_at": "2026-04-01T10:00:00"
}
```

---

# 8. 用户管理

## GET `/users`

**权限：ADMIN**

查询用户。

### Query

| 参数 | 类型 | 默认值 |
|---|---|---|
| page | int | 1 |
| page_size | int | 20 |

### Response

分页 `UserResponse`。

---

## POST `/users`

**权限：ADMIN**

创建用户。

### Request

```json
{
  "username": "staff02",
  "password": "123456",
  "real_name": "李四",
  "role": "STAFF",
  "enabled": true
}
```

### Response

```json
{
  "id": 2,
  "username": "staff02",
  "real_name": "李四",
  "role": "STAFF",
  "enabled": true,
  "created_at": "2026-04-01T10:00:00"
}
```

---

## PUT `/users/{id}/status`

**权限：ADMIN**

修改用户启用状态。

### Request

```json
{
  "enabled": false
}
```

停用后，该用户不能登录。

---

# 9. 错误码

| code | 含义 |
|---:|---|
| 0 | 成功 |
| 40001 | 参数/业务校验失败 |
| 40101 | 未登录或 Token 失效 |
| 40301 | 无权限 |
| 40302 | 当前状态不允许操作 |
| 40401 | 数据不存在 |
| 40901 | 数据版本冲突 |
| 40902 | 已存在进行中的重复业务 |
| 50001 | 系统内部错误 |

---

# 10. 核心枚举

### Role

`ADMIN` / `STAFF` / `AUDITOR`

### SupplierStatus

`DRAFT` / `PENDING_REVIEW` / `NORMAL` / `RETURNED` / `SUSPENDED` / `ELIMINATED`

### EvaluationStatus

`PENDING_REVIEW` / `APPROVED` / `RETURNED`

### LifecycleRequestType

`SUSPEND` / `RESUME`

### LifecycleRequestStatus

`PENDING` / `APPROVED` / `REJECTED`

### Decision

`APPROVE` / `REJECT`

### DocType

`BUSINESS_LICENSE` / `OTHER`

### Grade

`A` / `B` / `C` / `D`