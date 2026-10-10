# 后端接口设计文档（Java 内部接口 / 类设计）

> 本文档与 `docs/api-spec.md`（对外 HTTP/JSON 契约）是配套关系，不是重复：`api-spec.md` 定义前后端之间"传什么"，本文档定义后端内部"用什么 Java 接口/类结构去实现"——实体字段、Mapper/Service/Controller 方法签名、适配器模式的接口形态。这是给 Qoder 落地代码时用的设计蓝图，本身**不包含任何实现逻辑**，只有签名、字段、职责说明。
>
> 业务规则以 `.qoder/rules.md` 第 4 节为准，字段/接口路径以 `docs/api-spec.md` 为准；本文档若与两者冲突，以那两个文件为准，需回来同步修订本文档。

## 版本修订记录

> 铁律：本文档任何设计变更均须先在此登记一行，再改代码；与已实现代码不一致时，**以已实现代码为准并回写本文档**。表头沿用项目统一标准（与 `docs/TDD.docx`、需求规格说明书 V1.5 的「版本修订记录」表一致）。

---

## 1. 包结构总览

```
com.srm.core
├── entity            实体（MyBatis-Plus 映射对象）
├── mapper            BaseMapper<T> 接口
├── service           Service 接口
│   └── impl          Service 实现类
├── controller        REST Controller
├── calculator        绩效算分引擎：PerformanceMetricProvider + 适配器 + 唯一计算器
├── dto               请求/响应 DTO（按业务模块分子包）
│   ├── auth
│   ├── supplier
│   ├── qualification
│   ├── lifecycle
│   ├── performance
│   ├── auditlog
│   └── user
├── security          JWT 鉴权
├── common            统一响应体 / 异常 / 错误码 / 枚举
│   └── enums
└── config            MyBatis-Plus / Security / 演示账号种子配置
```

---

## 2. 实体（Entity）字段设计

> 逻辑删除字段 `deleted`、乐观锁字段 `version` 仅在需要的实体上出现。所有实体的 `id` 均为 `Long`，`@TableId(type = IdType.AUTO)`。时间字段统一 `LocalDateTime`（日期字段用 `LocalDate`）。

### 2.1 `User`（表 `sys_user`）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | Long | PK |
| username | String | 唯一 |
| password_hash | String | BCrypt 编码，**绝不出现在任何 Response DTO** |
| real_name | String | |
| role | String（`Role` 枚举） | ADMIN / STAFF / AUDITOR |
| enabled | Boolean | 停用账号不允许登录 |
| created_at | LocalDateTime | |
| updated_at | LocalDateTime | |

### 2.2 `Supplier`（表 `supplier`）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | Long | PK |
| name | String | |
| tax_no | String | 唯一索引 |
| type | String | |
| contact_name / contact_phone / contact_email | String | contact_email 可空 |
| address / remark | String | 可空 |
| effective_date / expiry_date | LocalDate | |
| status | String（`SupplierStatus` 枚举） | DRAFT/PENDING_REVIEW/NORMAL/RETURNED/SUSPENDED/ELIMINATED |
| version | Integer | `@Version`，乐观锁 |
| deleted | Integer | `@TableLogic`，仅本人 DRAFT 允许置 1 |
| created_by | Long | FK → users.id |
| created_at / updated_at | LocalDateTime | |
| latest_performance_grade | String（可空） | 缓存自最近一次**归档**评价，评价归档时同步写回，不单独提供写接口 |
| risk_warning | Boolean | 同上，随归档评价同步 |

> `business_license_url`（api-spec.md 里 SupplierResponse 的字段）**不作为 Supplier 表的持久化列**，由 Service 组装 Response 时从该供应商最新一条 `doc_type=BUSINESS_LICENSE` 的 `Qualification` 中取 `file_url` 填入，避免数据冗余不同步。

### 2.3 `Qualification`（表 `supplier_qualification`）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | Long | PK |
| supplier_id | Long | FK |
| doc_type | String（`DocType` 枚举） | BUSINESS_LICENSE / OTHER |
| file_url | String | |
| effective_date / expiry_date | LocalDate | 单项证照有效期（与 `Supplier` 实体同名字段不同义） |
| status | String（`QualificationStatus` 枚举） | `PENDING_REVIEW` / `APPROVED` / `REJECTED`（V1.5 新增） |
| reviewed_by | Long | 审核人（AUDITOR）用户 ID，可空（V1.5 新增） |
| reviewed_at | LocalDateTime | 审核时间，可空（V1.5 新增） |
| review_comment | String | 审核意见，驳回时必填（V1.5 新增） |
| created_at | LocalDateTime | |

> `expired` 字段**不持久化**，Response 组装时用 `expiry_date.isBefore(LocalDate.now())` 实时计算，**且仅对 `status = APPROVED` 的记录参与合规算分与 `risk_warning` 判定**（裁决 20261011）。

### 2.4 `PerformanceEvaluation`（表 `performance_evaluation`）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | Long | PK |
| supplier_id | Long | FK |
| period_start / period_end | LocalDate | 与 supplier_id 组合做"同周期唯一在途"校验：服务层预检 + DB 虚拟生成列唯一索引 `uk_supplier_active_period` 兜底（**已落地于 `schema.sql`**：`active_period_key` 生成列 + 唯一索引） |
| mode | String | `manual` \| `mock`，仅记录取数方式，不影响算分逻辑 |
| total_batches / delayed_batches | Integer | 事实字段（交付） |
| avg_delay_days | BigDecimal | |
| qc_total_batches / qc_failed_batches | Integer | 事实字段（质量） |
| major_accidents | Integer | |
| price_deviation_rate | BigDecimal | 事实字段（价格），% |
| complaint_overtime_count | Integer | 事实字段（服务） |
| score_quality / score_delivery / score_price / score_service / score_compliance | BigDecimal | **后端计算结果，无对应写接口字段** |
| total_score | BigDecimal | |
| grade | String（`Grade` 枚举 A/B/C/D） | |
| quality_exempt | Boolean | |
| risk_warning | Boolean | |
| status | String（`EvaluationStatus` 枚举） | PENDING_REVIEW / APPROVED / RETURNED |
| version | Integer | `@Version` |
| created_by | Long | FK |
| created_at | LocalDateTime | |
| reviewed_by | Long（可空） | FK |
| reviewed_at | LocalDateTime（可空） | |
| review_comment | String（可空） | |

> 命名提醒（踩坑点）：合规维度分数字段命名为 `scoreCompliance`，**不要**用单字母变量名 `c`/`C`，会跟评级字母 `C` 混淆，这是命名层面的强制要求，不是随意建议。

### 2.5 `LifecycleRequest`（表 `lifecycle_request`）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | Long | PK |
| supplier_id | Long | FK |
| type | String（`LifecycleRequestType` 枚举） | SUSPEND / RESUME / ELIMINATE |
| reason | String | 必填 |
| status | String（`LifecycleRequestStatus` 枚举） | PENDING / APPROVED / REJECTED |
| applied_by | Long | FK |
| applied_at | LocalDateTime | |
| decided_by | Long（可空） | FK |
| decided_at | LocalDateTime（可空） | |
| decision_comment | String（可空） | |

### 2.6 `AuditLog`（表 `audit_log`，只插入不更新）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | Long | PK |
| entity_type | String（`EntityType` 枚举） | SUPPLIER / PERFORMANCE_EVALUATION / LIFECYCLE_REQUEST / USER |
| entity_id | Long | |
| operator_id | Long | FK |
| operator_role | String（`Role` 枚举） | 落库时的快照角色，不随用户后续改角色变化 |
| action | String（`AuditAction` 枚举，共 11 码，与 `audit/AuditAction.java` 逐一对应） | 业务域：SUBMIT / AUDIT_APPROVE / AUDIT_REJECT / REVIEW_APPROVE / REVIEW_REJECT / DECISION_APPROVE / DECISION_REJECT（停用/恢复/淘汰统一用 DECISION_*）；用户域（entity_type=USER）：CREATE_USER / UPDATE_USER / ENABLE_USER / DISABLE_USER |
| old_status / new_status | String（可空） | |
| result | String（`AuditResult` 枚举） | SUCCESS / REJECTED |
| comment | String（可空） | |
| created_at | LocalDateTime | |

---

## 3. Mapper 接口

全部继承 `com.baomidou.mybatisplus.core.mapper.BaseMapper<T>`，默认 CRUD 用 MyBatis-Plus 提供的方法 + `QueryWrapper`/`LambdaQueryWrapper` 组装，**不需要为简单分页/条件查询写 XML**。仅以下场景建议落到 `src/main/resources/mapper/*.xml` 手写 SQL（避免 MyBatis-Plus 的 `Wrapper` 拼出低效或难读的 SQL）：

```java
public interface UserMapper extends BaseMapper<User> {}
public interface SupplierMapper extends BaseMapper<Supplier> {}
public interface QualificationMapper extends BaseMapper<Qualification> {}
public interface PerformanceEvaluationMapper extends BaseMapper<PerformanceEvaluation> {}
public interface LifecycleRequestMapper extends BaseMapper<LifecycleRequest> {}
public interface AuditLogMapper extends BaseMapper<AuditLog> {}
```

- `SupplierMapper`：`GET /suppliers` 的 `keyword` 需要模糊匹配 `name` 或 `tax_no`，用 `LambdaQueryWrapper.and(w -> w.like(Supplier::getName, kw).or().like(Supplier::getTaxNo, kw))` 即可，不需要 XML。
- `PerformanceEvaluationMapper`：同周期在途评价单校验（§4.4）可以直接 `selectCount` + `LambdaQueryWrapper`，不需要 XML。
- 目前设计下**没有强制需要手写 XML 的查询**；`resources/mapper/` 目录先保留为空骨架，只有当某个查询用 Wrapper 表达明显别扭（例如未来接入多表统计报表）时才落地 XML 文件，避免为了"看起来完整"硬凑 XML。

`created_by_name` / `operator_name` / `applied_by_name` / `decided_by_name` 这类需要"根据 user_id 查 real_name"的展示字段：Service 层组装 Response 时按需批量查询 `UserMapper.selectBatchIds(ids)` 后在内存里 map 一次，**不要在循环里逐条查询**（避免 N+1）。

---

## 4. Service 接口方法签名

### 4.1 `AuthService`

```java
public interface AuthService {
    LoginResponse login(LoginRequest request);
}
```

### 4.2 `SupplierService`

```java
public interface SupplierService {
    SupplierResponse create(SupplierCreateRequest request, UserPrincipal operator);
    SupplierResponse update(Long id, SupplierUpdateRequest request, UserPrincipal operator);
    SupplierResponse getById(Long id, UserPrincipal operator);
    PageResult<SupplierResponse> list(String status, String keyword, int page, int pageSize, UserPrincipal operator);
    void delete(Long id, UserPrincipal operator);
    SupplierResponse submit(Long id, UserPrincipal operator);
    SupplierResponse audit(Long id, SupplierAuditDecisionRequest request, UserPrincipal operator);
    QualificationResponse addQualification(Long supplierId, QualificationCreateRequest request, UserPrincipal operator);
    List<QualificationResponse> listQualifications(Long supplierId, UserPrincipal operator);
    QualificationResponse reviewQualification(Long qualificationId, QualificationReviewRequest request, UserPrincipal operator);
}
```

- `create`：只允许 `STAFF`，落地状态 `DRAFT`，`created_by` = operator.id。`tax_no` 唯一性双层校验：应用层预检（`tax_no` 相同且 `deleted = 0` 的记录已存在）→ `BusinessException(DUPLICATE_IN_PROGRESS)`（`40902`，友好报错）；并发穿透预检时由 DB `UNIQUE(tax_no_active)` 兜底，Service 捕获 `DuplicateKeyException` 同样转译 `40902`（裁决 20261010：税号冲突归 `40902` 业务冲突族，见 api-spec 第 2 节校验规则 + 错误码表）。逻辑删除供应商的 `tax_no_active` 为 NULL 不占键位，允许重新沿用该税号。字段格式校验由 Controller 层 Bean Validation 承担（`@NotBlank`/`@Pattern`，正则与 api-spec 第 2 节校验规则表逐字同源）：格式非法 → `VALIDATION_FAILED`（`40001`）先行，唯一性冲突 → `40902` 在后，Service 不重复校验。
- `update`：只允许状态 `DRAFT`/`RETURNED` 且 `operator.id == supplier.createdBy`，否则抛 `BusinessException(ErrorCode.STATUS_NOT_ALLOWED)`（→ HTTP 层映射 `40302`）。
- `submit`：校验至少存在一条未过期的 `BUSINESS_LICENSE` 资质，否则 `ErrorCode.VALIDATION_FAILED`（`40001`）；成功后调用 `SupplierStateMachine.transition(...)` 并写 `AuditLog`。
- `audit`：`version` 不匹配 → `ErrorCode.OPTIMISTIC_LOCK_CONFLICT`（`40901`）；`decision=REJECT` 但 `comment` 为空 → `40001`。
- **数据隔离（裁决 20261011，目标态）**：`getById`/`list`/`listQualifications` 必须把 `operator` 带进查询条件，不得取全量后交前端过滤：`STAFF` 得「公有资产区（`NORMAL`/`SUSPENDED`/`ELIMINATED`）+ 本人 `DRAFT`/`RETURNED`」；`AUDITOR`/`ADMIN` 得全量（ADMIN 仅只读，写操作 `40301`）。命中规则 A 但非本人 → `STATUS_NOT_ALLOWED`（`40302`）。
- `addQualification`：允许状态由 `DRAFT`/`RETURNED` **放宽为额外包含 `NORMAL`**（解除“准入后资质无法维护”死锁）；仍限 `operator.id == supplier.created_by`（ADMIN 不参与业务写），新记录一律 `status = PENDING_REVIEW`，**不直接生效**。
- `reviewQualification`（V1.5 新增）：只允许 `AUDITOR`；`APPROVE` → 新资质置 `APPROVED`，同事务将同 `supplier_id` + `doc_type` 的旧 `APPROVED` 记录置 `REJECTED`（不物理删），并重算 `risk_warning`（无过期有效资质则置 `false`）；`REJECT` → 新资质置 `REJECTED`，旧资质保持不变，供应商状态不变；`decision=REJECT` 但 `comment` 为空 → `40001`；全程写 `AuditLog`（`entity_type=SUPPLIER_QUALIFICATION`，复用 `AUDIT_APPROVE`/`AUDIT_REJECT`）。

### 4.3 `SupplierStateMachine`

```java
public interface SupplierStateMachine {
    /** 校验 from→to 是否为合法迁移，非法则抛 BusinessException(STATUS_NOT_ALLOWED) */
    void assertTransition(SupplierStatus from, SupplierStatus to);

    /** 返回某状态下允许迁移到的下一状态集合，供 Controller/前端权限展示复用 */
    Set<SupplierStatus> allowedNext(SupplierStatus from);
}
```

> 状态机本身不直接操作数据库，只做"迁移是否合法"的纯函数判断；实际的落库 + 审计日志写入在 `SupplierServiceImpl` 里完成，调用前必须先过 `assertTransition`。

### 4.4 `PerformanceEvaluationService`

```java
public interface PerformanceEvaluationService {
    PerformanceEvaluationResponse create(PerformanceEvaluationCreateRequest request, UserPrincipal operator);
    PerformanceEvaluationResponse update(Long id, PerformanceEvaluationUpdateRequest request, UserPrincipal operator);
    PerformanceEvaluationResponse review(Long id, PerformanceReviewDecisionRequest request, UserPrincipal operator);
    PerformanceEvaluationResponse getById(Long id);
    PageResult<PerformanceEvaluationResponse> list(Long supplierId, String status, int page, int pageSize);
}
```

- `create` 内部流程：① 按 `request.mode` 从 `Map<String, PerformanceMetricProvider>` 取 provider → `provider.resolveFacts(request, supplier)` 得到 `PerformanceFactRecord`；② 查询该 `supplier_id` 是否已有非终态（`PENDING_REVIEW`/`RETURNED`）记录同 `period_start`/`period_end`，存在则 `ErrorCode.DUPLICATE_IN_PROGRESS`（`40902`）；并发竞态由 DB 唯一索引 `uk_supplier_active_period` 兜底，INSERT 命中 `DuplicateKeyException` 同样转译 `40902`；③ 查询该供应商资质是否有过期 → 得到 `qualificationsExpired: boolean`；④ `PerformanceScoreCalculator.calculate(factRecord, qualificationsExpired)` 得到 `PerformanceEvaluationResult`；⑤ 落库，`status=PENDING_REVIEW`；⑥ 写 `AuditLog(action=SUBMIT, entity_type=PERFORMANCE_EVALUATION)`（一步式发起评价复用 SUBMIT 动作，靠 entity_type 与供应商建档区分）。
- `update`：仅 `status=RETURNED` 且本人可调用，重新走②之后的④⑤（沿用同一行 `id`，不新建）。
- `review`：`APPROVE` → `status=APPROVED`，同步回写 `Supplier.latest_performance_grade`/`risk_warning`；`REJECT` → `status=RETURNED`，`comment` 必填。

### 4.5 `LifecycleRequestService`

```java
public interface LifecycleRequestService {
    LifecycleRequestResponse create(Long supplierId, LifecycleRequestCreateRequest request, UserPrincipal operator);
    List<LifecycleRequestResponse> listBySupplier(Long supplierId);
    LifecycleRequestResponse decide(Long id, LifecycleDecisionRequest request, UserPrincipal operator);
}
```

- `create`：`type=SUSPEND` 要求 `supplier.status==NORMAL`；`type=RESUME` 要求 `supplier.status==SUSPENDED`；`type=ELIMINATE` 要求 `supplier.status` 为 `NORMAL` 或 `SUSPENDED`；供应商已处终态 `ELIMINATED` 时拒绝任何新申请；已存在同 `supplier_id`+`type` 的 `PENDING` 记录 → `40902`（并发兜底：DB 唯一索引 `uk_supplier_active_lifecycle` **已落地于 `schema.sql`**，命中 `DuplicateKeyException` 转译 40902）。
- `decide`：首先校验 `request.version` 与关联供应商当前 `version` 一致（对齐 §0 乐观锁铁律），不一致 → `ErrorCode.OPTIMISTIC_LOCK_CONFLICT`（`40901`）；`APPROVE` 时才联动 `SupplierService`（内部方法，非对外接口）把供应商状态置 `SUSPENDED`/`NORMAL`/`ELIMINATED`（`type=ELIMINATE` 通过后为终态，之后不可再建任何申请）；`REJECT` 只改本记录状态。全程写 `AuditLog(entity_type=LIFECYCLE_REQUEST)`。

### 4.6 `AuditLogService`

```java
public interface AuditLogService {
    void record(EntityType entityType, Long entityId, UserPrincipal operator,
                String action, String oldStatus, String newStatus,
                AuditResult result, String comment);
    /** V1.5 新增重载：带冗余 supplier_id 的审计写入（供应商域动作必须用本重载） */
    void record(EntityType entityType, Long entityId, Long supplierId, UserPrincipal operator,
                String action, String oldStatus, String newStatus,
                AuditResult result, String comment);
    PageResult<AuditLogResponse> list(String entityType, Long entityId, int page, int pageSize, UserPrincipal operator);
}
```

> **为何用重载而不改现有 8 参签名**（裁决 20261011）：`record(...)` 现有 8 参签名已被 `UserServiceImpl` 等处调用并有测试覆盖，改签名会连带改动已有实现与用例；旧签名内部委托 `supplierId = null`（用户域审计本就无归属供应商），新增重载只给供应商域使用，**向后兼容、不破坏现有测试**。
>
> `list(...)` 新增 `operator` 是**必须**的：否则无法实现 api-spec §0 规则 C 的行级隔离（STAFF 仅 `operator_id = 本人` OR `supplier_id IN 本人名下供应商`）。现实现 `AuditLogServiceImpl.list` 无此参数且 `audit_log` 表无 `supplier_id` 列，两者分属代码与 DDL 增量（DDL 目标态的权威记录已内联至 api-spec §0「目标态 DDL 增量」②，现状以 `schema.sql` 为准）。

> `record(...)` 内部的 Mapper 写入失败**不得向上抛出**中断主业务事务（已有测试 `AuditLogServiceImplTest#recordNeverPropagatesMapperFailures` 覆盖这一约束）——记日志失败只应打 ERROR 日志，不应导致审核/提交等主操作跟着回滚失败。

### 4.7 `UserService`

> 以已实现代码 `service/UserService.java` 为准（裁决 20261010，"文档落后于代码则修文档"原则）：所有写操作携带操作人 `UserPrincipal`，用于防自锁与审计留痕。

```java
public interface UserService {
    /** 分页查询，created_at 降序 */
    PageResult<UserResponse> list(int page, int pageSize);

    /** 新增（BCrypt 编码密码）；用户名已存在 → 40903；成功后审计 CREATE_USER */
    UserResponse create(UserCreateRequest request, UserPrincipal operator);

    /** 仅可改 real_name/role；请求体含非 null username/password → 40001；改自己角色 → 40302；不存在 → 40401；成功后审计 UPDATE_USER */
    UserResponse update(Long id, UserUpdateRequest request, UserPrincipal operator);

    /** 启用/停用；停用自己 → 40302；幂等（目标态与当前一致不写 DB/审计）；不存在 → 40401；成功后审计 ENABLE_USER/DISABLE_USER */
    void updateStatus(Long id, UserStatusUpdateRequest request, UserPrincipal operator);
}
```

### 4.8 `FileService`（支撑 `POST /files/upload`，api-spec 第 2 节）

```java
public interface FileService {
    /** 校验并落盘，返回可直接填入 QualificationCreateRequest.file_url 的访问地址；不写任何业务表 */
    FileUploadResponse upload(MultipartFile file);
}
```

实现约束（与 api-spec 文件上传节逐条对齐）：
- **校验**：扩展名 + MIME **双重白名单**（仅 `pdf`/`jpg`/`jpeg`/`png`，扩展名取自 `original_filename`、MIME 取自 `content_type`，两者都命中白名单才放行）；单文件≤10MB；不满足 → `BusinessException(VALIDATION_FAILED)`（40001）。空文件/文件名缺失同样 40001。
- **防覆盖与防注入**：落盘文件名 = `UUID.randomUUID() + 小写扩展名`，丢弃原始文件名拼接路径（杜绝 `../` 路径穿越）；原始文件名仅在响应体 `file_name` 回显。
- **存储**：MVP 本地磁盘，目录由配置项 `srm.upload.dir` 控制（默认 `./uploads`，不入仓）；不接 OSS。
- **对外访问**：`WebMvcConfigurer#addResourceHandlers` 将 `/uploads/**` 映射到 `file:{srm.upload.dir}/`；`file_url` = `{服务基址}/uploads/{存储文件名}`。
- **无业务副作用**：不写表、不做归属校验、不记审计（上传本身不是业务状态变更，资质落库时由 `POST /suppliers/{id}/qualifications` 写审计）；Spring Security 层面仅要求登录 + `STAFF` 角色（@PreAuthorize）。

---

## 5. Controller 接口（对应 `docs/api-spec.md`）

> 所有方法返回 `ApiResponse<T>`；分页接口返回 `ApiResponse<PageResult<T>>`。角色校验用 `@PreAuthorize("hasRole('STAFF')")` 等，方法体内部仍需 Service 层二次校验（rules.md §4.1 铁律：前后端双重校验，Controller 的 `@PreAuthorize` 不是唯一防线）。

```java
@RestController @RequestMapping("/api/v1/auth")
class AuthController {
    ApiResponse<LoginResponse> login(@RequestBody @Valid LoginRequest request);
}

@RestController @RequestMapping("/api/v1/suppliers")
class SupplierController {
    @PreAuthorize("hasRole('STAFF')")
    ApiResponse<SupplierResponse> create(@RequestBody @Valid SupplierCreateRequest request);

    @PreAuthorize("hasRole('STAFF')")
    ApiResponse<SupplierResponse> update(@PathVariable Long id, @RequestBody @Valid SupplierUpdateRequest request);

    ApiResponse<PageResult<SupplierResponse>> list(@RequestParam(required = false) String status,
                                                    @RequestParam(required = false) String keyword,
                                                    @RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "20") int pageSize);

    ApiResponse<SupplierResponse> getById(@PathVariable Long id);

    @PreAuthorize("hasRole('STAFF')")
    ApiResponse<Void> delete(@PathVariable Long id);

    @PreAuthorize("hasRole('STAFF')")
    ApiResponse<SupplierResponse> submit(@PathVariable Long id);

    @PreAuthorize("hasRole('AUDITOR')")
    ApiResponse<SupplierResponse> audit(@PathVariable Long id, @RequestBody @Valid SupplierAuditDecisionRequest request);

    @PreAuthorize("hasRole('STAFF')")
    ApiResponse<QualificationResponse> addQualification(@PathVariable Long id, @RequestBody @Valid QualificationCreateRequest request);

    ApiResponse<List<QualificationResponse>> listQualifications(@PathVariable Long id);
}

// V1.5 新增（裁决 20261011 资质审核闭环）：路径为顶层 `/qualifications/{id}/review`，不挂在 `/suppliers/{id}` 下
@RestController @RequestMapping("/api/v1")
class QualificationController {
    @PreAuthorize("hasRole('AUDITOR')")
    ApiResponse<QualificationResponse> review(@PathVariable Long id, @RequestBody @Valid QualificationReviewRequest request);
}

@RestController @RequestMapping("/api/v1")
class LifecycleRequestController {
    @PreAuthorize("hasRole('STAFF')")
    ApiResponse<LifecycleRequestResponse> create(@PathVariable("id") Long supplierId,
                                                  @RequestBody @Valid LifecycleRequestCreateRequest request);

    ApiResponse<List<LifecycleRequestResponse>> listBySupplier(@PathVariable("id") Long supplierId);

    @PreAuthorize("hasRole('AUDITOR')")
    ApiResponse<LifecycleRequestResponse> decide(@PathVariable Long id, @RequestBody @Valid LifecycleDecisionRequest request);
}

@RestController @RequestMapping("/api/v1/performance/evaluations")
class PerformanceEvaluationController {
    @PreAuthorize("hasRole('STAFF')")
    ApiResponse<PerformanceEvaluationResponse> create(@RequestBody @Valid PerformanceEvaluationCreateRequest request);

    @PreAuthorize("hasRole('STAFF')")
    ApiResponse<PerformanceEvaluationResponse> update(@PathVariable Long id, @RequestBody @Valid PerformanceEvaluationUpdateRequest request);

    @PreAuthorize("hasRole('AUDITOR')")
    ApiResponse<PerformanceEvaluationResponse> review(@PathVariable Long id, @RequestBody @Valid PerformanceReviewDecisionRequest request);

    ApiResponse<PageResult<PerformanceEvaluationResponse>> list(@RequestParam(required = false) Long supplierId,
                                                                 @RequestParam(required = false) String status,
                                                                 @RequestParam(defaultValue = "1") int page,
                                                                 @RequestParam(defaultValue = "20") int pageSize);

    ApiResponse<PerformanceEvaluationResponse> getById(@PathVariable Long id);
}

@RestController @RequestMapping("/api/v1/audit-logs")
class AuditLogController {
    ApiResponse<PageResult<AuditLogResponse>> list(@RequestParam(required = false) String entityType,
                                                    @RequestParam(required = false) Long entityId,
                                                    @RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "20") int pageSize);
}

@RestController @RequestMapping("/api/v1/users")
class UserController {
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<PageResult<UserResponse>> list(@RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int pageSize);

    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<UserResponse> create(@RequestBody @Valid UserCreateRequest request);

    /** 仅可改 real_name/role；出现 username/password → 40001；改自己角色 → 40302；不存在 → 40401；成功后审计 UPDATE_USER */
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<UserResponse> update(@PathVariable Long id, @RequestBody @Valid UserUpdateRequest request);

    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<Void> updateStatus(@PathVariable Long id, @RequestBody @Valid UserStatusUpdateRequest request);
}

@RestController @RequestMapping("/api/v1/files")
class FileController {
    /** multipart/form-data，字段名 file；校验见 §4.8；成功返回 file_url/file_name/size；不写业务表 */
    @PreAuthorize("hasRole('STAFF')")
    ApiResponse<FileUploadResponse> upload(@RequestParam("file") MultipartFile file);
}
```

---

## 6. 绩效算分引擎接口（核心防篡改设计）

```java
/** P6：只负责"取事实数据"，绝不算分 */
public interface PerformanceMetricProvider {
    /**
     * @param request 客户端请求（mode=manual 时 fact_record 必填；mode=mock 时会被忽略）
     * @param supplier 目标供应商（用于 mock 模式按供应商画像生成更真实的演示数据，如有需要）
     * @return 校验/生成后的事实记录
     * @throws BusinessException(VALIDATION_FAILED) 当 manual 模式下事实数据非法
     */
    PerformanceFactRecord resolveFacts(PerformanceEvaluationCreateRequest request, Supplier supplier);
}

@Component("manual")
class ManualFactInputAdapter implements PerformanceMetricProvider { /* 校验非负、qc_failed<=qc_total、delayed<=total */ }

@Component("mock")
class MockDataMetricAdapter implements PerformanceMetricProvider { /* 忽略请求里的 fact_record，按种子规则生成 */ }

/** P7：唯一的算分实现，不是接口，禁止做成可插拔 */
@Component
class PerformanceScoreCalculator {
    /**
     * @param facts 已校验/已生成的事实记录
     * @param qualificationsExpired 由调用方（Service 层）预先查好的资质是否过期
     */
    PerformanceEvaluationResult calculate(PerformanceFactRecord facts, boolean qualificationsExpired);
}
```

Controller/Service 中按 `mode` 取 provider 的唯一合法写法：

```java
@Autowired
private Map<String, PerformanceMetricProvider> providers; // key = "manual" | "mock"

PerformanceMetricProvider provider = providers.get(request.getMode());
if (provider == null) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "unknown mode: " + request.getMode());
```

**禁止**出现 `if ("mock".equals(mode)) { ... } else { ... }` 这种写法——这是 rules.md §4.4 的强制要求，代码评审时的检查点。

---

## 7. DTO 命名与包位置对照表

DTO 的字段定义以 `docs/api-spec.md` 对应 TS `interface` 为唯一事实来源；本表只标注建议的 Java 类名与包位置，方便 Qoder 落地时对齐目录结构：

| api-spec.md 中的 TS 接口 | Java 类 | 包 |
|---|---|---|
| `LoginRequest` / `LoginResponse` | 同名 | `dto.auth` |
| `SupplierResponse` / `SupplierCreateRequest` / `SupplierUpdateRequest` | 同名 | `dto.supplier` |
| `SupplierAuditDecisionRequest` | 同名 | `dto.supplier` |
| `QualificationResponse` / `QualificationCreateRequest` / `QualificationReviewRequest` | 同名 | `dto.qualification`（`QualificationReviewRequest` 为 V1.5 新增：`decision` + `comment`，与 `SupplierAuditDecisionRequest` 同构） |
| `LifecycleRequestResponse` / `LifecycleRequestCreateRequest` / `LifecycleDecisionRequest` | 同名 | `dto.lifecycle` |
| `PerformanceFactRecord` | 同名 | `dto.performance` |
| `PerformanceEvaluationResult` | 同名 | `dto.performance` |
| `PerformanceEvaluationResponse` / `*CreateRequest` / `*UpdateRequest` / `PerformanceReviewDecisionRequest` | 同名 | `dto.performance` |
| `AuditLogResponse` | 同名 | `dto.auditlog` |
| `UserResponse` / `UserCreateRequest` | 同名 | `dto.user` |
| `FileUploadResponse` | 同名 | `dto.file` |

- DTO 字段命名采用**显式映射**口径：每个与前端契约对齐的字段均显式标注 `@JsonProperty("snake_case")`（如 `@JsonProperty("tax_no")`），**不依赖** `PropertyNamingStrategies.SNAKE_CASE` 全局配置（依据 rules.md §1 与项目既定决策：显式注解可杜绝映射遗漏导致的字段丢失）。
- 再次强调：`PerformanceFactRecord` 及所有 `*CreateRequest`/`*UpdateRequest` 里**物理上不能出现**任何分数/评级字段——这不是校验层面的约束，是类定义层面就不应该有这个字段。

---

## 8. Security 组件接口

```java
@Component
class JwtTokenProvider {
    String generateToken(UserPrincipal principal);
    boolean validateToken(String token);       // 过期/签名不合法均返回 false，不抛异常
    String getUsername(String token);
    Role getRole(String token);
}

class JwtAuthenticationFilter extends OncePerRequestFilter {
    // 从 Authorization: Bearer <token> 取 token，校验通过则塞入 SecurityContext；
    // 校验失败不抛异常，直接放行给后续的 RestAuthenticationEntryPoint 统一处理为 40101
}

@Component
class CustomUserDetailsService implements UserDetailsService {
    UserDetails loadUserByUsername(String username); // 找不到/enabled=false 均抛 UsernameNotFoundException
}

class UserPrincipal implements UserDetails {
    Long getId();
    Role getRole();
    // 其余为 UserDetails 标准方法
}

@Component
class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {
    // 未登录/token 失效 → 返回 ApiResponse.error(40101, "未登录或登录已过期")
}

@Component
class RestAccessDeniedHandler implements AccessDeniedHandler {
    // 角色不匹配 → 返回 ApiResponse.error(40301, "无权限")
}
```

---

## 9. Common 组件接口

```java
public class ApiResponse<T> {
    int code; String message; T data;
    static <T> ApiResponse<T> success(T data);
    static <T> ApiResponse<T> error(ErrorCode code, String message);
}

public class PageResult<T> {
    List<T> list; long total; int page; int pageSize;
}

public class BusinessException extends RuntimeException {
    BusinessException(ErrorCode code, String message);
    ErrorCode getCode();
}

public enum ErrorCode {
    SUCCESS(0), VALIDATION_FAILED(40001), UNAUTHORIZED(40101), LOGIN_FAILED(40102), FORBIDDEN(40301),
    STATUS_NOT_ALLOWED(40302), NOT_FOUND(40401), OPTIMISTIC_LOCK_CONFLICT(40901),
    DUPLICATE_IN_PROGRESS(40902), USERNAME_DUPLICATE(40903), INTERNAL_ERROR(50001);
    final int value;
}

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    ApiResponse<Void> handleBusiness(BusinessException ex);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ApiResponse<Void> handleValidation(MethodArgumentNotValidException ex); // → 40001

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ApiResponse<Void> handleOptimisticLock(OptimisticLockingFailureException ex); // → 40901

    @ExceptionHandler(Exception.class)
    ApiResponse<Void> handleUnknown(Exception ex); // → 50001，同时记 ERROR 日志
}
```

枚举（`common.enums`）：`Role`、`SupplierStatus`、`EvaluationStatus`、`Grade`、`LifecycleRequestType`、`LifecycleRequestStatus`、`DocType`、`EntityType`、`AuditResult`、`Decision`（APPROVE/REJECT，供审核类接口的 `decision` 字段共用）。

---

## 10. Config 组件

```java
@Configuration
class MybatisPlusConfig {
    @Bean
    MybatisPlusInterceptor mybatisPlusInterceptor(); // 内部添加 OptimisticLockerInnerInterceptor
}

@Configuration
@EnableMethodSecurity(prePostEnabled = true)
class SecurityConfig {
    @Bean SecurityFilterChain filterChain(HttpSecurity http); // 注册 JwtAuthenticationFilter，禁用 CSRF/Session
    @Bean PasswordEncoder passwordEncoder();                  // BCryptPasswordEncoder
}

@Component
class DemoUserSeeder implements ApplicationRunner {
    // 仅当 srm.seed-demo-users=true 且 users 表为空时，插入 admin/staff01/auditor01 三个演示账号
    void run(ApplicationArguments args);
}
```

---

## 11. 与已删除实现的关系说明

本文档覆盖的接口设计，此前曾由一次探索性实现完整落地过一版（含单元测试，148 个测试全绿，行覆盖率 89.6%），但该实现已按项目分工调整被清空，只保留空的包/目录骨架（`.gitkeep`）。本文档是对那次实现的设计要点回顾整理 + 与 `docs/api-spec.md` 重新校对后的结果，作为 Qoder 重新落地代码时的设计依据，**不代表已有可运行代码**。
