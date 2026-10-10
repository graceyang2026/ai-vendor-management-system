# Qoder 项目规则（供 AI Agent 遵循）

本仓库是前后端分离的双工程：`srm-backend/`（Spring Boot）+ `srm-frontend/`（Vue 3）。代码与契约不一致时以契约为准，发现不一致要先询问等人工确认，不能各写各的。
## 0. AI Agent 执行原则

- 本规则文件是本仓库的强制开发规范，AI Agent 必须始终遵守。
- 开始修改代码前，必须先检查相关现有实现、测试、`docs/api-spec.md` 及相关设计文档，不得凭空假设项目结构或业务规则。
- 完成代码修改后必须执行项目质量门禁；质量检查失败时不得宣称任务完成，必须继续分析、修复并重新验证，直到全部通过。
- 不得通过删除测试、弱化断言、降低覆盖率阈值、修改 Hook 或绕过质量检查来获得“通过”。

## 1. 全栈技术栈规范

### 后端 (`srm-backend/`)
- Spring Boot 3.5.14，Java 17，Maven（`cd srm-backend && ./mvnw ...`）
- 持久层：**MyBatis-Plus**（`com.baomidou:mybatis-plus-spring-boot3-starter`），禁止引入 Spring Data JPA / Hibernate
  - 表结构以 `src/main/resources/schema.sql` 为唯一事实来源（MyBatis-Plus 不做自动建表）。改表结构的流程（用户裁决 2026-10-11）：先向用户说明改动内容并取得确认，确认后由 AI 直接修改该文件；未得确认不得动手。
  - 复杂查询写在 `src/main/resources/mapper/*.xml`，SQL 避免 MySQL 专有函数（如 `IFNULL` 可用但避免 `GROUP_CONCAT`/`ON DUPLICATE KEY` 等强绑定写法），保证未来可迁移到 KingbaseES
  - 乐观锁：实体字段 `@Version private Integer version;` + `MybatisPlusConfig` 中注册 `OptimisticLockerInnerInterceptor`
  - 逻辑删除：实体字段 `@TableLogic private Integer deleted;`，全局配置已在 `application.yml` 里设置
- 数据库唯一为 MySQL（生产）/ 兼容 KingbaseES（演进），本地测试可用 H2；数据库凭据只放根目录 `.env`（参照 `.env.example`），禁止硬编码进代码或提交仓库
- 代码生成：Lombok（`@Data`/`@Builder` 等）
- 包命名：`com.srm.core`，新建类必须落在该包结构下：
  - `entity`：MyBatis-Plus `@TableName` 实体
  - `mapper`：`BaseMapper<T>` 接口
  - `service` + `service/impl`：接口 + 实现类模式
  - `controller`：构造器注入，返回 `ApiResponse<T>`（见 `common` 包的统一响应体）
  - `calculator`：`PerformanceMetricProvider` 接口、两个适配器、唯一的 `PerformanceScoreCalculator`
  - `dto`：请求/响应体，字段名与 `docs/api-spec.md` 完全一致（snake_case，不做大小写转换）
  - `security`：JWT 鉴权 + 角色校验
  - `common`：枚举、异常、统一响应体
  - `config`：MyBatis-Plus 插件、Spring Security 配置、演示账号种子

### 前端 (`srm-frontend/`)
- Vue 3 + `<script setup>` + TypeScript + Vite + Element Plus
- `src/api/` 用 axios 封装，按模块拆分请求函数；请求/响应类型必须来自 `src/types/`，禁止用 `any` 绕过契约
- `src/types/` 的接口定义必须与 `docs/api-spec.md` 逐字段对齐
- 路由级角色守卫：未登录跳转登录页；角色不匹配的页面/按钮直接隐藏（但这只是体验优化，真正的权限拦截在后端）

## 2. TDD 业务铁律

1. **先写测试，再写实现**：任何新功能/修复必须先补充或编写失败的单元测试（Red），再编写最小实现使其通过（Green），最后重构（Refactor）。
2. **禁止无测试提交业务逻辑**：Service/Controller 层的新增或修改方法，必须同批提交对应的 `*Test.java`。
3. **测试框架统一**：后端使用 JUnit 5 + Mockito（`@Mock`、`@InjectMocks`），不引入其他测试框架。
4. **边界与异常路径必须覆盖**：不仅测试 happy path，还要覆盖参数校验失败、空值、异常抛出、乐观锁冲突等分支。
5. **重构不改变可观察行为**：重构前后必须保证既有测试全部通过，不得为了让测试通过而弱化断言。

## 3. 单元测试覆盖率要求

- **所有代码修改必须通过项目 Hook 质量门禁（`.qoder/settings.json` PostToolUse Hook）；失败必须继续修复直至通过**。
  - 后端 Java：单元测试全部通过，且 JaCoCo 行/分支覆盖率均 **≥85%**。
  - 前端 Vue/TS：`npm run build` 通过（类型检查 + 构建无错误）。
  - **禁止绕过或降低质量门禁**：不得跳过/篡改 Hook 配置、不得弱化断言或注释失败测试来让门禁"通过"。
- 后端 JaCoCo 统计的**行覆盖率**与**分支覆盖率**必须 **≥85%**。
- 覆盖率不达标的提交/PR 不允许合并。
- 新增或修改的类/方法必须使覆盖率不低于合并前水平。
- 覆盖率校验脚本：`.qoder/skills/check-coverage.sh`（后端 JaCoCo + 前端 `npm run build` 二合一）。
- 算分引擎专项测试脚本：`.qoder/skills/test-performance.sh`。
- 覆盖率自愈子代理：`.qoder/agents/coverage-fixer.md`（`coverage-fixer`），当覆盖率不达标时自动分析 JaCoCo 报告并补充测试用例。

## 4. 业务领域规则（源自 `docs/TDD.docx` 技术设计文档）

> 注意区分：本节的"TDD"指 `docs/TDD.docx`（Technical Design Document，技术设计文档，现行基线 **V1.4，2026-10-11**），与第 2 节"TDD 业务铁律"（Test-Driven Development）是两个不同含义的同一缩写，不要混淆。任何与下列规则冲突的代码改动，先查阅 `docs/TDD.docx` 原文和 `docs/api-spec.md` 再动手。（注：`docs/TDD.pdf` 与《SRM 后端数据库表设计》md/docx 已于 2026-10-11 从仓库删除，勿再引用；数据库现状以 `srm-backend/src/main/resources/schema.sql` 为唯一事实源。）

### 4.1 角色与 RBAC 权限矩阵

- 三大角色：`ADMIN`（系统管理员）、`STAFF`（业务员）、`AUDITOR`（审核员）。ADMIN 原则上**不参与**供应商业务数据的新增/修改/审批，管理权限与业务权限严格分离。
- 前后端必须**同时**执行 RBAC 校验，不能只依赖前端隐藏按钮：
  - 供应商信息新增/编辑：仅 `STAFF`，且仅限【草稿】/【退回】状态；【待审核】及以后状态后端强制拒绝写操作。
  - 资质审核（通过/驳回）、绩效复核、停用/淘汰最终决策：仅 `AUDITOR`。
  - 停用/恢复申请：`STAFF` 可提出申请，`AUDITOR` 才能做最终决策。
  - 用户/角色管理：仅 `ADMIN`。
  - 删除供应商草稿：仅本人 `STAFF`，且仅限本人草稿/待提交状态；正式流程记录禁止物理删除，只能逻辑删除。

### 4.2 供应商生命周期状态机与并发控制

- 主流程：草稿(DRAFT) → 提交 → 待审核(PENDING_REVIEW) → AUDITOR 审核 → 通过进入正常合作(NORMAL)；驳回则退回 STAFF 修改(RETURNED)后重新提交；NORMAL/SUSPENDED 均可进入淘汰(ELIMINATED)。
- 供应商记录处于【待审核 / PENDING_REVIEW】状态时必须加**乐观锁**（`@Version`），拒绝 STAFF 对同一记录的任何修改/删除请求，防止 AUDITOR 审批过程中的并发冲突。
- 状态变更只能按状态机合法迁移，禁止跳状态；所有状态变更必须写入状态历史与 AuditLog（操作者、时间、操作类型、旧状态、新状态、结果、审核意见）。
- 停用/恢复走独立的 `LifecycleRequest` 两步流程（STAFF 申请 + AUDITOR 终审），不允许接口直接 PATCH 供应商状态字段。
- 同一供应商同一考核周期内，只允许存在一张【草稿】或【待复核】的绩效评价单；上一张未经 AUDITOR 终审归档前，系统必须全局锁定"发起评价"入口，防止多线程算分冲突。
- 验收标准见 `docs/acceptance-criteria.md` §1。

### 4.3 绩效评价：前后端数据隔离与防篡改（核心铁律）

- **前端只能提交客观事实，绝不允许提交或覆盖分数/评级**。前端输入结构为 `PerformanceFactRecord`，仅包含：
  - 交货维度：`total_batches`、`delayed_batches`、`avg_delay_days`
  - 质量维度：`qc_total_batches`、`qc_failed_batches`、`major_accidents`
  - 价格与服务维度：`price_deviation_rate`、`complaint_overtime_count`
  - 合规维度：由后端自动读取 `Qualification.expiry_date`，前端不传
- 综合得分、Q/D/P/S/C 五维得分、A/B/C/D 评级**必须在后端硬编码计算**，任何 Controller/Service 不得接受前端传入的分数或评级字段并直接持久化；这是接口设计与代码评审的强制检查项（`docs/api-spec.md` 第 4 节的 DTO 定义里物理上不存在分数字段）。
- 五维得分公式（硬编码，不得配置化成可被前端影响的参数），详见 `docs/api-spec.md` 第 4.1 节；`qc_total_batches = 0` 时质量维度记 100 分并打"当期无业务发生-免考"标签。
- 综合得分 `= Q*30% + D*25% + P*20% + S*15% + C*10%`；评级：`A` ≥90；`80 ≤ B < 90`；`70 ≤ C < 80`；`D` <70。
- 评级与状态机的联动：A/B/C 级不自动变更供应商状态（C 级仅打风险预警）；D 级只打风险预警，不自动 `SUSPENDED`——必须由人工发起停用申请并经 AUDITOR 审批（见 4.2 的 `LifecycleRequest`）才能落地。
- 评分提交后立即进入【待复核】且数据不可逆锁死；经 AUDITOR 复核通过后写入 `PerformanceEvaluation` 表形成永久历史，不得覆盖旧评价；复核驳回则 `RETURNED`，原 STAFF 可改后重提（沿用同一条记录）。
- 验收标准见 `docs/acceptance-criteria.md` §2。

### 4.4 适配器模式：`PerformanceMetricProvider`

- P6（维度录入）与 P7（加权计算）统一抽象为 `PerformanceMetricProvider` 接口，当前 MVP 提供：
  - `ManualFactInputAdapter`：生产环境默认实现，STAFF 页面录入客观事实，内置评分范围与必填字段强校验。
  - `MockDataMetricAdapter`：DEMO/测试模式专用，按预设规则或随机种子自动生成履约流水数据（如总批次、逾期批次等），直接注入算分引擎，无需依赖 ERP/WMS。
- 二期演进预留 `ExternalSystemMetricAdapter`，用于零代码切换到 ERP/WMS 实时采集，新增该适配器不应要求修改 P6/P7 之外的既有代码（接口对扩展开放、对修改封闭）。
- 算分本身（P7）由唯一的 `PerformanceScoreCalculator` 完成，不是接口的一部分，任何 adapter 都只负责"取事实数据"再交给它。
- Controller 按请求 `mode` 字段从 `Map<String, PerformanceMetricProvider>`（Spring 按 bean name 注入）取 provider，禁止在 Controller/Service 中直接 if-else 判断"是否为 mock 模式"。

### 4.5 审计与不可变性

- 关键业务操作（状态变更、审核决策、绩效复核、退回重填）必须全量写入 `AuditLog`，记录操作者、时间、操作类型、旧/新状态、结果与意见。
- 正式流程记录（已提交审核、已归档的绩效评价等）禁止物理删除，只允许逻辑删除且仅限规则允许的草稿。
- 验收标准见 `docs/acceptance-criteria.md` §3。

## 5. 其他约束

- 运行后端：`cd srm-backend && ./mvnw spring-boot:run`
- 构建后端：`cd srm-backend && ./mvnw clean package`
- 全量测试后端：`cd srm-backend && ./mvnw test`
- 运行前端：`cd srm-frontend && npm run dev`
- 构建前端：`cd srm-frontend && npm run build`
- 详细业务设计原文：`docs/TDD.docx`（V1.4）；需求补充：`docs/供应商管理系统需求规格说明书-补充非MVP V1.6_20261011待评审.docx`；接口契约：`docs/api-spec.md`
