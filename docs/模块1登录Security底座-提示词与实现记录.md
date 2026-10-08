# 模块 1：登录及 Security 底座 —— 提示词与生成过程记录

> 本文档导出本次「@backend-dev 实现 SRM 后端模块 1」任务的完整过程：原始提示词 → TDD 落地流程 → 交付文件清单 → 验证结果。
> 生成时间：2026-10-08 ｜ 分支：MVP-W3

---

## 一、原始任务提示词

```
@backend-dev 请实现 SRM 后端【模块 1：登录及 Security 底座】功能，代码结构与方法签名必须严格遵循
docs/backend-interface-design.md（第 8、9、10 节）与《SRM 底座功能契约文档》。

【落地范围】
1. Common 基础组件 (com.srm.core.common)：
   - 按照 backend-interface-design.md 第 9 节实现 ApiResponse<T>、ErrorCode（包含 0, 40001, 40101,
     40102, 40301, 40302, 40401, 40901, 40902, 50001）、BusinessException 与 GlobalExceptionHandler。
2. Security 组件 (com.srm.core.security)：
   - 按照第 8 节实现 JwtTokenProvider、JwtAuthenticationFilter（校验失败仅放行，不抛异常）、
     RestAuthenticationEntryPoint（返回 40101）、RestAccessDeniedHandler（返回 40301）、
     CustomUserDetailsService 与 UserPrincipal。
3. 登录接口与种子播种 (com.srm.core.controller & config)：
   - 实现 POST /api/v1/auth/login，密码/账号校验失败统一返回 code: 40102。
   - 实现 DemoUserSeeder（第 10 节），受 srm.seed-demo-users 开关控制，仅当 users 表为空时自动播种
     admin/staff01/auditor01。
4. 单元测试：
   - 编写 JwtTokenProviderTest、JwtAuthenticationFilterTest、AuthControllerTest 等。
   - 确保 ./mvnw test 全部通过，且行/分支覆盖率 > 85%。
```

### 设计依据（源文档）
- `docs/backend-interface-design.md` 第 8 节（Security 组件接口）、第 9 节（Common 组件接口）、第 10 节（Config 组件）
- 《SRM 底座功能契约文档》（docx，经 textutil 转 txt 阅读）
- `docs/api-spec.md` 第 1 节（Auth）与第 8 节（错误码表）
- `.qoder/rules.md`（TDD 业务铁律、包结构规范、覆盖率门禁）

---

## 二、TDD 生成过程（Red → Green → Refactor）

### 2.1 Red：先写测试（编译失败状态）
按 `.qoder/rules.md` 第 2 节铁律「先写测试，再写实现」，第一批创建 15 个测试类：

| 测试类 | 覆盖目标 |
|---|---|
| `ApiResponseTest` | success/error 静态工厂、全部错误码值 |
| `ErrorCodeTest` | 10 个契约码值 |
| `BusinessExceptionTest` | code/message 透传、RuntimeException 语义 |
| `GlobalExceptionHandlerTest` | 5 个 handler 分支（含 AccessDenied 兜底） |
| `JwtTokenProviderTest` | 签发、过期/篡改/异密钥/垃圾 token 拒绝、claims 解析 |
| `JwtAuthenticationFilterTest` | 无 header/非 Bearer/无效 token 放行、有效 token 注入 SecurityContext |
| `RestAuthenticationEntryPointTest` | HTTP 401 + 40101 响应体 |
| `RestAccessDeniedHandlerTest` | HTTP 403 + 40301 响应体 |
| `CustomUserDetailsServiceTest` | 找到/找不到/停用三种路径 |
| `UserPrincipalTest` | authorities 前缀、账号标志位 |
| `AuthServiceImplTest` | 登录成功、密码错/停用/账号不存在统一 40102、失败不签发 token |
| `AuthControllerTest` | standalone MockMvc + SNAKE_CASE 序列化契约 |
| `DemoUserSeederTest` | 开关关/表非空/表空播种/防御分支 |
| `TestControllerTest` | 联通接口 |
| `AuthSecurityIntegrationTest` | @SpringBootTest + H2 端到端（播种→登录→40101/40301） |

### 2.2 Green：最小实现（26 个文件）

**Common 组件**（`com.srm.core.common`）
- `ApiResponse<T>`：`code/message/data` + `success(T)` / `error(ErrorCode, String)`
- `ErrorCode`：SUCCESS(0) … INTERNAL_ERROR(50001)，共 10 个枚举值
- `BusinessException`：`(ErrorCode code, String message)` + `getCode()`
- `PageResult<T>`：`list/total/page/pageSize`
- `GlobalExceptionHandler`：`@RestControllerAdvice`，4 个契约 handler + `AccessDeniedException` 兜底（→40301，防 @PreAuthorize 拒绝被 catch-all 误报 50001）
- `common/enums/Role`：ADMIN/STAFF/AUDITOR

**Security 组件**（`com.srm.core.security`）
- `JwtTokenProvider`：jjwt 0.12.6，HS256；`validateToken` 过期/签名不合法返回 false 不抛异常
- `JwtAuthenticationFilter`：`OncePerRequestFilter`，校验失败仅放行
- `RestAuthenticationEntryPoint`：HTTP 401 + code 40101「未登录或登录已过期」
- `RestAccessDeniedHandler`：HTTP 403 + code 40301「无权限」
- `UserPrincipal`：`getId()/getRole()` + UserDetails 标准方法，authorities = `ROLE_<role>`
- `CustomUserDetailsService`：找不到/enabled=false 均抛 `UsernameNotFoundException`

**登录链路**（`controller/service/dto/entity/mapper`）
- `User` 实体（`sys_user`）、`UserMapper extends BaseMapper<User>`
- `LoginRequest`（@NotBlank 校验）、`LoginResponse`（token/userId/username/realName/role）
- `AuthService` / `AuthServiceImpl`：`AuthenticationManager.authenticate` → 签发 token；任何 `AuthenticationException` 统一转 `BusinessException(INVALID_CREDENTIALS, "用户名或密码错误")`
- `AuthController`：`POST /api/v1/auth/login`（构造器注入）

**Config 组件**（`com.srm.core.config`）
- `SecurityConfig`：无状态 JWT + CSRF/Session 禁用 + `@EnableMethodSecurity` + permitAll(login、/api/test/**)
- `MybatisPlusConfig`：`OptimisticLockerInnerInterceptor`
- `DemoUserSeeder`：`ApplicationRunner`，`srm.seed-demo-users=true` 且 `sys_user` 为空时播种 admin/staff01/auditor01（BCrypt 加密）

**工程配置**
- `pom.xml`：新增 `spring-boot-starter-security`、`jjwt-api/impl/jackson 0.12.6`
- `application.yml`：`srm.jwt.secret` / `srm.jwt.expiration-ms`
- 启动类：`@MapperScan("com.srm.core.mapper")`
- 测试侧：`schema-h2.sql`（H2 兼容 schema，规避主 schema.sql 的 MySQL 生成列语法）

### 2.3 修复过程（Refactor/缺陷修复）
1. MockMvc 无 `isNull()` 匹配器 → 改用 hamcrest `nullValue()`
2. MyBatis-Plus `BaseMapper.insert` 重载歧义 → 改用 `ArgumentCaptor<User>`
3. 集成测试 user_id 断言受 @BeforeEach 清表重插影响 → 改为信任 seeder 播种顺序、不再清表
4. 单元测试中 `@Value` 字段在手动 new 对象时不生效 → 删除默认值断言（由集成测试覆盖）

---

## 三、交付文件清单

### 主代码（22 个 Java 文件 + 3 个配置文件）
```
srm-backend/src/main/java/com/srm/core/
├── SrmBackendApplication.java          （+@MapperScan）
├── common/
│   ├── ApiResponse.java
│   ├── PageResult.java
│   ├── ErrorCode.java
│   ├── BusinessException.java
│   ├── GlobalExceptionHandler.java
│   └── enums/Role.java
├── security/
│   ├── JwtTokenProvider.java
│   ├── JwtAuthenticationFilter.java
│   ├── RestAuthenticationEntryPoint.java
│   ├── RestAccessDeniedHandler.java
│   ├── UserPrincipal.java
│   └── CustomUserDetailsService.java
├── entity/User.java
├── mapper/UserMapper.java
├── dto/auth/LoginRequest.java
├── dto/auth/LoginResponse.java
├── service/AuthService.java
├── service/impl/AuthServiceImpl.java
├── controller/AuthController.java
└── config/
    ├── SecurityConfig.java
    ├── MybatisPlusConfig.java
    └── DemoUserSeeder.java
```

### 测试代码（15 个测试类 + 1 个 H2 schema）
```
srm-backend/src/test/java/com/srm/core/
├── AuthSecurityIntegrationTest.java
├── common/  ApiResponseTest / ErrorCodeTest / BusinessExceptionTest / GlobalExceptionHandlerTest
├── security/ JwtTokenProviderTest / JwtAuthenticationFilterTest / RestAuthenticationEntryPointTest
│             / RestAccessDeniedHandlerTest / CustomUserDetailsServiceTest / UserPrincipalTest
├── service/impl/ AuthServiceImplTest
├── controller/  AuthControllerTest / TestControllerTest
└── config/ DemoUserSeederTest
srm-backend/src/test/resources/schema-h2.sql
```

---

## 四、验证结果

### 4.1 单元测试
```
Tests run: 71, Failures: 0, Errors: 0, Skipped: 0
```
- AuthSecurityIntegrationTest 14 个（H2 端到端）、JwtTokenProviderTest 10 个、AuthServiceImplTest 7 个等

### 4.2 覆盖率（JaCoCo，门禁 > 85%）
| 指标 | 结果 |
|---|---|
| 行覆盖率 | **99.01%**（200/202）✅ |
| 分支覆盖率 | **100.00%**（16/16）✅ |

- 唯一未覆盖：`SrmBackendApplication.main()` 启动入口（惯例不测）
- HTML 报告：`srm-backend/target/site/jacoco/index.html`

### 4.3 实机验证（MySQL vendor_db + curl）
```json
POST /api/v1/auth/login  {"username":"staff01","password":"Staff@123"}
→ {"code":0,"message":"成功","data":{"token":"eyJ...","user_id":2,"username":"staff01","real_name":"张三","role":"STAFF"}}

密码错误 → {"code":40102,"message":"用户名或密码错误","data":null}
无 token 访问业务接口 → HTTP 401 + {"code":40101,"message":"未登录或登录已过期","data":null}
```

---

## 五、关键设计决策

1. **登录失败统一 40102**：账号不存在/密码错误/停用一律走 `AuthenticationException` 捕获转换，不区分原因（api-spec.md 第 1 节安全要求）
2. **JwtAuthenticationFilter 不抛异常**：校验失败直接放行，40101 统一由 `RestAuthenticationEntryPoint` 响应，避免鉴权逻辑与响应格式散落两处
3. **AccessDeniedException 兜底**：`@PreAuthorize` 方法级拒绝发生在 DispatcherServlet 内，需 GlobalExceptionHandler 兜底 40301，否则会被 catch-all 误报 50001（为模块 2 RBAC 铺路）
4. **HTTP 状态码语义**：40101→HTTP 401、40301→HTTP 403，与响应体 code 双通道对齐
5. **测试 H2 隔离**：`schema-h2.sql` 仅建 `sys_user`，规避主 schema.sql 的 MySQL 生成列语法在 H2 上的兼容问题
6. **JWT 密钥环境变量化**：`srm.jwt.secret` 默认值仅用于本地开发，生产通过 `JWT_SECRET` 覆盖

---

## 六、会话时间线（本地存储元数据提取）

> 来源：Qoder CN 本地数据库 `~/.qoder-cn/shared_client/cache/db/local.db`（chat_session / chat_message 表）。
> 注意：消息正文为加密存储，以下为可提取的元数据 + 会话内已知内容还原。

- 会话 ID：`9f14bf64-a66e-4f19-a5b9-b1d7d5be99ab`
- 会话标题：SRM 后端模块 1
- 时间跨度：2026-10-08 11:08:30 → 14:08:01（约 3 小时）
- 消息规模：用户提问 5 轮 / 助手回复 69 条 / 工具调用 109 次

| # | 用户提问（时间） | 处理结果 |
|---|---|---|
| 1 | 11:08 实现模块 1（登录及 Security 底座） | 71 测试全绿、行 99.01% / 分支 100% |
| 2 | 11:2x 运行项目，用 Postman 测试 | 后端 8080 启动、播种 3 账号、curl 验证通过 |
| 3 | 11:3x 生成测试覆盖率 | JaCoCo 报告 + HTML 打开 |
| 4 | 13:2x 把提示词到生成过程导出文件 | 生成本文档 |
| 5 | 13:24 Qoder CN 没找到会话？ | 核查插件/数据库，确认无内置导出功能（见下） |

### 关于 Qoder CN 会话导出的核查结论
1. **插件无内置导出**：反编译核查 IntelliJ 插件 `qoder-cn-jetbrains`（`instrumented-qoder-core-2026.924.1.jar`，3927 个类）中无「导出/Export」相关文案与动作类；会话 UI 仅有 `ChatHistoryPanel`（历史面板，用于打开旧会话），无导出入口。
2. **本地存储加密**：`local.db` 中 `chat_message.content`、`chat_record.question/answer` 均为密文，无法离线提取对话原文。
3. **替代方案**：本文件（提示词 + 生成过程）即当前可获得的完整导出；如需逐字对话原文，可在 Qoder 面板历史中打开会话后手动复制粘贴。
