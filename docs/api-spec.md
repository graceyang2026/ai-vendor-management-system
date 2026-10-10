# SRM MVP 前后端 API 契约 (v1)

> 依据 `docs/TDD.docx`（供应商管理系统设计文档 V1.3，基线 2026-09-29）与补充需求书 V1.5 制定。本文档是 `srm-backend/` 与 `srm-frontend/` 的唯一共同事实来源（single source of truth）；任何接口变更必须先改这份文档再改代码。

## 版本修订记录

> 铁律：本文档任何契约变更均须先在此登记一行，再改代码；未登记的改动视为无效契约。表头沿用项目统一标准（与 `docs/TDD.docx`、需求规格说明书 V1.5 的「版本修订记录」表一致）。

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
- **数据可见性分层隔离（裁决 20261011，彻底取代旧文“后端不做行级隔离、前端按业务范围内过滤”）**：`GET /suppliers`、`GET /suppliers/{id}`、`GET /suppliers/{id}/qualifications`、`GET /audit-logs` 均由**后端强制隔离**，禁止依赖前端过滤（与本文 §0“前端隐藏不是安全边界”口径一致）：
  - **规则 A｜私有在途区**：`status IN ('DRAFT','RETURNED')` 的供应商档案，后端强制附加 `created_by = 当前用户`；其他人既查不到也改不了（列表直接过滤掉，详情越权访问返回 `40302`）。
  - **规则 B｜公有资产区**：`status IN ('NORMAL','SUSPENDED','ELIMINATED')` **不限 `created_by`**，全体登录角色只读共享——供应商一旦准入即公司级公共资产，否则跨业务员协同无法进行。
  - **规则 C｜审计日志范围**：`STAFF` 只见「本人操作留痕 + 名下资产留痕」，即 `operator_id = 当前用户` OR `supplier_id IN (SELECT id FROM supplier WHERE created_by = 当前用户)`；`AUDITOR` / `ADMIN` 全局可见。
  - **ADMIN 例外**：因权限矩阵“查看供应商档案与日志 ✓（系统管理需要）”，ADMIN 不受规则 A 的本人限制，但仍是**只读**（写操作一律 `40301`）。
  - **落地复用现有 RBAC 底座（不新造轮子）**：角色粗粒度拦截沿用 Controller `@PreAuthorize("hasRole('...')")`（`SecurityConfig` 已 `@EnableMethodSecurity`）；本人/状态细粒度校验沿用 `com.srm.core.security.RoleGuard` 已有三方法——`requireRole`→`40301`、`requireStatusAllowed`→`40302`、`requireSelf(ownerId)`→`40302`；当前用户 ID 从 `CurrentUserProvider.require()` 取 `UserPrincipal.getId()`。规则 A/C 的列表隔离只需在查询条件里拼 `created_by` / `supplier_id`，**不引入部门树、数据权限组等多维模型**。
  - **实现前提（DDL）**：`audit_log` 需新增冗余列 `supplier_id`（见下方「目标态 DDL 增量」②）——该表现无此列，若无此列，“名下资产留痕”无法用单一条件覆盖 `PERFORMANCE_EVALUATION` / `LIFECYCLE_REQUEST` 等非 `SUPPLIER` 实体。

- **目标态 DDL 增量（裁决 20261011；自本文起升为唯一权威记录，《SRM 后端数据库表设计.md》不再作为核对依据）**：数据库**现状事实源为 `srm-backend/src/main/resources/schema.sql`**（下“现状”列均已实测读出）。下列三项属待落地增量，归“供应商业务代码批次”任务卡；未落地前本契约按目标态描述接口行为，后端不得因库里还没这些列而改变接口定义。

| # | 对象 | `schema.sql` / 代码现状 | 需新增（目标态） | 支撑的裁决 |
|---|---|---|---|---|
| ① | `supplier_qualification` | 仅 `id`/`supplier_id`/`doc_type`/`file_url`/`effective_date`/`expiry_date`/`created_at`/`updated_at`/`deleted` | `status VARCHAR(30) NOT NULL DEFAULT 'PENDING_REVIEW'`、`reviewed_by BIGINT NULL`、`reviewed_at DATETIME NULL`、`review_comment VARCHAR(500) NULL` | 资质审核闭环：`NORMAL` 下补传必须承载“待审核版本”；同一 `supplier_id`+`doc_type` 只允许一条 `APPROVED` |
| ② | `audit_log` | 无 `supplier_id` 列（现有索引：`entity_type+entity_id`、`operator_id`、`created_at`） | `supplier_id BIGINT NULL` + `KEY idx_supplier_id (supplier_id)`；写日志时冗余写入归属供应商 ID | 规则 C 审计行级隔离：`supplier_id IN (本人创建供应商)` 需用单一条件覆盖非 `SUPPLIER` 实体留痕 |
| ③ | `EntityType` 枚举（`audit/EntityType.java`） | 仅 `SUPPLIER`/`PERFORMANCE_EVALUATION`/`LIFECYCLE_REQUEST`/`USER` 四值 | 增 `SUPPLIER_QUALIFICATION` | 资质上传/审核动作可审计（`/qualifications/{id}/review` 全量写 `AuditLog`） |

  已落地的同类范式（无需新增，实现 ①② 时照抄写法即可）：`performance_evaluation.active_period_key` + `uk_supplier_active_period`、`lifecycle_request.active_key` + `uk_supplier_active_lifecycle` 都是“逻辑删除友好的虚拟生成列 + 部分唯一索引”；若需强约束“同一类型仅一条 `APPROVED`”，同法用生成列实现，不引入触发器。

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

用户名不存在、密码错误、或用户 `enabled = false`，统一返回 `40102`（不区分具体原因，避免暴露"用户名是否存在"这类信息）。

---

## 2. 供应商 Supplier

### 状态机

`DRAFT`（草稿/待提交） → `PENDING_REVIEW`（待审核，AUDITOR 审核中）→ 通过 → `NORMAL`（正常合作）；驳回 → `RETURNED`（待修改/已驳回，STAFF 改后可重新 `submit` 回到 `PENDING_REVIEW`）。`NORMAL ⇄ SUSPENDED`（停用/恢复）只能通过第 3 节的生命周期申请流转，不允许直接 PATCH 状态字段。`NORMAL`/`SUSPENDED` → `ELIMINATED`（淘汰，终态、不可逆；须经第 3 节生命周期申请流程：STAFF 提交 `ELIMINATE` 申请，AUDITOR 审批通过后生效，不允许 AUDITOR 跳过申请直接变更状态）。

> **上游冲突已裁决（20261011，站 TDD）**：SRS V1.5 §4「资质控制机制」原句“…或由审计员视风险程度，手动将该供应商状态变更为‘停用’”与 TDD《资质过期与风险预警》“不允许 AUDITOR 绕过流程直接修改状态”相悖。按 **TDD 口径**定案：停用只能由 STAFF 提 `SUSPEND` 申请、AUDITOR 在 `POST /lifecycle-requests/{id}/decision` 裁决；**本契约不提供任何直接改 `supplier.status` 的旁路接口**。SRS 侧对应修订已列入待办（需用户确认后才能动 DOCX），修订前以本文为准。

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

**校验规则**（裁决 20261010，口径来自需求规格说明书 V1.5「数据校验机制」；**后端 Bean Validation（`@NotBlank`/`@Pattern`）与前端表单正则必须逐字同源**，不得两侧各写一套）：

失败统一返回 `code = 40001`（枚举名以已实现代码为准：`ErrorCode.VALIDATION_FAILED`；详见下方“传输方式”），`message` 用本表固定文案。

| 字段 | 规则 | 正则 | 失败 message |
|---|---|---|---|
| `name` | 必填；trim 后非空且不得为纯空格；**2 ~ 64** 字符 | `^\S.{0,62}\S$`（首尾非空白，含中间共 2~64 字符） | `供应商名称格式不合法（不得为空格且长度需为2~64字符）` |
| `tax_no` | 必填；严格符合 **GB 32100 十八位** 统一社会信用代码；字符集为数字 + 大写字母，**不含 I/O/Z/S/V** | `^[0-9A-HJ-NPQRTUWXY]{18}$` | `统一社会信用代码格式不合法（必须为18位有效字符）` |
| `type` | 必填；取值必须在 `SupplierType` 枚举内 | — | `供应商类型不合法` |
| `contact_name` | 必填；trim 后非空且不得为纯空格；**2 ~ 64** 字符 | 同 `name` | `联系人姓名格式不合法（不得为空格且长度需为2~64字符）` |
| `contact_phone` | 必填；中国大陆标准 **11 位手机号**（MVP 暂不强求座机号，聚焦主流） | `^1[3-9]\d{9}$` | `联系电话格式不合法（必须为11位有效手机号）` |
| `contact_email` | 选填；一旦填写必须合法邮箱格式 | 常规邮箱格式 | `联系邮箱格式不合法` |
| `effective_date` / `expiry_date` | 必填；`yyyy-MM-dd`；`expiry_date` 必须晚于 `effective_date` | — | `日期格式不合法或失效日期早于生效日期` |
| `address` / `remark` | 选填，无格式校验 | — | — |

> 长度上限取值说明：`name` 与 `contact_name` 均为 **2 ~ 64** 字符（用户裁决 20261010：`contact_name` 按统一口径放宽到 64，不再以旧列宽 50 为限）。配套 DDL：`supplier.contact_name` 由 `VARCHAR(50)` 扩为 **`VARCHAR(100)`**（与同表 `contact_email VARCHAR(100)` 对齐，为业务上限 64 留出余量）——属 `schema.sql` 变更，已归入后端实现任务卡，**未落地前契约先行**，校验与写库不得出现 50/64 两套口径。`tax_no` 18 位落在 `VARCHAR(50)` 内。校验在 Controller 入参层一次完成，Service 不重复校验；`PUT /suppliers/{id}` 沿用同一套规则（仅对提交的字段生效）。

**唯一性**：`tax_no` 全局唯一（数据库唯一索引 + 应用层预检，撞未删除供应商的已有税号返回 `40902`；被逻辑删除供应商的税号因生成列 `tax_no_active` 置 NULL 而不占用，允许重新建档）——格式校验（`40001`）先行，唯一性校验（`40902`）在后。

**必填与 NULL 分层（裁决 20261011）**：必填靠**应用层强校验**实现，不靠数据库约束——DTO 上用 `@NotBlank`/`@NotNull`/`@Pattern`，缺项或非法一律在 Controller 层被 `40001` 拦下。数据库侧保持现状：`name`/`tax_no` 已是 `NOT NULL` 的列**不动**（当兜底防呆），`contact_name`/`contact_phone`/`contact_email`/`address` 等允许 `NULL` 的列**不收紧**（保留历史数据兼容性，避免后续加字段或灰度迁移时因存量脏数据导致 DDL 报错）。两层不冲突：写入路径严格拦，读取路径允许列为 `null` 并按空值展示。本裁决**不产生任何 DDL 变更**。

**传输方式**：本节所有 `40001` 失败**仍随 HTTP 200 返回**，错信息在统一响应体的 `code`/`message` 里（已实现：`GlobalExceptionHandler` 直接返回 `ApiResponse`，`@ExceptionHandler(MethodArgumentNotValidException.class)` 取首个字段错误 message；前端 `utils/request.ts` 拦截器同样按 `body.code` 判失败）。本契约不以 HTTP 状态码传递业务错。

### 接口

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | `/suppliers` | STAFF | 建档，创建 `DRAFT` |
| PUT | `/suppliers/{id}` | STAFF（本人） | 仅 `DRAFT`/`RETURNED` 可编辑，其余状态 `40302` |
| GET | `/suppliers` | ALL（**按 §0 隔离规则**） | query: `status?`, `keyword?`, `page`, `page_size`；STAFF 结果集 = 公有资产区 + 本人草稿/退回；AUDITOR/ADMIN 为全量 |
| GET | `/suppliers/{id}` | ALL（**按 §0 隔离规则**） | 详情，含最近一次归档绩效评级；命中规则 A 但非本人 → `40302` |
| DELETE | `/suppliers/{id}` | STAFF（本人） | 仅本人 `DRAFT` 逻辑删除，其余状态 `40302` |
| POST | `/suppliers/{id}/submit` | STAFF（本人） | `DRAFT`/`RETURNED` → `PENDING_REVIEW`；要求已上传至少一份未过期营业执照资质，否则 `40001` |
| POST | `/suppliers/{id}/audit` | AUDITOR | 审核决策，见下 |
| POST | `/suppliers/{id}/qualifications` | STAFF（本人） | 上传资质，允许状态由 `DRAFT`/`RETURNED` **放宽为 `DRAFT`/`RETURNED`/`NORMAL`**（裁决 20261011：解除“准入后资质无法维护”死锁）；新记录一律落 `status=PENDING_REVIEW`，**不直接生效** |
| GET | `/suppliers/{id}/qualifications` | ALL（**按 §0 隔离规则**） | 资质列表；默认仅返回 `APPROVED`（现行有效）与 `PENDING_REVIEW`（待审核）记录，`REJECTED` 仅供本人追溯 |
| POST | `/qualifications/{id}/review` | AUDITOR | 资质审核闭环：`APPROVE` → 新资质置 `APPROVED`，同 `supplier_id`+`doc_type` 的旧资质自动置 `REJECTED`（历史保留不物理删）；`REJECT` → 新资质置 `REJECTED`，**旧资质保持不变**，供应商 `NORMAL` 基础合作状态不受影响 |

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
  effective_date: string;       // 单项证照有效期（与 supplier 表同名字段不同义，见下方说明）
  expiry_date: string;
  status: 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED';   // 新增：裁决 20261011 资质审核闭环
  expired: boolean;             // 由 expiry_date < 今天 计算得出；仅对 status=APPROVED 参与合规算分
  created_at: string;
  reviewed_by?: number;         // 新增：审核人
  reviewed_at?: string;         // 新增：审核时间
  review_comment?: string;      // 新增：驳回意见
}
interface QualificationCreateRequest {
  doc_type: 'BUSINESS_LICENSE' | 'OTHER';
  file_url: string;
  effective_date: string;
  expiry_date: string;
}
```

**资质审核闭环（裁决 20261011，对应需求“业务员上传及维护资质文件”+ TDD“资质过期人工提 SUSPEND”两者打通）**：

- **提交即审核**：任何状态（含 `NORMAL`）上传的新资质一律落 `PENDING_REVIEW`，不直接参与算分与有效性判定。
- **平滑过渡无真空**：审核完成前，**旧的 `APPROVED` 资质保持生效位**（即“已过期但仍为现行版”），不会出现“无有效资质”的真空报错；`REJECTED` 记录只作历史追溯，不物理删除。
- **审核通过联动**：新资质置 `APPROVED` → 同类型旧资质自动置 `REJECTED` → 该 `doc_type` 不再处于过期态 → **`risk_warning` 自动置 `false`，合规得分 `C` 恢复 100**（详见第 4 节）。
- **审核驳回**：STAFF 重新上传，**期间不影响供应商当前 `NORMAL` 基础合作状态**（仅 `C=0` 与 `risk_warning=true` 的预警保留）。
- **日期字段作用域**：`supplier.effective_date`/`expiry_date` = **企业合作契约周期**（DB 注释“合作开始/结束日期”）；`supplier_qualification.effective_date`/`expiry_date` = **单项证照有效期**。两者同名不同义，互不联动；**供应商档案 `expiry_date` 到期在 MVP 内不触发任何自动动作**（无定时任务、不自动停用），仅作为展示与合作区间记录（裁决 20261011：“到期行为”明确为空，防止实现者自行发挥）。

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
  version: number;                 // 乐观锁版本号（对齐 §0 铁律：决策改供应商带版本 status，不回传或版本不匹配 → 40901）
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
- `C = 100`（资质全部有效），任一**现行有效（`status=APPROVED`）**资质 `expiry_date` 已过期则 `C = 0` 并置 `risk_warning = true`（合规维度由后端自动读取 `Qualification`，前端不传）。`PENDING_REVIEW`/`REJECTED` 的资质**不参与**判定；因此审核通过新资质的瞬间，`C` 自动恢复 100 且 `risk_warning` 自动置 `false`（裁决 20261011 闭环）。
- 综合得分 `= Q*30% + D*25% + P*20% + S*15% + C*10%`
- 评级：`A ≥ 90`；`80 ≤ B < 90`；`70 ≤ C < 80`；`D < 70`。
- 评级联动：`A/B` 不触发任何拦截；`C` 维持 `NORMAL` 但 `risk_warning = true`（前端展示预警，不做订单拦截，本期无采购模块）；`D` 只置 `risk_warning = true` 并要求人工走第 3 节的停用申请流程，评价接口本身**不会**自动变更供应商状态。

> **零业务免考机制（Zero-Business Exemption）**：**`qc_total_batches = 0` 分支已写入 TDD V1.4（2026-10-11 修订）**——当期无质检/业务发生则 `Q` 直接计 100 分并标记 `quality_exempt = true`，避免除零异常与无业务期间的虚假扣分。本契约按用户裁决把同一规则**对称扩展到交付维度**：`total_batches = 0` 时 `D` 也计 100 分并复用同一个 `quality_exempt` 标识，同时封住 `D` 公式中 `delayed_batches / total_batches` 的除零未定义缺口（旧版契约只写了 `Q` 分支，属契约遗漏）。`quality_exempt` 列名为历史原因，语义为“当期无业务的整体免考标识”，**不拆分为 `delivery_exempt`**（MVP 不加列）。**待回写上游**：`D` 维度分支需同步补进 TDD，补之前以本契约为准。

### 4.2 数据结构

```ts
interface PerformanceFactRecord {
  total_batches: number;        // 交付总批次（专用于履约交付维度 D 计算）
  delayed_batches: number;      // 逾期批次数
  avg_delay_days: number;       // 平均逾期天数
  qc_total_batches: number;     // 质检总批次（专用于质量得分 Q 及零业务免考判断）
  qc_failed_batches: number;    // 质检不合格批数
  major_accidents: number;      // 重大事故数
  price_deviation_rate: number;   // 价格偏离率 %
  complaint_overtime_count: number; // 客诉超时次数
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
  updated_at: string;
  reviewed_by?: number;
  reviewed_at?: string;
  review_comment?: string;
}

interface PerformanceEvaluationCreateRequest {
  supplier_id: number;
  period_start: string;
  period_end: string;
  mode: 'manual' | 'mock';               // ① 轻量演示标识：mock 由服务端生成事实，manual 用前端事实
  fact_record?: PerformanceFactRecord;   // mode=manual 必填；mode=mock 即使传入也被忽略
}

interface PerformanceEvaluationUpdateRequest {
  fact_record: PerformanceFactRecord;    // 仅 RETURNED 状态重提时可改
  version: number;
}
```

> 对应 TDD §6 D4_1 `PerformanceEvaluationDraft`：该表**仅为后端算分解耦与过程审计的内部暂存表（Staging Table）**，系统**不向 API 与前端暴露任何【草稿】业务状态或草稿操作接口**（依 TDD 定性 + 用户 2026-10-10「以 TDD 为准」裁决）。STAFF 一次录入客观事实并 `POST /performance/evaluations` 即由后端算分、直接落 `PENDING_REVIEW`；同周期唯一在途校验在本 create 接口内完成。

### 4.3 接口

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | `/performance/evaluations` | STAFF | 一步发起评价：`mode=manual` 校验事实 / `mode=mock` 服务端生成事实，后端算分并落 `status=PENDING_REVIEW`；同 `supplier_id`+`period` 已有未归档在途（`PENDING_REVIEW`/`RETURNED`）返回 `40902` |
| PUT | `/performance/evaluations/{id}` | STAFF（本人） | 仅 `status=RETURNED` 可改 `fact_record` 重提，重新触发算分，沿用同一 `id` |
| POST | `/performance/evaluations/{id}/review` | AUDITOR | 复核：`APPROVE` 归档、`REJECT` 退回 |
| GET | `/performance/evaluations` | ALL | query: `supplier_id?`, `status?`, `page`, `page_size` |
| GET | `/performance/evaluations/{id}` | ALL | 详情 |

### `POST /performance/evaluations`

Request: `PerformanceEvaluationCreateRequest`（见上）

- `mode=manual`：由 `ManualFactInputAdapter` 校验 `fact_record`（非负、`qc_failed_batches ≤ qc_total_batches`、`delayed_batches ≤ total_batches` 等），校验失败 `40001`。
- `mode=mock`：由 `MockDataMetricAdapter` 按种子规则自动生成客观事实（演示/测试专用），**前端传入的 `fact_record` 被服务端忽略而不是报错**，避免借 mock 模式夹带自定义分数。
- create 内先查同 `supplier_id`+`period_start`/`period_end` 是否已有未归档（`PENDING_REVIEW`/`RETURNED`）在途评价单，存在则 `40902`（同周期只允许一张在途，防并发算分冲突）。并发竞态由 DB 虚拟生成列唯一索引 `uk_supplier_active_period` 兜底（**已落地于 `schema.sql`**：`active_period_key` 生成列 + `UNIQUE KEY uk_supplier_active_period`），命中 `DuplicateKey` 同样返回 `40902`。
- 通过后由 `PerformanceScoreCalculator` 完成加权计算与评级映射，一步落 `status=PENDING_REVIEW`（提交即锁死，无草稿态）。

Response `data`: `PerformanceEvaluationResponse`

### `PUT /performance/evaluations/{id}`

仅当前用户是创建人且 `status=RETURNED` 时可改 `fact_record` 重提，否则 `40302`；重提重新触发算分，沿用同一条记录（不新建）。Response `data`: `PerformanceEvaluationResponse`。

### `POST /performance/evaluations/{id}/review`

Request:
```ts
interface PerformanceReviewDecisionRequest {
  version: number;
  decision: 'APPROVE' | 'REJECT';
  comment?: string;   // REJECT 时必填
}
```
- `APPROVE`：`status → APPROVED`，即**归档态**——同一张 `performance_evaluation` 表内的永久记录，**无独立历史表**（`schema.sql` 中无此类表，且不得新增）；之后不可再编辑；供应商 `latest_performance_grade`/`risk_warning` 同步更新。
- `REJECT`：`status → RETURNED`，原 STAFF 可通过 `PUT /performance/evaluations/{id}` 改事实重提（沿用同一条记录，不新建，保持"一个考核周期一张单"的约束）。

---

## 5. 审计日志

```ts
interface AuditLogResponse {
  id: number;
  entity_type: 'SUPPLIER' | 'PERFORMANCE_EVALUATION' | 'LIFECYCLE_REQUEST' | 'USER' | 'SUPPLIER_QUALIFICATION';   // 末项为裁决 20261011 资质审核闭环新增（需后端 `EntityType` 枚举同步增项）
  entity_id: number;
  operator_id: number;
  operator_name: string;
  operator_role: Role;
  action: string;          // SUBMIT / AUDIT_APPROVE / AUDIT_REJECT / REVIEW_APPROVE / REVIEW_REJECT / DECISION_APPROVE / DECISION_REJECT；用户管理（entity_type=USER）：CREATE_USER / UPDATE_USER / ENABLE_USER / DISABLE_USER，完整枚举以后端 `audit/AuditAction.java` 为准（当前 11 值，已逐一对应）
  old_status?: string;
  new_status?: string;
  result: 'SUCCESS' | 'REJECTED';
  comment?: string;
  created_at: string;
}
```

`GET /api/v1/audit-logs?entity_type=&entity_id=&page=&page_size=`——**后端强制行级隔离**（裁决 20261011，删除旧版“ALL 角色可查看、前端按业务范围内过滤、后端不做行级隔离”的错误表述）：`STAFF` 仅返回 `operator_id = 当前用户` OR `supplier_id IN (本人创建的供应商)` 的记录；`AUDITOR` / `ADMIN` 全量可见。实现依赖 `audit_log` 新增 `supplier_id` 冗余列与 `SUPPLIER_QUALIFICATION` 枚举项（均见 §0「目标态 DDL 增量」②③）。

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
| PUT | `/users/{id}` | ADMIN — body: `UserUpdateRequest`（仅可改 `real_name`/`role`；出现 `username`/`password` 字段 → 40001 拒绝；改自己角色 → 40302） |
| PATCH | `/users/{id}/status` | ADMIN — body: `{ enabled: boolean }`（停用自己 → 40302；重复设置幂等） |

```ts
interface UserUpdateRequest {
  real_name?: string;
  role?: Role;
}
```

> 审计留痕：`POST /users`（`CREATE_USER`）、`PATCH /users/{id}/status`（`ENABLE_USER`/`DISABLE_USER`）及角色/资料修改（`UPDATE_USER`）成功后，均写入 `audit_log`，`entity_type=USER`、`entity_id` 为目标用户 ID、`old_status`/`new_status` 记录 `enabled` 变化（新增时 `old_status` 为空）。

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
| 40102 | 用户名或密码错误（登录失败） |
| 40301 | 无权限（角色不匹配） |
| 40302 | 当前状态不允许该操作（如非草稿态编辑、非本人操作） |
| 40401 | 资源不存在 |
| 40901 | 并发冲突（乐观锁版本不匹配） |
| 40902 | 业务冲突（重复的在途申请/评价单/税号唯一性冲突） |
| 40903 | 用户名已存在（用户管理；含被逻辑删除占用的用户名） |
| 50001 | 服务器内部错误 |
