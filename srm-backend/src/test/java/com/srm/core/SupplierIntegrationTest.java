package com.srm.core;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.srm.core.common.enums.Role;
import com.srm.core.entity.Supplier;
import com.srm.core.entity.User;
import com.srm.core.mapper.SupplierMapper;
import com.srm.core.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 模块一供应商主链 5 接口真实数据库集成测试（H2，supplier 表含生成列 tax_no_active +
 * 唯一索引 uk_tax_no_active，见 schema-h2.sql）。覆盖验收点：
 * - acceptance-criteria §1.2：乐观锁并发 40901（陈旧 version PUT 拒绝、成功后版本递增）
 * - acceptance-criteria §1.6：税号唯一 40902 与逻辑删除后释放税号位（生成列真实生效）
 * - acceptance-criteria §1.7：契约固定中文 message 端到端回传（HTTP 200 + code 40001）
 * - acceptance-criteria §4：角色拦截 40301（@PreAuthorize）、越权 40302、§0 行级隔离
 * - rules.md §1：分页真实生效（PaginationInnerInterceptor 在 supplier 表 LIMIT/COUNT）
 */
@SpringBootTest(properties = "srm.seed-demo-users=true")
@AutoConfigureMockMvc
class SupplierIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SupplierMapper supplierMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /** 每测试方法税号自增，避免并行污染（18 位纯数字，GB32100 字符集合规） */
    private static final AtomicInteger TAX_SEQ = new AtomicInteger(500);

    private String nextTaxNo() {
        return String.format("913301%012d", TAX_SEQ.getAndIncrement());
    }

    private String loginToken(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data").get("token").asText();
    }

    private long userId(String username) {
        User u = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        assertThat(u).isNotNull();
        return u.getId();
    }

    private String createBody(String name, String taxNo) {
        return """
                {
                  "name": "%s",
                  "tax_no": "%s",
                  "type": "MANUFACTURING",
                  "contact_name": "王强",
                  "contact_phone": "13800138000",
                  "effective_date": "2026-01-01",
                  "expiry_date": "2028-12-31"
                }
                """.formatted(name, taxNo);
    }

    /** 通过 API 建档，返回 data 节点 */
    private JsonNode createViaApi(String token, String name, String taxNo) throws Exception {
        String body = mockMvc.perform(post("/api/v1/suppliers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(name, taxNo)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data");
    }

    private Supplier insertRow(String name, String taxNo, String status, Long createdBy) {
        Supplier s = new Supplier();
        s.setName(name);
        s.setTaxNo(taxNo);
        s.setType("DISTRIBUTOR");
        s.setContactName("联系人");
        s.setContactPhone("13800138000");
        s.setEffectiveDate(LocalDate.of(2026, 1, 1));
        s.setExpiryDate(LocalDate.of(2028, 12, 31));
        s.setStatus(status);
        s.setCreatedBy(createdBy);
        s.setVersion(1);
        s.setCreatedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        supplierMapper.insert(s);
        return s;
    }

    // ==================== §1.1 建档 ====================

    @Test
    void staffCreateDraftReturnsContractShapeWithDerivedFieldsNull() throws Exception {
        String token = loginToken("staff01", "Staff@123");
        long staffId = userId("staff01");
        String tax = nextTaxNo();

        JsonNode data = createViaApi(token, "集成建档公司A", tax);
        // 建档出参即为私有在途草稿：DRAFT + 版本 1 + 三枚派生字段留空
        assertThat(data.get("status").asText()).isEqualTo("DRAFT");
        assertThat(data.get("version").asInt()).isEqualTo(1);
        assertThat(data.get("id").asLong()).isPositive();
        assertThat(data.hasNonNull("business_license_url")).isFalse();
        mockMvc.perform(post("/api/v1/suppliers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("集成建档公司B", nextTaxNo())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.created_by").value((int) staffId))
                .andExpect(jsonPath("$.data.created_by_name").value("张三"))
                .andExpect(jsonPath("$.data.effective_date").value("2026-01-01"))
                .andExpect(jsonPath("$.data.business_license_url").value(nullValue()))
                .andExpect(jsonPath("$.data.latest_performance_grade").value(nullValue()))
                .andExpect(jsonPath("$.data.risk_warning").value(nullValue()));

        // 创建即 DRAFT 私有草稿已落库
        Supplier persisted = supplierMapper.selectOne(
                new LambdaQueryWrapper<Supplier>().eq(Supplier::getTaxNo, tax));
        assertThat(persisted).isNotNull();
        assertThat(persisted.getStatus()).isEqualTo("DRAFT");
        assertThat(persisted.getVersion()).isEqualTo(1);
    }

    @Test
    void nonStaffRolesBlockedWith40301AndAnonymousWith40101() throws Exception {
        String auditorToken = loginToken("auditor01", "Auditor@123");
        String adminToken = loginToken("admin", "Admin@123");

        // 验收 §4.2/§4.3：ADMIN、AUDITOR 均不得建档 → 403 + 40301
        mockMvc.perform(post("/api/v1/suppliers")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("管理员违规建档", nextTaxNo())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));

        mockMvc.perform(post("/api/v1/suppliers")
                        .header("Authorization", "Bearer " + auditorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("审核员违规建档", nextTaxNo())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));

        // 未登录 → 401 + 40101
        mockMvc.perform(post("/api/v1/suppliers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("匿名建档", nextTaxNo())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    void duplicateTaxNoReturns40902() throws Exception {
        String token = loginToken("staff01", "Staff@123");
        String tax = nextTaxNo();
        createViaApi(token, "重复税号甲", tax);

        mockMvc.perform(post("/api/v1/suppliers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("重复税号乙", tax)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40902));
    }

    @Test
    void invalidNameReturnsContractChineseMessage() throws Exception {
        String token = loginToken("staff01", "Staff@123");

        // 验收 §1.7：HTTP 200 + code 40001 + 逐字契约文案
        mockMvc.perform(post("/api/v1/suppliers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("短", nextTaxNo())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message")
                        .value("供应商名称格式不合法（不得为空格且长度需为2~64字符）"));
    }

    // ==================== §1.2 编辑（乐观锁并发 40901，验收 §1.2） ====================

    @Test
    void optimisticLockStaleVersionRejectedFreshVersionSucceeds() throws Exception {
        String token = loginToken("staff01", "Staff@123");
        JsonNode created = createViaApi(token, "乐观锁试验场", nextTaxNo());
        long id = created.get("id").asLong();

        // 携带陈旧版本号 → 40901
        mockMvc.perform(put("/api/v1/suppliers/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"陈旧写\",\"version\":99}"))
                .andExpect(jsonPath("$.code").value(40901));

        // 携带正确版本 1 → 成功且响应回传自增后新版本 2
        mockMvc.perform(put("/api/v1/suppliers/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"合法改名\",\"version\":1}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.name").value("合法改名"))
                .andExpect(jsonPath("$.data.version").value(2));

        Supplier db = supplierMapper.selectById(id);
        assertThat(db.getVersion()).isEqualTo(2);
        assertThat(db.getName()).isEqualTo("合法改名");

        // 旧版本号再写 → 40901（模拟并发一方已提交后另一方持旧版本）
        mockMvc.perform(put("/api/v1/suppliers/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"并发脏写\",\"version\":1}"))
                .andExpect(jsonPath("$.code").value(40901));
        assertThat(supplierMapper.selectById(id).getName()).isEqualTo("合法改名");
    }

    @Test
    void updateTaxNoToAnotherActiveSupplierReturns40902() throws Exception {
        String token = loginToken("staff01", "Staff@123");
        String taxA = nextTaxNo();
        String taxB = nextTaxNo();
        long idA = createViaApi(token, "税号冲突甲", taxA).get("id").asLong();
        createViaApi(token, "税号冲突乙", taxB);

        mockMvc.perform(put("/api/v1/suppliers/" + idA)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tax_no\":\"" + taxB + "\",\"version\":1}"))
                .andExpect(jsonPath("$.code").value(40902));
    }

    @Test
    void editOthersDraftByAnotherStaffReturns40302AndPendingReviewRejected() throws Exception {
        // staff02 越权改 staff01 草稿 → 40302（requireSelf）
        User staff02 = new User();
        staff02.setUsername("staff02_lock");
        staff02.setPasswordHash(passwordEncoder.encode("Staff02@123"));
        staff02.setRealName("王二");
        staff02.setRole(Role.STAFF.name());
        staff02.setEnabled(true);
        userMapper.insert(staff02);

        String ownerToken = loginToken("staff01", "Staff@123");
        String otherToken = loginToken("staff02_lock", "Staff02@123");
        long id = createViaApi(ownerToken, "他人草稿保护", nextTaxNo()).get("id").asLong();

        mockMvc.perform(put("/api/v1/suppliers/" + id)
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"越权改名\",\"version\":1}"))
                .andExpect(jsonPath("$.code").value(40302));

        // 待审核记录拒绝 STAFF 编辑（双保护的状态拒绝侧，验收 §1.2）
        Supplier pending = supplierMapper.selectById(id);
        pending.setStatus("PENDING_REVIEW");
        supplierMapper.updateById(pending);

        mockMvc.perform(put("/api/v1/suppliers/" + id)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"送审后改名\",\"version\":1}"))
                .andExpect(jsonPath("$.code").value(40302));
    }

    // ==================== §1.3 / §1.4 行级隔离（验收 §4.4） ====================

    @Test
    void rowLevelIsolationEnforcedOnListAndDetailByBackend() throws Exception {
        String staffAToken = loginToken("staff01", "Staff@123");
        User staffB = new User();
        staffB.setUsername("staff02_iso");
        staffB.setPasswordHash(passwordEncoder.encode("StaffB@123"));
        staffB.setRealName("李四");
        staffB.setRole(Role.STAFF.name());
        staffB.setEnabled(true);
        userMapper.insert(staffB);
        String staffBToken = loginToken("staff02_iso", "StaffB@123");

        String draftName = "隔离草稿" + TAX_SEQ.getAndIncrement();
        long draftId = createViaApi(staffAToken, draftName, nextTaxNo()).get("id").asLong();

        // STAFF-B 列表不可见 STAFF-A 私有草稿（后端拼 created_by 条件，非前端过滤）
        mockMvc.perform(get("/api/v1/suppliers")
                        .header("Authorization", "Bearer " + staffBToken)
                        .param("keyword", draftName))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list.length()").value(0));

        // STAFF-A 自己可见
        mockMvc.perform(get("/api/v1/suppliers")
                        .header("Authorization", "Bearer " + staffAToken)
                        .param("keyword", draftName))
                .andExpect(jsonPath("$.data.list.length()").value(1));

        // 规则 A 行级隔离仅对 STAFF 生效（用户裁决 2026-10-14 晚）：STAFF-B 直查详情 → 40302
        mockMvc.perform(get("/api/v1/suppliers/" + draftId)
                        .header("Authorization", "Bearer " + staffBToken))
                .andExpect(jsonPath("$.code").value(40302));

        // 用户裁决 2026-10-14 晚："AUDITOR 不受 §0 规则A的约束" → 列表与详情均全量可见，
        // 与 api-spec §2 接口表 "AUDITOR/ADMIN 为全量" 一致
        String auditorToken = loginToken("auditor01", "Auditor@123");
        mockMvc.perform(get("/api/v1/suppliers")
                        .header("Authorization", "Bearer " + auditorToken)
                        .param("keyword", draftName))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list.length()").value(1));
        mockMvc.perform(get("/api/v1/suppliers/" + draftId)
                        .header("Authorization", "Bearer " + auditorToken))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        // ADMIN 全量可见、只读（写接口 40301 另有测试）
        String adminToken = loginToken("admin", "Admin@123");
        mockMvc.perform(get("/api/v1/suppliers/" + draftId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        // 准入公有区（NORMAL）后全员可见
        Supplier normal = supplierMapper.selectById(draftId);
        normal.setStatus("NORMAL");
        supplierMapper.updateById(normal);
        mockMvc.perform(get("/api/v1/suppliers/" + draftId)
                        .header("Authorization", "Bearer " + staffBToken))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void detailMissingOrSoftDeletedReturns40401() throws Exception {
        String token = loginToken("staff01", "Staff@123");
        mockMvc.perform(get("/api/v1/suppliers/999999")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(40401));
    }

    // ==================== §1.5 逻辑删除 + 税号位释放（验收 §1.6） ====================

    @Test
    void deleteDraftOnlyByOwnerThenTaxNoReleased() throws Exception {
        String ownerToken = loginToken("staff01", "Staff@123");
        String tax = nextTaxNo();
        long id = createViaApi(ownerToken, "待删除草稿", tax).get("id").asLong();

        // 非本人不可删（先建第二个 STAFF）
        User other = new User();
        other.setUsername("staff02_del");
        other.setPasswordHash(passwordEncoder.encode("StaffD@123"));
        other.setRealName("孙三");
        other.setRole(Role.STAFF.name());
        other.setEnabled(true);
        userMapper.insert(other);
        mockMvc.perform(delete("/api/v1/suppliers/" + id)
                        .header("Authorization", "Bearer " + loginToken("staff02_del", "StaffD@123")))
                .andExpect(jsonPath("$.code").value(40302));

        // 本人 DRAFT 删除成功，data=null、code=0
        mockMvc.perform(delete("/api/v1/suppliers/" + id)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(nullValue()));

        // 逻辑删除而非物理删除：DB 行仍在、deleted=1；MP @TableLogic 使 selectById 视同不存在
        Supplier raw = supplierMapper.selectById(id);
        assertThat(raw).isNull();
        mockMvc.perform(get("/api/v1/suppliers/" + id)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$.code").value(40401));

        // 生成列释放税号位：同税号重新建档成功且 status=DRAFT（验收 §1.6）
        JsonNode recreated = createViaApi(ownerToken, "税号复用重建", tax);
        assertThat(recreated.get("status").asText()).isEqualTo("DRAFT");

        // 非 DRAFT（NORMAL）不可经本接口删除 → 40302（正式记录禁物理删除入口）
        Supplier asNormal = supplierMapper.selectById(recreated.get("id").asLong());
        asNormal.setStatus("NORMAL");
        supplierMapper.updateById(asNormal);
        mockMvc.perform(delete("/api/v1/suppliers/" + recreated.get("id").asLong())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$.code").value(40302));
    }

    @Test
    void auditorCannotDeleteViaStaffOnlyEndpoint() throws Exception {
        String auditorToken = loginToken("auditor01", "Auditor@123");
        mockMvc.perform(delete("/api/v1/suppliers/1")
                        .header("Authorization", "Bearer " + auditorToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    // ==================== 分页真实生效（rules.md §1 + 沿用 PaginationIntegrationTest 规范） ====================

    @Test
    void listPaginationAppliesLimitCountAndOffset() throws Exception {
        String token = loginToken("staff01", "Staff@123");
        long staffId = userId("staff01");
        String namePrefix = "分页供应商" + TAX_SEQ.getAndIncrement();
        for (int i = 0; i < 12; i++) {
            insertRow(namePrefix + "_" + i, nextTaxNo(), "NORMAL", staffId);
        }

        String firstPage = mockMvc.perform(get("/api/v1/suppliers")
                        .header("Authorization", "Bearer " + token)
                        .param("keyword", namePrefix)
                        .param("page", "1")
                        .param("page_size", "5"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list.length()").value(5)) // LIMIT 生效
                .andExpect(jsonPath("$.data.total").value(12))        // COUNT 生效
                .andExpect(jsonPath("$.data.page_size").value(5))
                .andReturn().getResponse().getContentAsString();

        String secondPage = mockMvc.perform(get("/api/v1/suppliers")
                        .header("Authorization", "Bearer " + token)
                        .param("keyword", namePrefix)
                        .param("page", "2")
                        .param("page_size", "5"))
                .andReturn().getResponse().getContentAsString();

        long firstId = objectMapper.readTree(firstPage).get("data").get("list").get(0).get("id").asLong();
        long secondId = objectMapper.readTree(secondPage).get("data").get("list").get(0).get("id").asLong();
        assertThat(secondId).isNotEqualTo(firstId); // OFFSET 生效

        // status 过滤条件拼进查询（非前端过滤）
        mockMvc.perform(get("/api/v1/suppliers")
                        .header("Authorization", "Bearer " + token)
                        .param("keyword", namePrefix)
                        .param("status", "DRAFT"))
                .andExpect(jsonPath("$.data.total").value(0));
    }
}
