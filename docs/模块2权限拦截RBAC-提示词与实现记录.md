# 模块 2：权限拦截（RBAC）—— 提示词与实现记录

> 本文档导出「实现 SRM 底座模块 2：权限拦截（RBAC）」任务的完整过程：原始提示词 → 契约收集 → 核心设计决策 → TDD 落地 → 修复链 → 验证结果。
> 生成时间：2026-10-08 ｜ 分支：MVP-W3 ｜ 会话：9f14bf64（提示词全文取自明文转录）

---

## 一、原始任务提示词（全文）

```
请继续实现 SRM 底座功能的【模块 2：权限拦截（RBAC）】。

【契约要求与规范对齐】
1. 捕获 40301 无权限：
   - 实现/更新 `RestAccessDeniedHandler`，当角色不匹配时，返回标准的
     `ApiResponse.error(ErrorCode.FORBIDDEN, "无权限")`（状态码 40301）。
2. Security 配置更新：
   - 在 SecurityConfig 中注册 `RestAccessDeniedHandler` 与 `RestAuthenticationEntryPoint`。
   - 确保开启方法级安全注解 `@EnableMethodSecurity`。
3. Controller 层权限注解：
   - 检查并配置 Controller 方法的 `@PreAuthorize`：
     * STAFF 权限：`@PreAuthorize("hasRole('STAFF')")`
     * AUDITOR 权限：`@PreAuthorize("hasRole('AUDITOR')")`
     * ADMIN 权限：`@PreAuthorize("hasRole('ADMIN')")`
4. 业务铁律（Service 层双重校验）：
   - 遵循前后端双重校验原则：Controller 的 @PreAuthorize 做粗粒度拦截，Service 层必须对
     "状态/本人"进行二次校验（例如：供应商仅在【草稿/退回】状态允许 STAFF 编辑；
     【待审核】及以后状态强行拒绝）。

修改完成后，请运行 `./mvnw test` 或执行门禁脚本验证测试是否通过。
```

### 设计依据（源文档）
- `docs/backend-interface-design.md` §5（L269-358 Controller @PreAuthorize 权限矩阵）、§8（L455-465：40301 由 RestAccessDeniedHandler）
- 《SRM 底座功能契约文档》docx → `textutil` 转 `/tmp/contract.txt` 阅读（L36-152 RBAC 权限矩阵；**L109 关键句**："40101/40301 由 Security 层处理，不经过 GlobalExceptionHandler"）

---

## 二、现状核实（实现前）

| 契约要求 | 模块 1 遗留状态 | 结论 |
|---|---|---|
| ① RestAccessDeniedHandler 403+40301 | 已实现且返回格式正确 | ✅ 无需改 |
| ② SecurityConfig 双 handler + @EnableMethodSecurity | 已注册、已开启 | ✅ 无需改 |
| ③ Controller @PreAuthorize 矩阵 | 线上无业务 Controller（模块 5+ 才落地） | ⚠️ 以测试控制器验证机制 |
| ④ Service 层状态/本人二次校验 | 无组件 | ❌ 本次核心新建 |
| （冲突项）advice 里有 AccessDenied handler | 模块 1 曾加过兜底 → **违反契约 L109** | ❌ 必须删除并改为重抛 |

---

## 三、核心设计决策：40301 唯一出口纠偏

**问题**：直接删除 `GlobalExceptionHandler` 的 AccessDenied handler 后，异常会被 `@ExceptionHandler(Exception.class)` 兜底吞成 **50001**，仍然到不了 Security 层。

**方案**（三层防护锁定）：
1. `handleUnknown` 签名改 `throws Exception`，方法体开头：
   ```java
   if (ex instanceof AccessDeniedException) { throw ex; }  // 原样重抛
   ```
2. 异常经 DispatcherServlet 冒泡至 `ExceptionTranslationFilter` → `RestAccessDeniedHandler` 产出 403+40301
3. 测试锁定三层：反射断言 advice 不得有 AccessDeniedException 参数方法（单测）→ `isSameAs(ex)` 重抛断言（单测）→ 角色矩阵端到端 403 断言（集成测试）

**业务铁律机制化**：新建两个可复用组件，供模块 5+ 业务 Service 直接调用——

| 组件 | 方法 | 语义 | 错误 |
|---|---|---|---|
| `CurrentUserProvider` | `current()` | Optional 包装 SecurityContext 里的 UserPrincipal | — |
| | `require()` | 未登录 | 40101 |
| `RoleGuard` | `requireRole(Role...)` | 角色兜底（防 Controller 漏标 @PreAuthorize） | 40301 无权限 |
| | `requireStatusAllowed(boolean, msg)` | 状态二次校验（如仅【草稿/退回】可编辑） | 40302 + 动态文案 |
| | `requireSelf(Long ownerId)` | 本人校验 | 40302 仅限本人操作 |

---

## 四、TDD 生成过程（Red → Green）

### 4.1 Red：测试先行（新增 25 例）
- `CurrentUserProviderTest`（5 例）：有/无 Authentication、principal 类型不符、require 未登录抛 40101
- `RoleGuardTest`（9 例）：三方法各自 通过/拒绝/未登录 路径，断言错误码与文案
- `GlobalExceptionHandlerTest` 改造：删除原 AccessDenied→40301 用例，替换为**反射契约测试** `accessDeniedIsHandledBySecurityLayerNotByAdvice`（遍历 advice 全部方法参数类型，禁止出现 AccessDeniedException）
- `AuthSecurityIntegrationTest` +11 例：
  - 角色矩阵：staffToken 访问 auditor-only → 403+40301；adminToken 访问 staff-only → 40301（ADMIN 不参与业务）；匿名 → 40101 而非 40301；响应体 Content-Type/结构来自 Security 层 handler
  - 端到端二次校验：`RbacDemoController.business-write`（故意不加 @PreAuthorize）验证 RoleGuard 兜底；`status-locked` → 40302；`owner/2`（staff01 本人）通过、`owner/999` → 40302

### 4.2 Green：实现（2 新建 + 1 改造）
```
srm-backend/src/main/java/com/srm/core/
├── security/CurrentUserProvider.java   （新建）
├── security/RoleGuard.java             （新建，构造器注入 CurrentUserProvider）
└── common/GlobalExceptionHandler.java  （删 AccessDenied handler；handleUnknown 改签名重抛）
```

### 4.3 修复链（按发生顺序）
1. 测试方法名 `40301Response...` 数字开头 → Java 非法标识符编译错 → 重命名 `deniedResponseComesFromSecurityLayerHandler`
2. `handleUnknown` 加 `throws Exception` → 既有测试 unreported exception → 测试方法补 `throws Exception`
3. `@TestConfiguration` 嵌套 `@RestController` 会自动注册为 Bean，另加的 `@Bean RbacDemoController` 工厂方法导致 **Ambiguous mapping**、上下文加载失败 25 ERROR → 删除 @Bean 方法，保留构造器注入
4. 陈旧 `target/surefire-reports` 历史报告干扰结果判读 → `rm -rf` 后重跑取权威数据
5. `./mvnw test | grep ...; echo $?` 拿到的是 grep 退出码，BUILD FAILURE 被掩盖 → 改为显式 grep 汇总行判断

---

## 五、验证结果

### 5.1 全量测试
```
Tests run: 96, Failures: 0, Errors: 0, Skipped: 0   （模块 1 的 71 + 本模块 25）
BUILD SUCCESS
```

### 5.2 覆盖率（JaCoCo，门禁 > 85%）
| 指标 | 结果 |
|---|---|
| 行覆盖率 | **99.11%** ✅ |
| 分支覆盖率 | **100.00%** ✅ |
| check-coverage.sh | ✅ Quality Gate PASSED |

### 5.3 关键行为实证（集成测试）
- `@PreAuthorize` 拒绝 → **HTTP 403** + `{"code":40301,"message":"无权限"}`，出口为 RestAccessDeniedHandler
- 未登录 → HTTP 401 + 40101（不被误判为 40301）
- 无 @PreAuthorize 的业务口 → RoleGuard.requireRole 兜底 40301
- 状态锁定 → 40302 动态文案；非本人 → 40302 "仅限本人操作"

---

## 六、遗留说明与衔接

1. **线上暂不可手动测 403**：RBAC 演示控制器只在测试上下文；业务 Controller（模块 5+）落地后 Postman 可直接触发
2. **衔接模式**（后续每个业务模块两件事）：Controller 按 backend-interface-design.md §5 矩阵标注 `@PreAuthorize`；Service 写操作调用 `roleGuard.requireRole(...)/requireStatusAllowed(...)/requireSelf(...)`，并记得调 `AuditLogService.record(...)`（模块 4 已就绪）
3. 演示账号契约 ID：admin=1、staff01=2、auditor01=3（测试断言依赖）
