package com.srm.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.srm.core.common.enums.Role;
import com.srm.core.entity.User;
import com.srm.core.mapper.UserMapper;
import com.srm.core.security.JwtTokenProvider;
import com.srm.core.security.RoleGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 模块 1+2 端到端集成测试（H2 内存库）：
 * DemoUserSeeder 播种 → POST /api/v1/auth/login → JWT 鉴权链路（40101/40301 响应体与 HTTP 状态）
 * → RBAC 角色矩阵（@PreAuthorize 粗粒度拦截）+ Service 层 RoleGuard 二次校验。
 */
@SpringBootTest(properties = "srm.seed-demo-users=true")
@AutoConfigureMockMvc
class AuthSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // 表结构由 schema-h2.sql 建好，演示账号由 DemoUserSeeder 在上下文启动时播种
        // （admin=1 / staff01=2 / auditor01=3），测试不再清表，以端到端验证 seeder 行为。
    }

    private void insertUser(String username, String rawPassword, String realName, String role, boolean enabled) {
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRealName(realName);
        user.setRole(role);
        user.setEnabled(enabled);
        userMapper.insert(user);
    }

    private String loginAndGetToken(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data").get("token").asText();
    }

    @Test
    void seederCreatesDemoAccountsOnEmptyTable() {
        assertThat(userMapper.selectCount(null)).isGreaterThanOrEqualTo(3);
        User staff01 = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                        .eq(User::getUsername, "staff01"));
        assertThat(staff01).isNotNull();
        assertThat(staff01.getRole()).isEqualTo("STAFF");
        assertThat(staff01.getRealName()).isEqualTo("张三");
        assertThat(staff01.getEnabled()).isTrue();
        assertThat(passwordEncoder.matches("Staff@123", staff01.getPasswordHash())).isTrue();

        User admin = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                        .eq(User::getUsername, "admin"));
        assertThat(admin).isNotNull();
        assertThat(admin.getRole()).isEqualTo("ADMIN");

        User auditor01 = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                        .eq(User::getUsername, "auditor01"));
        assertThat(auditor01).isNotNull();
        assertThat(auditor01.getRole()).isEqualTo("AUDITOR");
    }

    @Test
    void loginSuccessReturnsContractFieldsInSnakeCase() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"staff01\",\"password\":\"Staff@123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("成功"))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.user_id").value(2))
                .andExpect(jsonPath("$.data.username").value("staff01"))
                .andExpect(jsonPath("$.data.real_name").value("张三"))
                .andExpect(jsonPath("$.data.role").value("STAFF"))
                .andExpect(jsonPath("$.data.password_hash").doesNotExist());
    }

    @Test
    void allThreeDemoAccountsCanLogin() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"Admin@123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.role").value("ADMIN"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"auditor01\",\"password\":\"Auditor@123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.role").value("AUDITOR"));
    }

    @Test
    void wrongPasswordReturns40102() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"staff01\",\"password\":\"Wrong@123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40102))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void unknownUsernameReturns40102() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ghost\",\"password\":\"Whatever@1\"}"))
                .andExpect(jsonPath("$.code").value(40102));
    }

    @Test
    void disabledAccountReturns40102() throws Exception {
        insertUser("disabled01", "Disabled@123", "停用账号", "STAFF", false);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"disabled01\",\"password\":\"Disabled@123\"}"))
                .andExpect(jsonPath("$.code").value(40102));
    }

    @Test
    void blankRequestReturns40001() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"\"}"))
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void protectedEndpointWithoutTokenReturns40101WithHttp401() throws Exception {
        mockMvc.perform(get("/api/v1/protected-ping"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101))
                .andExpect(jsonPath("$.message").value("未登录或登录已过期"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void protectedEndpointWithInvalidTokenReturns40101() throws Exception {
        mockMvc.perform(get("/api/v1/protected-ping")
                        .header("Authorization", "Bearer invalid.token.value"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    void protectedEndpointWithValidTokenReturns200() throws Exception {
        String token = loginAndGetToken("staff01", "Staff@123");

        mockMvc.perform(get("/api/v1/protected-ping")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void wrongRoleAccessReturns40301WithHttp403() throws Exception {
        String staffToken = loginAndGetToken("staff01", "Staff@123");

        mockMvc.perform(get("/api/v1/admin-only")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301))
                .andExpect(jsonPath("$.message").value("无权限"));
    }

    @Test
    void adminRoleAccessesAdminOnlyEndpoint() throws Exception {
        String adminToken = loginAndGetToken("admin", "Admin@123");

        mockMvc.perform(get("/api/v1/admin-only")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testPingEndpointIsPermittedWithoutToken() throws Exception {
        mockMvc.perform(get("/api/test/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    // ==================== 模块 2：RBAC 角色矩阵（@PreAuthorize 粗粒度拦截） ====================

    @Test
    void staffRoleAccessesStaffOnlyEndpoint() throws Exception {
        String staffToken = loginAndGetToken("staff01", "Staff@123");

        mockMvc.perform(get("/api/v1/rbac-demo/staff-only")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void staffTokenAccessesAuditorOnlyReturns40301WithHttp403() throws Exception {
        String staffToken = loginAndGetToken("staff01", "Staff@123");

        mockMvc.perform(get("/api/v1/rbac-demo/auditor-only")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301))
                .andExpect(jsonPath("$.message").value("无权限"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void auditorTokenAccessesStaffOnlyReturns40301() throws Exception {
        String auditorToken = loginAndGetToken("auditor01", "Auditor@123");

        mockMvc.perform(get("/api/v1/rbac-demo/staff-only")
                        .header("Authorization", "Bearer " + auditorToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    void adminTokenAccessesStaffOnlyReturns40301() throws Exception {
        // 业务铁律：ADMIN 不参与供应商业务操作，管理权限与业务权限严格分离
        String adminToken = loginAndGetToken("admin", "Admin@123");

        mockMvc.perform(get("/api/v1/rbac-demo/staff-only")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    void anonymousAccessesRbacEndpointReturns40101Not40301() throws Exception {
        // 未登录访问受角色保护接口 → 40101（身份问题优先于权限问题）
        mockMvc.perform(get("/api/v1/rbac-demo/staff-only"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    void deniedResponseComesFromSecurityLayerHandler() throws Exception {
        // 契约：40301 由 RestAccessDeniedHandler 产生，Content-Type 为 JSON，body 为统一 ApiResponse 格式
        String staffToken = loginAndGetToken("staff01", "Staff@123");

        mockMvc.perform(get("/api/v1/rbac-demo/auditor-only")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301))
                .andExpect(jsonPath("$.message").value("无权限"));
    }

    // ==================== 模块 2：Service 层 RoleGuard 二次校验（业务铁律） ====================

    @Test
    void serviceLayerRoleGuardPassesForStaffOnBusinessWrite() throws Exception {
        String staffToken = loginAndGetToken("staff01", "Staff@123");

        mockMvc.perform(get("/api/v1/rbac-demo/business-write")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void serviceLayerRoleGuardBlocksAdminEvenIfPreAuthorizeMissing() throws Exception {
        // 模拟 @PreAuthorize 漏配场景：无注解端点内 RoleGuard 兜底拒绝 ADMIN → 40301
        String adminToken = loginAndGetToken("admin", "Admin@123");

        mockMvc.perform(get("/api/v1/rbac-demo/business-write")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40301))
                .andExpect(jsonPath("$.message").value("无权限"));
    }

    @Test
    void serviceLayerStatusGuardRejectsPendingReviewEditWith40302() throws Exception {
        String staffToken = loginAndGetToken("staff01", "Staff@123");

        mockMvc.perform(get("/api/v1/rbac-demo/status-locked")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40302))
                .andExpect(jsonPath("$.message").value("【待审核】状态不允许编辑"));
    }

    @Test
    void serviceLayerSelfGuardRejectsAnotherOwnersDraftWith40302() throws Exception {
        String staffToken = loginAndGetToken("staff01", "Staff@123"); // staff01 user_id=2

        mockMvc.perform(get("/api/v1/rbac-demo/owner/999")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40302))
                .andExpect(jsonPath("$.message").value("仅限本人操作"));
    }

    @Test
    void serviceLayerSelfGuardPassesForOwner() throws Exception {
        String staffToken = loginAndGetToken("staff01", "Staff@123");

        mockMvc.perform(get("/api/v1/rbac-demo/owner/2")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void issuedTokenIsUsableAgainstProtectedEndpoint() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"auditor01\",\"password\":\"Auditor@123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(body).get("data");
        String token = data.get("token").asText();

        assertThat(jwtTokenProvider.validateToken(token)).isTrue();
        assertThat(jwtTokenProvider.getUsername(token)).isEqualTo("auditor01");
        assertThat(jwtTokenProvider.getRole(token).name()).isEqualTo("AUDITOR");
        assertThat(jwtTokenProvider.getUserId(token)).isEqualTo(data.get("user_id").asLong());

        mockMvc.perform(get("/api/v1/protected-ping")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @TestConfiguration
    static class TestProtectedEndpoints {

        @RestController
        static class ProtectedTestController {

            @GetMapping("/api/v1/protected-ping")
            public Map<String, Object> ping() {
                return Map.of("code", 0, "message", "ok");
            }

            @GetMapping("/api/v1/admin-only")
            @PreAuthorize("hasRole('ADMIN')")
            public Map<String, Object> adminOnly() {
                return Map.of("code", 0, "message", "admin only");
            }
        }

        /**
         * 模块 2 RBAC 专用测试控制器（@TestConfiguration 嵌套类自动注册为 Bean）：
         * 验证 @PreAuthorize 角色矩阵与 Service 层 RoleGuard 二次校验。
         * 业务模块（Supplier/Lifecycle/Performance）落地时按契约权限矩阵复用同样写法。
         */
        @RestController
        static class RbacDemoController {

            private final RoleGuard roleGuard;

            RbacDemoController(RoleGuard roleGuard) {
                this.roleGuard = roleGuard;
            }

            @GetMapping("/api/v1/rbac-demo/staff-only")
            @PreAuthorize("hasRole('STAFF')")
            public Map<String, Object> staffOnly() {
                return Map.of("code", 0, "message", "staff only");
            }

            @GetMapping("/api/v1/rbac-demo/auditor-only")
            @PreAuthorize("hasRole('AUDITOR')")
            public Map<String, Object> auditorOnly() {
                return Map.of("code", 0, "message", "auditor only");
            }

            /** 故意不加 @PreAuthorize，验证 RoleGuard 兜底（供应商写操作允许 STAFF/AUDITOR，拒绝 ADMIN） */
            @GetMapping("/api/v1/rbac-demo/business-write")
            public Map<String, Object> businessWrite() {
                roleGuard.requireRole(Role.STAFF, Role.AUDITOR);
                return Map.of("code", 0, "message", "business write ok");
            }

            /** 供应商【待审核】状态下 STAFF 编辑必须被 Service 层拒绝（40302） */
            @GetMapping("/api/v1/rbac-demo/status-locked")
            @PreAuthorize("hasRole('STAFF')")
            public Map<String, Object> statusLocked() {
                roleGuard.requireStatusAllowed(false, "【待审核】状态不允许编辑");
                return Map.of("code", 0, "message", "unexpected");
            }

            /** 删除草稿仅限本人（owner_id 匹配当前登录用户） */
            @GetMapping("/api/v1/rbac-demo/owner/{ownerId}")
            @PreAuthorize("hasRole('STAFF')")
            public Map<String, Object> owner(@PathVariable Long ownerId) {
                roleGuard.requireSelf(ownerId);
                return Map.of("code", 0, "message", "self ok");
            }
        }
    }
}
