# SRM MVP 前后端 API 契约 (v1)

> 依据 `docs/TDD.docx`（供应商管理系统设计文档 V1.2）与补充需求书 V1.5 制定。本文档是 `srm-backend/` 与 `srm-frontend/` 的唯一共同事实来源（single source of truth）；任何接口变更必须先改这份文档再改代码。

## 0. 全局约定

- **Base URL**: `/api/v1`
- **鉴权**: 除 `POST /api/v1/auth/login` 外，所有接口都必须携带 `Authorization: Bearer <token>`；后端对每个接口按角色强制校验，前端隐藏按钮只是体验优化，不是安全边界。
- **命名风格**: 请求体、响应体、query 参数统一使用 `snake_case`，前端 TS 类型字段名与 JSON 字段名保持一致（不做大小写转换，杜绝映射遗漏引发的字段丢失）。
- **统一响应体**：

```ts
interface ApiResponse<T> {
  code: number;        // 0 = 成功；非 0 见第 8 节错误码表
  message: string;
  data: T | null;
}
```

- **分页**：请求携带 `page`（从 1 开始，默认 1）、`page_size`（默认 20，最大 100）；响应 `data` 统一为：

```ts
interface PageResult<T> {
  list: T[];
  total: number;
  page: number;
  page_size: number;
}
```

- **时间格式**：ISO-8601，如 `2026-09-28T10:00:00`。
- **乐观锁**：所有会被并发审核的资源（`Supplier`、`PerformanceEvaluation`）响应体都带 `version` 字段；写操作（如审核决策）必须在请求体里回传 `version`，后端用它做 MyBatis-Plus 乐观锁校验，版本不匹配返回 `40901`。
- **角色枚举** `Role`: `ADMIN` | `STAFF` | `AUDITOR`

---

## 1. 认证 Auth

### `POST /api/v1/auth/login`

Request:
```json
{ "username": "staff01", "password": "••••••" }
```

Response `data`:
```json
{
  "token": "eyJhbGciOi...",
  "user_id": 2,
  "username": "staff01",
  "real_name": "张三",
  "role": "STAFF"
}
```

TS:
```ts
interface LoginRequest { username: string; password: string; }
interface LoginResponse {
  token: string;
  user_id: number;
  username: string;
  real_name: string;
  role: 'ADMIN' | 'STAFF' | 'AUDITOR';
}
```

MVP 演示账号（启动时按需播种，仅当 `users` 表为空）：`admin/Admin@123`、`staff01/Staff@123`、`auditor01/Auditor@123`。

---

## 2. 供应商 Supplier

### 状态机

`DRAFT`（草稿/待提交） → `PENDING_REVIEW`（待审核，AUDITOR 审核中）→ 通过 → `NORMAL`（正常合作）；驳回 → `RETURNED`（待修改/已驳回，STAFF 改后可重新 `submit` 回到 `PENDING_REVIEW`）。`NORMAL ⇄ SUSPENDED`（停用/恢复）只能通过第 3 节的生命周期申请流转，不允许直接 PATCH 状态字段。`NORMAL`/`SUSPENDED` → `ELIMINATED`（淘汰，终态、不可逆；须经第 3 节生命周期申请流程：STAFF 提交 `ELIMINATE` 申请，AUDITOR 审批通过后生效，不允许 AUDITOR 跳过申请直接变更状态）。

`SupplierStatus` = `'DRAFT' | 'PENDING_REVIEW' | 'NORMAL' | 'RETURNED' | 'SUSPENDED' | 'ELIMINATED'`

### 对象定义

```ts
interface SupplierResponse {
  id: number;
  name: string;
  tax_no: string;               // 统一社会信用代码/税号，唯一
  type: string;                 // 供应商类型
  contact_name: string;
  contact_phone: string;
  contact_email?: string;
  address?: string;
  remark?: string;
  business_license_url?: string;
  effective_date: string;       // 生效日期
  expiry_date: string;          // 失效日期
  status: SupplierStatus;
  version: number;              // 乐观锁版本号
  created_by: number;
  created_by_name: string;
  created_at: string;
  updated_at: string;
  latest_performance_grade?: 'A' | 'B' | 'C' | 'D';
  risk_warning: boolean;        // 最近一次归档评价为 C/D 级时置 true
}

interface SupplierCreateRequest {
  name: string;
  tax_no: string;
  type: string;
  contact_name: string;
  contact_phone: string;
  contact_email?: string;
  address?: string;
  remark?: string;
  effective_date: string;
  expiry_date: string;
}
type SupplierUpdateRequest = Partial<SupplierCreateRequest>;
```

**校验规则**：`name`/`tax_no`/`type`/`contact_name`/`contact_phone`/`effective_date`/`expiry_date` 必填；`tax_no` 全局唯一（数据库唯一索引 + 应用层预检）；`contact_phone` 格式校验；`contact_email` 若填写需符合邮箱格式。

### 接口

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | `/suppliers` | STAFF | 建档，创建 `DRAFT` |
| PUT | `/suppliers/{id}` | STAFF（本人） | 仅 `DRAFT`/`RETURNED` 可编辑，其余状态 `40302` |
| GET | `/suppliers` | ALL | query: `status?`, `keyword?`, `page`, `page_size` |
| GET | `/suppliers/{id}` | ALL | 详情，含最近一次归档绩效评级 |
| DELETE | `/suppliers/{id}` | STAFF（本人） | 仅本人 `DRAFT` 逻辑删除，其余状态 `40302` |
| POST | `/suppliers/{id}/submit` | STAFF（本人） | `DRAFT`/`RETURNED` → `PENDING_REVIEW`；要求已上传至少一份未过期营业执照资质，否则 `40001` |
| POST | `/suppliers/{id}/audit` | AUDITOR | 审核决策，见下 |
| POST | `/suppliers/{id}/qualifications` | STAFF（本人） | 上传资质，仅 `DRAFT`/`RETURNED` |
| GET | `/suppliers/{id}/qualifications` | ALL | 资质列表 |

### `POST /suppliers/{id}/audit`

Request:
```ts
interface SupplierAuditDecisionRequest {
  version: number;
  decision: 'APPROVE' | 'REJECT';
  comment?: string;   // decision=REJECT 时必填
}
```
- `APPROVE`：`PENDING_REVIEW → NORMAL`
- `REJECT`：`PENDING_REVIEW → RETURNED`，`comment` 必填
- 全量写 `AuditLog`；`version` 不匹配返回 `40901`（说明期间被并发修改，前端需重新拉取详情）。

Response `data`: `SupplierResponse`

### 文件上传

供应商建档/资质相关的文件（营业执照等）先经此接口上传换取 `file_url`，再把 `file_url` 填入 `QualificationCreateRequest`；本接口本身不写业务表，不做归属校验。

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | `/files/upload` | STAFF | `multipart/form-data`，字段名 `file` |

**校验规则**：仅允许 `pdf`/`jpg`/`jpeg`/`png`；单文件不超过 10MB；超出限制或类型不符返回 `40001`。

**存储方式**：MVP 阶段存本地磁盘即可，不需要接云存储/OSS；后端通过静态资源映射（如 `/uploads/**` → 本地目录）对外提供返回的 `file_url`。

Response `data`:
```ts
interface FileUploadResponse {
  file_url: string;   // 直接用于 QualificationCreateRequest.file_url
  file_name: string;  // 原始文件名
  size: number;        // 字节
}
```

### 资质 Qualification

```ts
interface QualificationResponse {
  id: number;
  supplier_id: number;
  doc_type: 'BUSINESS_LICENSE' | 'OTHER';
  file_url: string;
  effective_date: string;
  expiry_date: string;
  expired: boolean;     // 由 expiry_date < 今天 计算得出
  created_at: string;
}
interface QualificationCreateRequest {
  doc_type: 'BUSINESS_LICENSE' | 'OTHER';
  file_url: string;
  effective_date: string;
  expiry_date: string;
}
```

---

## 3. 生命周期申请（停用 / 恢复）

停用/恢复不是简单状态切换，而是"STAFF 提申请 → AUDITOR 终审"的两步流程，用独立资源 `LifecycleRequest` 记录，避免污染供应商状态枚举。

```ts
type LifecycleRequestType = 'SUSPEND' | 'RESUME' | 'ELIMINATE';
type LifecycleRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

interface LifecycleRequestResponse {
  id: number;
  supplier_id: number;
  type: LifecycleRequestType;
  reason: string;
  status: LifecycleRequestStatus;
  applied_by: number;
  applied_by_name: string;
  applied_at: string;
  decided_by?: number;
  decided_by_name?: string;
  decided_at?: string;
  decision_comment?: string;
}
interface LifecycleRequestCreateRequest {
  type: LifecycleRequestType;
  reason: string;   // 必填：停用需说明原因（如 D 级绩效/合规问题），恢复需说明整改情况，淘汰需说明依据（如长期停用未整改/重大合规问题）
}
interface LifecycleDecisionRequest {
  decision: 'APPROVE' | 'REJECT';
  comment?: string;
}
```

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | `/suppliers/{id}/lifecycle-requests` | STAFF | `type=SUSPEND` 仅当供应商当前为 `NORMAL`；`type=RESUME` 仅当当前为 `SUSPENDED`；`type=ELIMINATE` 仅当当前为 `NORMAL` 或 `SUSPENDED`；同一供应商同一类型已有 `PENDING` 申请时返回 `40902` |
| GET | `/suppliers/{id}/lifecycle-requests` | ALL | 申请历史 |
| POST | `/lifecycle-requests/{id}/decision` | AUDITOR | `APPROVE` 且 `type=SUSPEND` → 供应商置 `SUSPENDED`；`APPROVE` 且 `type=RESUME` → 供应商置 `NORMAL`；`APPROVE` 且 `type=ELIMINATE` → 供应商置 `ELIMINATED`（终态，之后不可再对该供应商创建任何生命周期申请）；`REJECT` 只改申请状态，供应商状态不变。全量写 `AuditLog` |

---

## 4. 绩效评价（核心防篡改铁律）

**前端只能提交客观事实，绝不能提交或覆盖分数/评级** —— 这是接口设计的强制约束，`PerformanceFactRecord` 里物理上没有分数字段。

### 4.1 固定权重与评级规则（后端硬编码，不可配置、不可由前端影响）

| 维度 | 权重 |
|---|---|
| 质量 Quality | 30% |
| 交付 Delivery | 25% |
| 价格 Price | 20% |
| 服务 Service | 15% |
| 合规 Compliance | 10% |

- `Q = MAX(0, 100 - (qc_failed_batches / qc_total_batches * 100 * 1.5) - major_accidents * 50)`；若 `qc_total_batches = 0`，`Q = 100` 且打 `quality_exempt = true` 标签（"当期无业务发生-免考"）。
- `D = MAX(0, 100 - (delayed_batches / total_batches * 100) - avg_delay_days * 2)`
- `P = MAX(0, 100 - price_deviation_rate * 2)`
- `S = MAX(0, 100 - complaint_overtime_count * 10)`
- `C = 100`（资质全部有效），任一资质 `expiry_date` 已过期则 `C = 0` 并置 `risk_warning = true`（合规维度由后端自动读取 `Qualification`，前端不传）。
- 综合得分 `= Q*30% + D*25% + P*20% + S*15% + C*10%`
- 评级：`A ≥ 90`；`80 ≤ B < 90`；`70 ≤ C < 80`；`D < 70`。
- 评级联动：`A/B` 不触发任何拦截；`C` 维持 `NORMAL` 但 `risk_warning = true`（前端展示预警，不做订单拦截，本期无采购模块）；`D` 只置 `risk_warning = true` 并要求人工走第 3 节的停用申请流程，评价接口本身**不会**自动变更供应商状态。

### 4.2 数据结构

```ts
interface PerformanceFactRecord {
  total_batches: number;
  delayed_batches: number;
  avg_delay_days: number;
  qc_total_batches: number;
  qc_failed_batches: number;
  major_accidents: number;
  price_deviation_rate: number;   // %
  complaint_overtime_count: number;
}

interface PerformanceEvaluationResult {
  score_quality: number;
  score_delivery: number;
  score_price: number;
  score_service: number;
  score_compliance: number;
  total_score: number;
  grade: 'A' | 'B' | 'C' | 'D';
  quality_exempt: boolean;
  risk_warning: boolean;
}

type EvaluationStatus = 'PENDING_REVIEW' | 'APPROVED' | 'RETURNED';

interface PerformanceEvaluationResponse {
  id: number;
  supplier_id: number;
  period_start: string;
  period_end: string;
  fact_record: PerformanceFactRecord;
  result: PerformanceEvaluationResult;
  status: EvaluationStatus;
  version: number;
  created_by: number;
  created_by_name: string;
  created_at: string;
  reviewed_by?: number;
  reviewed_at?: string;
  review_comment?: string;
}

type DraftStatus = 'DRAFT' | 'SUBMITTED';

interface PerformanceEvaluationDraftResponse {
  id: number;
  supplier_id: number;
  period_start: string;
  period_end: string;
  mode: 'manual' | 'mock';
  fact_record?: PerformanceFactRecord;
  status: DraftStatus;
  created_by: number;
  created_by_name: string;
  created_at: string;
  updated_at: string;
}

interface PerformanceEvaluationDraftCreateRequest {
  supplier_id: number;
  period_start: string;
  period_end: string;
  mode: 'manual' | 'mock';
  fact_record?: PerformanceFactRecord;   // mode=manual 时必填；mode=mock 时即使传了也会被忽略
}
```

> 对应 TDD 6.2 节 D4_1 `PerformanceEvaluationDraft`（评价草稿表）：STAFF 发起评价、录入客观事实明细时先落草稿；`submit` 才触发算分引擎并生成正式的 `PerformanceEvaluationResponse` 记录（`PENDING_REVIEW`）。**不存在跳过草稿直接一步创建正式评价的接口**——这一点覆盖了本文档此前"没有独立的草稿态 API"的旧结论。

### 4.3 接口

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | `/performance/evaluations/drafts` | STAFF | 发起评价，创建草稿，`status=DRAFT` |
| PUT | `/performance/evaluations/drafts/{id}` | STAFF（本人） | 仅 `status=DRAFT` 且 `mode=manual` 可改 `fact_record` |
| DELETE | `/performance/evaluations/drafts/{id}` | STAFF（本人） | 仅 `status=DRAFT` 可删，逻辑删除 |
| GET | `/performance/evaluations/drafts/{id}` | ALL | 草稿详情 |
| GET | `/performance/evaluations/drafts` | ALL | query: `supplier_id?`, `status?`, `page`, `page_size` |
| POST | `/performance/evaluations/drafts/{id}/submit` | STAFF（本人） | 见下，提交草稿触发算分，生成正式评价并锁死进入 `PENDING_REVIEW` |
| PUT | `/performance/evaluations/{id}` | STAFF（本人） | 仅 `status=RETURNED` 可改事实重新提交，重新触发算分 |
| POST | `/performance/evaluations/{id}/review` | AUDITOR | 复核：`APPROVE` 归档、`REJECT` 退回 |
| GET | `/performance/evaluations` | ALL | query: `supplier_id?`, `status?`, `page`, `page_size` |
| GET | `/performance/evaluations/{id}` | ALL | 详情 |

### `POST /performance/evaluations/drafts`

Request: `PerformanceEvaluationDraftCreateRequest`（见上）

- `mode=manual`：由 `ManualFactInputAdapter` 校验 `fact_record`（非负、`qc_failed_batches ≤ qc_total_batches`、`delayed_batches ≤ total_batches` 等），校验失败 `40001`。
- `mode=mock`：创建草稿时立即由 `MockDataMetricAdapter` 按种子规则自动生成事实数据并存入草稿（演示/测试专用），**前端传入的 `fact_record` 会被服务端忽略而不是报错**，避免误以为可以借 mock 模式夹带自定义分数；`mock` 草稿的 `fact_record` 不允许后续修改。
- 创建草稿不做同周期唯一性校验（唯一性校验在 `submit` 时进行）。

Response `data`: `PerformanceEvaluationDraftResponse`

### `PUT /performance/evaluations/drafts/{id}`

仅当前用户是创建人、`status=DRAFT`、`mode=manual` 时可改 `fact_record`，否则 `40302`。Response `data`: `PerformanceEvaluationDraftResponse`。

### `POST /performance/evaluations/drafts/{id}/submit`

- 仅当前用户是创建人且 `status=DRAFT`，否则 `40302`。
- 同一 `supplier_id` + `period_start`/`period_end` 已存在未归档（`PENDING_REVIEW`/`RETURNED`）正式评价单时返回 `40902`（同周期只允许一张在途评价单，防止多线程算分冲突）。
- 提交成功后：由 `PerformanceScoreCalculator` 读取草稿 `fact_record` 完成加权计算与评级映射，创建正式评价记录且 `status = PENDING_REVIEW`（评分提交后立即进入待复核且数据不可逆锁死）；草稿本身置 `status=SUBMITTED` 并逻辑删除，不可再编辑或再次提交。

Response `data`: `PerformanceEvaluationResponse`

### `POST /performance/evaluations/{id}/review`

Request:
```ts
interface PerformanceReviewDecisionRequest {
  version: number;
  decision: 'APPROVE' | 'REJECT';
  comment?: string;   // REJECT 时必填
}
```
- `APPROVE`：归档进历史表，形成永久记录，之后不可再编辑；供应商 `latest_performance_grade`/`risk_warning` 同步更新。
- `REJECT`：`status → RETURNED`，原 STAFF 可通过 `PUT /performance/evaluations/{id}` 改事实重提（沿用同一条记录，不新建，保持"一个考核周期一张单"的约束）。

---

## 5. 审计日志

```ts
interface AuditLogResponse {
  id: number;
  entity_type: 'SUPPLIER' | 'PERFORMANCE_EVALUATION' | 'PERFORMANCE_EVALUATION_DRAFT' | 'LIFECYCLE_REQUEST';
  entity_id: number;
  operator_id: number;
  operator_name: string;
  operator_role: Role;
  action: string;          // 如 SUBMIT / AUDIT_APPROVE / AUDIT_REJECT / SUSPEND_APPROVE ...
  old_status?: string;
  new_status?: string;
  result: 'SUCCESS' | 'REJECTED';
  comment?: string;
  created_at: string;
}
```

`GET /api/v1/audit-logs?entity_type=&entity_id=&page=&page_size=`（ALL 角色可查看，前端按"业务范围内"过滤展示，后端不做行级隔离）。

---

## 6. 用户管理（ADMIN）

```ts
interface UserResponse {
  id: number;
  username: string;
  real_name: string;
  role: Role;
  enabled: boolean;
  created_at: string;
}
interface UserCreateRequest {
  username: string;
  password: string;
  real_name: string;
  role: Role;
}
```

| 方法 | 路径 | 角色 |
|---|---|---|
| GET | `/users` | ADMIN |
| POST | `/users` | ADMIN |
| PATCH | `/users/{id}/status` | ADMIN — body: `{ enabled: boolean }` |

---

## 7. 适配器模式说明（供后端实现对齐）

> TDD 对适配器命名前后不一致（6.1/7.2/8.1 节各不相同，甚至同名类在不同章节指代不同模式）。本节命名是后端实现唯一依据，Qoder 按本节生成类名，不需要与 TDD 逐字对应。

`PerformanceMetricProvider` 接口只负责"取事实数据"（DFD 中的 P6），不做算分：

- `ManualFactInputAdapter`：校验并直通 STAFF 提交的 `fact_record`。
- `MockDataMetricAdapter`：按种子/预设规则生成演示用事实数据。
- 二期预留 `ExternalSystemMetricAdapter`（对接 ERP/WMS），本期不实现，只保证接口可扩展。

算分（P7）由唯一的 `PerformanceScoreCalculator` 完成，任何 adapter 都必须把结果交给它计算，不允许各自实现加权逻辑。Controller 按请求里的 `mode` 字段从 `Map<String, PerformanceMetricProvider>` 中取 bean，禁止写 if/else 判断模式。

---

## 8. 错误码

| code | 含义 |
|---|---|
| 0 | 成功 |
| 40001 | 参数校验失败（含必填缺失、格式错误、事实数据非法） |
| 40101 | 未登录 / token 失效 |
| 40301 | 无权限（角色不匹配） |
| 40302 | 当前状态不允许该操作（如非草稿态编辑、非本人操作） |
| 40401 | 资源不存在 |
| 40901 | 并发冲突（乐观锁版本不匹配） |
| 40902 | 业务冲突（重复的在途申请/评价单） |
| 50001 | 服务器内部错误 |
