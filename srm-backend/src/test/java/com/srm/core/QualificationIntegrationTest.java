package com.srm.core;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.srm.core.common.enums.Role;
import com.srm.core.entity.Supplier;
import com.srm.core.entity.SupplierQualification;
import com.srm.core.mapper.SupplierMapper;
import com.srm.core.mapper.SupplierQualificationMapper;
import com.srm.core.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 模块一 §1.7/§1.8 真实数据库集成测试（H2，supplier_qualification 表含生成列 approved_key +
 * 唯一索引 uk_supplier_approved_doc，见 schema-h2.sql；照抄 supplier 表 H2 适配范式）。覆盖：
 * - §1.7：上传即 PENDING/version=1（裁决 20261012）；DRAFT/RETURNED/NORMAL 三态放行（裁决 20261011）；
 *   40401（含逻辑删除供应商）、40302（非本人先判、状态门槛后判）、40301（非 STAFF）、40101（匿名）、
 *   40001 契约固定文案端到端回传（HTTP 200 + body.code，用户裁决 2026-10-14）
 * - §1.8：APPROVED+PENDING 全员可见；REJECTED 仅建档本人（STAFF 非本人被后端 SQL 排除），
 *   AUDITOR/ADMIN 全量含 REJECTED（裁决 2026-10-14 晚 × 契约组合口径）；
 *   每行携带 version（§2.3 取值链）与 expired（expiry_date &lt; 今天 同表派生）；
 *   他人私有在途区 40302、供应商不存在 40401
 * - DDL 等价性：uk_supplier_approved_doc 生成列唯一约束在 H2 真实生效（§2.3 竞态 DB 兜底的基础）
 * - §1.7 同类型在途顶替（裁决 20261015）：真库实证 delete(wrapper) 是 UPDATE deleted=1 而非物理 DELETE
 *   （JdbcTemplate 绕过 @TableLogic 直查物理列），且 APPROVED/REJECTED/跨 doc_type 免疫、校验失败不顶旧件
 */
@SpringBootTest(properties = "srm.seed-demo-users=true")
@AutoConfigureMockMvc
class QualificationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SupplierMapper supplierMapper;

    @Autowired
    private SupplierQualificationMapper qualificationMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /** 独立税号段（900+），与 SupplierIntegrationTest（500+）错开，共享上下文库不撞 uk */
    private static final AtomicInteger TAX_SEQ = new AtomicInteger(900);

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

    private void insertStaff(String username, String password, String realName) {
        com.srm.core.entity.User u = new com.srm.core.entity.User();
        u.setUsername(username);
        u.setPasswordHash(passwordEncoder.encode(password));
        u.setRealName(realName);
        u.setRole(Role.STAFF.name());
        u.setEnabled(true);
        userMapper.insert(u);
    }

    /** 通过 §1.1 API 建档（创建即本人 DRAFT），返回供应商 ID */
    private long createSupplierViaApi(String token, String name) throws Exception {
        String body = """
                {
                  "name": "%s",
                  "tax_no": "%s",
                  "type": "MANUFACTURING",
                  "contact_name": "王强",
                  "contact_phone": "13800138000",
                  "effective_date": "2026-01-01",
                  "expiry_date": "2028-12-31"
                }
                """.formatted(name, nextTaxNo());
        String resp = mockMvc.perform(post("/api/v1/suppliers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).get("data").get("id").asLong();
    }

    /** 直改供应商状态（模拟模块二流转后的状态，绕开未实现的 submit/audit 入口） */
    private void flipSupplierStatus(long id, String status) {
        Supplier s = supplierMapper.selectById(id);
        s.setStatus(status);
        supplierMapper.updateById(s);
    }

    private String qualBody(String docType, String fileUrl, String effective, String expiry) {
        return """
                {
                  "doc_type": "%s",
                  "file_url": "%s",
                  "effective_date": "%s",
                  "expiry_date": "%s"
                }
                """.formatted(docType, fileUrl, effective, expiry);
    }

    /** 直插资质行（批复态无法经 §1.7 产生，只能造数——模拟模块二批复后的存量） */
    private SupplierQualification seedQual(long supplierId, String status, String docType,
                                           LocalDate effective, LocalDate expiry) {
        SupplierQualification q = new SupplierQualification();
        q.setSupplierId(supplierId);
        q.setDocType(docType);
        q.setFileUrl("/uploads/2026/10/seed-" + docType.toLowerCase() + "-" + status.toLowerCase() + ".pdf");
        q.setEffectiveDate(effective);
        q.setExpiryDate(expiry);
        q.setStatus(status);
        q.setVersion(1);
        q.setCreatedAt(LocalDateTime.now());
        q.setUpdatedAt(LocalDateTime.now());
        qualificationMapper.insert(q);
        return q;
    }

    private List<String> statusesOf(String json) throws Exception {
        List<String> statuses = new ArrayList<>();
        for (JsonNode row : objectMapper.readTree(json).get("data")) {
            statuses.add(row.get("status").asText());
        }
        return statuses;
    }

    private String listAsString(String token, long supplierId) throws Exception {
        return mockMvc.perform(get("/api/v1/suppliers/" + supplierId + "/qualifications")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    // ==================== §1.7 上传资质 ====================

    @Test
    void uploadReturnsPendingVersionOneAndPersistsSame() throws Exception {
        String token = loginToken("staff01", "Staff@123");
        long supplierId = createSupplierViaApi(token, "资质上传试验场");

        String resp = mockMvc.perform(post("/api/v1/suppliers/" + supplierId + "/qualifications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(qualBody("BUSINESS_LICENSE", "/uploads/2026/10/bl.pdf",
                                "2026-01-01", "2028-12-31")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.supplier_id").value((int) supplierId))
                .andExpect(jsonPath("$.data.doc_type").value("BUSINESS_LICENSE"))
                .andExpect(jsonPath("$.data.file_url").value("/uploads/2026/10/bl.pdf"))
                // 裁决 20261012：新记录一律 PENDING + version=1，不直接生效
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.expired").value(false))
                .andExpect(jsonPath("$.data.reviewed_by").doesNotExist()) // 未批复：null 值在 SNAKE_CASE 序列化下仍出键
                .andReturn().getResponse().getContentAsString();
        // 上述 doesNotExist 与"键在值为 null"取决于序列化策略，此处只做无歧义断言：
        assertThat(objectMapper.readTree(resp).get("data").has("reviewed_by")).isTrue();
        assertThat(objectMapper.readTree(resp).get("data").get("reviewed_by").isNull()).isTrue();

        SupplierQualification persisted = qualificationMapper.selectOne(
                new LambdaQueryWrapper<SupplierQualification>()
                        .eq(SupplierQualification::getSupplierId, supplierId));
        assertThat(persisted).isNotNull();
        assertThat(persisted.getStatus()).isEqualTo("PENDING");
        assertThat(persisted.getVersion()).isEqualTo(1);
        assertThat(persisted.getDeleted()).isEqualTo(0);
        assertThat(persisted.getEffectiveDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(persisted.getExpiryDate()).isEqualTo(LocalDate.of(2028, 12, 31));
    }

    @Test
    void uploadAllowedOnOwnReturnedAndNormalSuppliers() throws Exception {
        // 裁决 20261011：DRAFT/RETURNED/NORMAL 三态放行（解除"准入后资质无法维护"死锁）
        String token = loginToken("staff01", "Staff@123");
        long returned = createSupplierViaApi(token, "退回态补传试验场");
        flipSupplierStatus(returned, "RETURNED");
        mockMvc.perform(post("/api/v1/suppliers/" + returned + "/qualifications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(qualBody("OTHER", "/uploads/2026/10/other1.pdf",
                                "2026-01-01", "2027-12-31")))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        long normal = createSupplierViaApi(token, "存续期补传试验场");
        flipSupplierStatus(normal, "NORMAL");
        mockMvc.perform(post("/api/v1/suppliers/" + normal + "/qualifications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(qualBody("OTHER", "/uploads/2026/10/other2.pdf",
                                "2026-01-01", "2027-12-31")))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void uploadValidationFailuresReturnContractFixedMessagesOnHttp200() throws Exception {
        String token = loginToken("staff01", "Staff@123");
        long supplierId = createSupplierViaApi(token, "资质校验试验场");

        // doc_type 值域（契约 §1.7 枚举 BUSINESS_LICENSE/OTHER；message 为契约未收录的自拟固定文案，见留痕）
        mockMvc.perform(post("/api/v1/suppliers/" + supplierId + "/qualifications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(qualBody("营业执照", "/uploads/2026/10/x.pdf", "2026-01-01", "2028-12-31")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("资质类型不合法（仅支持 BUSINESS_LICENSE/OTHER）"));

        // file_url 必填（§1.6 返回的 URL）
        mockMvc.perform(post("/api/v1/suppliers/" + supplierId + "/qualifications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(qualBody("OTHER", "  ", "2026-01-01", "2028-12-31")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("资质文件地址不能为空"));

        // 日期：yyyy-MM-dd 且 expiry 晚于 effective（契约固定文案）
        mockMvc.perform(post("/api/v1/suppliers/" + supplierId + "/qualifications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(qualBody("OTHER", "/uploads/2026/10/y.pdf", "2028-12-31", "2026-01-01")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("日期格式不合法或失效日期早于生效日期"));

        // 2026-02-30：过正则但语义非法 → 仍是 40001，不得冒 50001
        mockMvc.perform(post("/api/v1/suppliers/" + supplierId + "/qualifications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(qualBody("OTHER", "/uploads/2026/10/z.pdf", "2026-02-30", "2027-01-01")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void uploadToMissingOrSoftDeletedSupplierReturns40401() throws Exception {
        String token = loginToken("staff01", "Staff@123");

        mockMvc.perform(post("/api/v1/suppliers/987654/qualifications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(qualBody("OTHER", "/uploads/2026/10/a.pdf", "2026-01-01", "2028-12-31")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40401))
                .andExpect(jsonPath("$.message").value("供应商不存在"));

        // §1.5 逻辑删除后的供应商：@TableLogic 使其视同不存在 → 同样 40401
        long doomed = createSupplierViaApi(token, "删除后禁传试验场");
        mockMvc.perform(delete("/api/v1/suppliers/" + doomed)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(post("/api/v1/suppliers/" + doomed + "/qualifications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(qualBody("OTHER", "/uploads/2026/10/b.pdf", "2026-01-01", "2028-12-31")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40401));
    }

    @Test
    void uploadToOthersSupplierOrForbiddenStatusReturns40302() throws Exception {
        String ownerToken = loginToken("staff01", "Staff@123");
        insertStaff("staff02_qual", "StaffQ@123", "王二");
        String otherToken = loginToken("staff02_qual", "StaffQ@123");

        // 他人 NORMAL（公有区）档案：读得到但传不了——§1.7 角色口径"STAFF（本人）"
        long publicNormal = createSupplierViaApi(ownerToken, "公有区他人档案试验场");
        flipSupplierStatus(publicNormal, "NORMAL");
        mockMvc.perform(post("/api/v1/suppliers/" + publicNormal + "/qualifications")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(qualBody("OTHER", "/uploads/2026/10/c.pdf", "2026-01-01", "2028-12-31")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40302))
                .andExpect(jsonPath("$.message").value("仅限本人操作"));

        // 本人但状态在途/停用：状态门槛 40302（契约 §1.7 错误矩阵；message 用契约 40302 固定文案）
        long pending = createSupplierViaApi(ownerToken, "在途态禁传试验场");
        flipSupplierStatus(pending, "PENDING_REVIEW");
        mockMvc.perform(post("/api/v1/suppliers/" + pending + "/qualifications")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(qualBody("OTHER", "/uploads/2026/10/d.pdf", "2026-01-01", "2028-12-31")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40302))
                .andExpect(jsonPath("$.message").value("当前状态不允许该操作"));

        long suspended = createSupplierViaApi(ownerToken, "停用态禁传试验场");
        flipSupplierStatus(suspended, "SUSPENDED");
        mockMvc.perform(post("/api/v1/suppliers/" + suspended + "/qualifications")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(qualBody("OTHER", "/uploads/2026/10/e.pdf", "2026-01-01", "2028-12-31")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40302));
    }

    @Test
    void uploadRoleGatesAuditorAdmin40301Anonymous40101() throws Exception {
        String ownerToken = loginToken("staff01", "Staff@123");
        long supplierId = createSupplierViaApi(ownerToken, "角色拦截试验场");
        String body = qualBody("OTHER", "/uploads/2026/10/f.pdf", "2026-01-01", "2028-12-31");

        // AUDITOR/ADMIN 不得写业务档案（403 由 Security 层产出，见 B1 同口径）
        mockMvc.perform(post("/api/v1/suppliers/" + supplierId + "/qualifications")
                        .header("Authorization", "Bearer " + loginToken("auditor01", "Auditor@123"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
        mockMvc.perform(post("/api/v1/suppliers/" + supplierId + "/qualifications")
                        .header("Authorization", "Bearer " + loginToken("admin", "Admin@123"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));

        // 匿名 401/40101
        mockMvc.perform(post("/api/v1/suppliers/" + supplierId + "/qualifications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    // ==================== §1.8 资质列表 ====================

    @Test
    void listRejectedVisibleOnlyToOwnerStaffButFullForAuditorAdmin() throws Exception {
        String ownerToken = loginToken("staff01", "Staff@123");
        insertStaff("staff03_qual", "StaffQ3@123", "孙三");
        String otherToken = loginToken("staff03_qual", "StaffQ3@123");

        // 公有区 NORMAL：全员可读列表，但 REJECTED 行仅建档本人（STAFF 非本人被后端过滤）
        long supplierId = createSupplierViaApi(ownerToken, "REJECTED可见性试验场");
        flipSupplierStatus(supplierId, "NORMAL");
        seedQual(supplierId, "APPROVED", "BUSINESS_LICENSE",
                LocalDate.of(2026, 1, 1), LocalDate.of(2029, 1, 1));
        seedQual(supplierId, "PENDING", "OTHER",
                LocalDate.of(2026, 1, 1), LocalDate.of(2029, 1, 1));
        seedQual(supplierId, "REJECTED", "OTHER",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 1));

        // 契约 §1.8 × 裁决 2026-10-14 晚组合口径：STAFF 非本人 → APPROVED+PENDING，无 REJECTED
        assertThat(statusesOf(listAsString(otherToken, supplierId)))
                .containsExactlyInAnyOrder("APPROVED", "PENDING");
        // 建档本人 → REJECTED 可追溯
        assertThat(statusesOf(listAsString(ownerToken, supplierId)))
                .containsExactlyInAnyOrder("APPROVED", "PENDING", "REJECTED");
        // AUDITOR/ADMIN 全量含 REJECTED（批复主体本就产 REJECTED 行，不可对其隐身；ADMIN 本就全量）
        assertThat(statusesOf(listAsString(loginToken("auditor01", "Auditor@123"), supplierId)))
                .containsExactlyInAnyOrder("APPROVED", "PENDING", "REJECTED");
        assertThat(statusesOf(listAsString(loginToken("admin", "Admin@123"), supplierId)))
                .containsExactlyInAnyOrder("APPROVED", "PENDING", "REJECTED");

        // 私有在途区：STAFF 非本人连列表都 40302（§0 规则 A 后端强制）
        long privateDraft = createSupplierViaApi(ownerToken, "私有区列表试验场");
        seedQual(privateDraft, "PENDING", "OTHER",
                LocalDate.of(2026, 1, 1), LocalDate.of(2029, 1, 1));
        mockMvc.perform(get("/api/v1/suppliers/" + privateDraft + "/qualifications")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40302));
        // 裁决 2026-10-14 晚：AUDITOR 不受规则 A 约束 → 他人私有在途区也可列
        mockMvc.perform(get("/api/v1/suppliers/" + privateDraft + "/qualifications")
                        .header("Authorization", "Bearer " + loginToken("auditor01", "Auditor@123")))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void listRowsCarryVersionAndExpiredDerivationAndReviewTrace() throws Exception {
        String ownerToken = loginToken("staff01", "Staff@123");
        long supplierId = createSupplierViaApi(ownerToken, "派生位列表试验场");
        flipSupplierStatus(supplierId, "NORMAL");

        SupplierQualification expiredApproved = seedQual(supplierId, "APPROVED", "BUSINESS_LICENSE",
                LocalDate.of(2020, 1, 1), LocalDate.now().minusDays(1));
        // 批复痕迹由模块二回写；集成层直接造数模拟已批存量
        expiredApproved.setReviewedBy(3L);
        expiredApproved.setReviewedAt(LocalDateTime.of(2026, 10, 2, 15, 0));
        expiredApproved.setReviewComment("历史批复备注");
        // 不手设 version：乐观锁拦截器按 WHERE version=1 更新并自增，落库后真实为 2（下方断言即验此链）
        qualificationMapper.updateById(expiredApproved);
        seedQual(supplierId, "PENDING", "OTHER",
                LocalDate.of(2026, 1, 1), LocalDate.now().plusYears(2));

        String json = listAsString(ownerToken, supplierId);
        JsonNode data = objectMapper.readTree(json).get("data");
        assertThat(data).hasSize(2);
        JsonNode approvedRow = null;
        JsonNode pendingRow = null;
        for (JsonNode row : data) {
            if ("APPROVED".equals(row.get("status").asText())) {
                approvedRow = row;
            } else {
                pendingRow = row;
            }
        }
        // expired = expiry_date < 今天（同表派生，本批实现）；version 每行携带（§2.3 取值链）
        assertThat(approvedRow.get("expired").asBoolean()).isTrue();
        assertThat(approvedRow.get("version").asInt()).isEqualTo(2);
        assertThat(approvedRow.get("reviewed_by").asLong()).isEqualTo(3L);
        assertThat(approvedRow.get("reviewed_at").asText()).isEqualTo("2026-10-02T15:00:00");
        assertThat(approvedRow.get("review_comment").asText()).isEqualTo("历史批复备注");
        assertThat(pendingRow.get("expired").asBoolean()).isFalse();
        assertThat(pendingRow.get("version").asInt()).isEqualTo(1);
    }

    @Test
    void listMissingSupplierReturns40401AndEmptyArrayWhenNoRows() throws Exception {
        String ownerToken = loginToken("staff01", "Staff@123");
        mockMvc.perform(get("/api/v1/suppliers/987655/qualifications")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40401));

        long empty = createSupplierViaApi(ownerToken, "空资质列表试验场");
        mockMvc.perform(get("/api/v1/suppliers/" + empty + "/qualifications")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void listAnonymousRejectedWith40101() throws Exception {
        mockMvc.perform(get("/api/v1/suppliers/1/qualifications"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    // ==================== DDL 等价性：生成列唯一约束真实生效 ====================

    @Test
    void approvedUniqueGuardIsLiveOnH2ViaGeneratedColumn() {
        // uk_supplier_approved_doc（schema-h2.sql 照 supplier 表范式建 approved_key 生成列）：
        // 同供应商同 doc_type 仅一条 APPROVED——§2.3 批复竞态的 DB 兜底，本批先锁定 DDL 等价性
        String tax = nextTaxNo();
        Supplier supplier = new Supplier();
        supplier.setName("生效位唯一试验场");
        supplier.setTaxNo(tax);
        supplier.setType("DISTRIBUTOR");
        supplier.setContactName("联系人");
        supplier.setContactPhone("13800138000");
        supplier.setEffectiveDate(LocalDate.of(2026, 1, 1));
        supplier.setExpiryDate(LocalDate.of(2028, 12, 31));
        supplier.setStatus("NORMAL");
        supplier.setCreatedBy(userId("staff01"));
        supplier.setVersion(1);
        supplier.setCreatedAt(LocalDateTime.now());
        supplier.setUpdatedAt(LocalDateTime.now());
        supplierMapper.insert(supplier);

        seedQual(supplier.getId(), "APPROVED", "BUSINESS_LICENSE",
                LocalDate.of(2024, 1, 1), LocalDate.of(2027, 1, 1));
        assertThatThrownBy(() -> seedQual(supplier.getId(), "APPROVED", "BUSINESS_LICENSE",
                LocalDate.of(2025, 1, 1), LocalDate.of(2028, 1, 1)))
                .isInstanceOf(DuplicateKeyException.class);

        // PENDING/REJECTED 不占生效位：同 doc_type 多条在途/历史合法
        seedQual(supplier.getId(), "PENDING", "BUSINESS_LICENSE",
                LocalDate.of(2026, 1, 1), LocalDate.of(2029, 1, 1));
        seedQual(supplier.getId(), "REJECTED", "BUSINESS_LICENSE",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 1));
        List<SupplierQualification> rows = qualificationMapper.selectList(
                new LambdaQueryWrapper<SupplierQualification>()
                        .eq(SupplierQualification::getSupplierId, supplier.getId()));
        assertThat(rows).hasSize(3);
    }

    // ==================== §1.7 同类型在途顶替（裁决 20261015，契约 §1.7 业务约束新增行） ====================

    /** 经 §1.7 上传一份证照并回传新行 id */
    private long uploadQualAndGetId(String token, long supplierId, String docType, String fileUrl) throws Exception {
        String resp = mockMvc.perform(post("/api/v1/suppliers/" + supplierId + "/qualifications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(qualBody(docType, fileUrl, "2026-01-01", "2028-12-31")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).get("data").get("id").asLong();
    }

    /** 绕过 @TableLogic 直查物理行：顶替必须是 UPDATE deleted=1（可查），行必须还在（非物理 DELETE） */
    private int deletedOf(long qualificationId) {
        Integer deleted = jdbcTemplate.queryForObject(
                "SELECT deleted FROM supplier_qualification WHERE id = ?", Integer.class, qualificationId);
        assertThat(deleted).isNotNull();
        return deleted;
    }

    @Test
    void resubmitSameDocTypeLogicallyReplacesOldPendingRow() throws Exception {
        // Red①：同 supplier + 同 doc_type 的旧 PENDING 被顶（deleted=1 物理保留），新行 PENDING/version=1，
        // 被顶旧件经 @TableLogic 自动从 §1.8 隐身
        String token = loginToken("staff01", "Staff@123");
        long supplierId = createSupplierViaApi(token, "在途顶替试验场");

        long oldId = uploadQualAndGetId(token, supplierId, "BUSINESS_LICENSE", "/uploads/2026/10/bl-old.pdf");
        long newId = uploadQualAndGetId(token, supplierId, "BUSINESS_LICENSE", "/uploads/2026/10/bl-new.pdf");

        assertThat(deletedOf(oldId)).isEqualTo(1);
        assertThat(deletedOf(newId)).isZero();

        JsonNode data = objectMapper.readTree(listAsString(token, supplierId)).get("data");
        assertThat(data).hasSize(1);
        assertThat(data.get(0).get("id").asLong()).isEqualTo(newId);
        assertThat(data.get(0).get("status").asText()).isEqualTo("PENDING");
        assertThat(data.get(0).get("version").asInt()).isEqualTo(1);
        // MP 逻辑视图同样只见新行（@TableLogic 对 selectList 自动追加 deleted=0）
        assertThat(qualificationMapper.selectCount(new LambdaQueryWrapper<SupplierQualification>()
                .eq(SupplierQualification::getSupplierId, supplierId))).isEqualTo(1L);
    }

    @Test
    void resubmitDoesNotTouchApprovedOrRejectedRows() throws Exception {
        // Red②：批复产出的 APPROVED/REJECTED 行不在顶替匹配集内（delete wrapper 三 eq 只命中 PENDING）
        String token = loginToken("staff01", "Staff@123");
        long supplierId = createSupplierViaApi(token, "批复态免疫试验场");
        flipSupplierStatus(supplierId, "NORMAL");
        SupplierQualification approved = seedQual(supplierId, "APPROVED", "BUSINESS_LICENSE",
                LocalDate.of(2026, 1, 1), LocalDate.of(2029, 1, 1));
        SupplierQualification rejected = seedQual(supplierId, "REJECTED", "BUSINESS_LICENSE",
                LocalDate.of(2020, 1, 1), LocalDate.of(2025, 1, 1));

        uploadQualAndGetId(token, supplierId, "BUSINESS_LICENSE", "/uploads/2026/10/bl-renew.pdf");

        assertThat(deletedOf(approved.getId())).isZero();
        assertThat(deletedOf(rejected.getId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM supplier_qualification WHERE id = ?", String.class, approved.getId()))
                .isEqualTo("APPROVED");
    }

    @Test
    void failedUploadDoesNotReplaceExistingPendingRow() throws Exception {
        // Red③：校验失败（40001 日期语义 / 40302 状态门槛）都发生在顶替步骤之前——旧 PENDING 必须原样在途
        String token = loginToken("staff01", "Staff@123");
        long supplierId = createSupplierViaApi(token, "校验失败保旧件试验场");
        long oldId = uploadQualAndGetId(token, supplierId, "OTHER", "/uploads/2026/10/keep.pdf");

        mockMvc.perform(post("/api/v1/suppliers/" + supplierId + "/qualifications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(qualBody("OTHER", "/uploads/2026/10/bad.pdf", "2028-12-31", "2026-01-01")))
                .andExpect(jsonPath("$.code").value(40001));
        assertThat(deletedOf(oldId)).isZero();

        flipSupplierStatus(supplierId, "PENDING_REVIEW");
        mockMvc.perform(post("/api/v1/suppliers/" + supplierId + "/qualifications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(qualBody("OTHER", "/uploads/2026/10/gated.pdf", "2026-01-01", "2028-12-31")))
                .andExpect(jsonPath("$.code").value(40302));
        assertThat(deletedOf(oldId)).isZero();
        // 逻辑视图旧件仍可见（未被顶掉）
        assertThat(qualificationMapper.selectById(oldId)).isNotNull();
    }

    @Test
    void resubmitBusinessLicenseLeavesOtherDocTypePendingIntact() throws Exception {
        // Red④：顶替严格按 doc_type 划界——OTHER 在途件不受 BUSINESS_LICENSE 重提交影响
        String token = loginToken("staff01", "Staff@123");
        long supplierId = createSupplierViaApi(token, "跨类型不误伤试验场");
        long blId = uploadQualAndGetId(token, supplierId, "BUSINESS_LICENSE", "/uploads/2026/10/bl1.pdf");
        long otherId = uploadQualAndGetId(token, supplierId, "OTHER", "/uploads/2026/10/other1.pdf");

        long blRenewId = uploadQualAndGetId(token, supplierId, "BUSINESS_LICENSE", "/uploads/2026/10/bl2.pdf");

        assertThat(deletedOf(blId)).isEqualTo(1);
        assertThat(deletedOf(blRenewId)).isZero();
        assertThat(deletedOf(otherId)).isZero();
    }

    private long userId(String username) {
        com.srm.core.entity.User u = userMapper.selectOne(
                new LambdaQueryWrapper<com.srm.core.entity.User>().eq(com.srm.core.entity.User::getUsername, username));
        assertThat(u).isNotNull();
        return u.getId();
    }
}
