package com.srm.core;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.srm.core.common.enums.Role;
import com.srm.core.entity.Supplier;
import com.srm.core.entity.SupplierQualification;
import com.srm.core.entity.User;
import com.srm.core.mapper.SupplierMapper;
import com.srm.core.mapper.SupplierQualificationMapper;
import com.srm.core.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 模块二 §2.1 提交准入审核 + §2.2 审核决策（连带批复资质）真实数据库集成测试（H2，
 * 见 schema-h2.sql：supplier.version / supplier_qualification.version+approved_key+uk / audit_log.supplier_id 全都在位）。
 *
 * 契约源：docs/接口契约文档-供应商与绩效-20261013.md §2.1（L255-312）、§2.2（L316-370）
 * + docs/acceptance-criteria.md §1.1、§1.2。锁定：
 * - §2.1：DRAFT/RETURNED → PENDING_REVIEW；前置硬门槛=至少一份未过期 BUSINESS_LICENSE（expiry_date>=今天，
 *   边界当天允许）否则 40001「请先上传有效期内的营业执照」；状态非目标态 40302；非本人 40302；
 *   version 缺失或不匹配 40901（裁决 20261013 候选 B，api-spec §2 接口表为权威源）；审计 SUBMIT/SUPPLIER；
 *   SupplierResponse 20 字段整态回传（派生三枚仍归二期恒 null）。
 * - §2.2：只允许 PENDING_REVIEW（DRAFT/NORMAL → 40302，acceptance §1.1）；APPROVE→NORMAL、REJECT→RETURNED；
 *   REJECT 无 comment → 40001「驳回时必须填写意见」；version 缺失/不匹配 → 40901 且状态与资质一律不变
 *   （acceptance §1.2）；<b>同事务连带</b>：本供应商全部 PENDING 资质批量置 APPROVED/REJECTED 并回写
 *   reviewed_by/reviewed_at（附录 A2）；APPROVE 时同类型旧 APPROVED 件退位（backend-interface-design L210：
 *   "历史保留不物理删、状态不变"=逻辑删除 deleted=1，与 §1.7 顶替同范式）；
 *   审计只落 SUPPLIER 域一笔（AUDIT_APPROVE/AUDIT_REJECT），连带批复<b>不另落第二笔</b> SUPPLIER_QUALIFICATION，
 *   资质归属写 audit_log.supplier_id 冗余列（裁决 20261012 方案甲）。
 */
@SpringBootTest(properties = "srm.seed-demo-users=true")
@AutoConfigureMockMvc
class SupplierSubmitAuditIntegrationTest {

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

    /** 独立税号段（700+），与 SupplierIntegrationTest(500+)/QualificationIntegrationTest(900+) 错开 */
    private static final AtomicInteger TAX_SEQ = new AtomicInteger(700);

    private static final String MSG_LICENSE_REQUIRED = "请先上传有效期内的营业执照";
    private static final String MSG_STATUS_NOT_ALLOWED = "当前状态不允许该操作";
    private static final String MSG_VERSION_CONFLICT = "数据已被修改，请刷新后重试";
    private static final String MSG_REJECT_COMMENT_REQUIRED = "驳回时必须填写意见";

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
        User u = new User();
        u.setUsername(username);
        u.setPasswordHash(passwordEncoder.encode(password));
        u.setRealName(realName);
        u.setRole(Role.STAFF.name());
        u.setEnabled(true);
        userMapper.insert(u);
    }

    private long userId(String username) {
        User u = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        assertThat(u).isNotNull();
        return u.getId();
    }

    /** 经 §1.1 建档（创建即本人 DRAFT，version=1） */
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

    /** 直改供应商状态（造数：模拟其他路径产出的存量状态，用于 §2.2/§2.1 前置态准备） */
    private void flipSupplierStatus(long id, String status) {
        Supplier s = supplierMapper.selectById(id);
        s.setStatus(status);
        supplierMapper.updateById(s);
    }

    /** 直插资质行（批复态无法经 §1.7 产生） */
    private SupplierQualification seedQual(long supplierId, String docType, String status, LocalDate expiry) {
        SupplierQualification q = new SupplierQualification();
        q.setSupplierId(supplierId);
        q.setDocType(docType);
        q.setFileUrl("/uploads/2026/10/submit-" + docType.toLowerCase() + "-" + status.toLowerCase() + ".pdf");
        q.setEffectiveDate(LocalDate.of(2026, 1, 1));
        q.setExpiryDate(expiry);
        q.setStatus(status);
        q.setVersion(1);
        q.setCreatedAt(LocalDateTime.now());
        q.setUpdatedAt(LocalDateTime.now());
        qualificationMapper.insert(q);
        return q;
    }

    private String submit(String token, long supplierId, String jsonBody) throws Exception {
        return mockMvc.perform(post("/api/v1/suppliers/" + supplierId + "/submit")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String audit(String token, long supplierId, String jsonBody) throws Exception {
        return mockMvc.perform(post("/api/v1/suppliers/" + supplierId + "/audit")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private int codeOf(String json) throws Exception {
        return objectMapper.readTree(json).get("code").asInt();
    }

    /** 提交（带当前库内 version）到 PENDING_REVIEW 的快捷链，返回新 version */
    private int submitToPendingReview(String staffToken, long supplierId) throws Exception {
        String json = submit(staffToken, supplierId,
                "{\"version\":" + supplierMapper.selectById(supplierId).getVersion() + "}");
        assertThat(codeOf(json)).isZero();
        return objectMapper.readTree(json).get("data").get("version").asInt();
    }

    private List<Map<String, Object>> auditRows(String entityType, long entityId, String action) {
        return jdbcTemplate.queryForList(
                "SELECT entity_type, entity_id, supplier_id, operator_id, operator_role, action,"
                        + " old_status, new_status, result, comment"
                        + " FROM audit_log WHERE entity_type = ? AND entity_id = ? AND action = ?",
                entityType, entityId, action);
    }

    /** 按 entity_type + 本用例自有 supplier_id 计数留痕（跨类共享库必须自带归属条件，避免全局计数互污） */
    private int auditCount(String entityType, long supplierId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_type = ? AND supplier_id = ?",
                Integer.class, entityType, supplierId);
        return count == null ? 0 : count;
    }

    // ==================== §2.1 POST /suppliers/{id}/submit ====================

    @Test
    void submitDraftWithValidBusinessLicenseMovesToPendingReviewAndAudits() throws Exception {
        String token = loginToken("staff01", "Staff@123");
        long id = createSupplierViaApi(token, "提交准入试验场");
        seedQual(id, "BUSINESS_LICENSE", "PENDING", LocalDate.now().plusYears(1));

        String json = submit(token, id, "{\"version\":1}");
        assertThat(codeOf(json)).isZero();
        JsonNode data = objectMapper.readTree(json).get("data");
        assertThat(data.get("id").asLong()).isEqualTo(id);
        assertThat(data.get("status").asText()).isEqualTo("PENDING_REVIEW");
        // version 自增后回传，供 §2.2 审核取值（契约 §2.1 响应说明）
        assertThat(data.get("version").asInt()).isEqualTo(2);
        // SupplierResponse 全 20 字段整态回传；派生三枚归二期恒 null（裁决 2026-10-14 16:15）
        assertThat(data.has("tax_no")).isTrue();
        assertThat(data.has("created_by_name")).isTrue();
        assertThat(data.get("business_license_url").isNull()).isTrue();
        assertThat(data.get("latest_performance_grade").isNull()).isTrue();
        assertThat(data.get("risk_warning").isNull()).isTrue();

        Supplier persisted = supplierMapper.selectById(id);
        assertThat(persisted.getStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(persisted.getVersion()).isEqualTo(2);

        List<Map<String, Object>> logs = auditRows("SUPPLIER", id, "SUBMIT");
        assertThat(logs).hasSize(1);
        Map<String, Object> log = logs.get(0);
        assertThat(log.get("ENTITY_TYPE")).isEqualTo("SUPPLIER");
        assertThat(log.get("SUPPLIER_ID")).isEqualTo(id);   // 冗余归属列（DDL②）
        assertThat(log.get("OPERATOR_ID")).isEqualTo(userId("staff01"));
        assertThat(log.get("OPERATOR_ROLE")).isEqualTo("STAFF");
        assertThat(log.get("OLD_STATUS")).isEqualTo("DRAFT");
        assertThat(log.get("NEW_STATUS")).isEqualTo("PENDING_REVIEW");
        assertThat(log.get("RESULT")).isEqualTo("SUCCESS");
    }

    @Test
    void submitFromReturnedIsLegalTransition() throws Exception {
        String token = loginToken("staff01", "Staff@123");
        long id = createSupplierViaApi(token, "退回重提试验场");
        seedQual(id, "BUSINESS_LICENSE", "PENDING", LocalDate.now().plusDays(30));
        flipSupplierStatus(id, "RETURNED");

        int versionBefore = supplierMapper.selectById(id).getVersion();
        String json = submit(token, id, "{\"version\":" + versionBefore + "}");
        assertThat(codeOf(json)).isZero();
        assertThat(objectMapper.readTree(json).get("data").get("status").asText())
                .isEqualTo("PENDING_REVIEW");
        assertThat(auditRows("SUPPLIER", id, "SUBMIT")).hasSize(1);
    }

    @Test
    void submitExpiryDateEqualToTodayIsNotExpiredAndPassesGate() throws Exception {
        // 契约 §2.1：expiry_date >= 今天（当天到期仍算"未过期"）
        String token = loginToken("staff01", "Staff@123");
        long id = createSupplierViaApi(token, "到期日边界试验场");
        seedQual(id, "BUSINESS_LICENSE", "PENDING", LocalDate.now());

        assertThat(codeOf(submit(token, id, "{\"version\":1}"))).isZero();
    }

    @Test
    void submitWithoutUnexpiredBusinessLicenseReturns40001AndChangesNothing() throws Exception {
        String token = loginToken("staff01", "Staff@123");

        // ① 无任何资质
        long none = createSupplierViaApi(token, "无证照提交试验场");
        String json1 = submit(token, none, "{\"version\":1}");
        assertThat(codeOf(json1)).isEqualTo(40001);
        assertThat(objectMapper.readTree(json1).get("message").asText()).isEqualTo(MSG_LICENSE_REQUIRED);

        // ② 营业执照已过期（expiry_date < 今天）
        long expired = createSupplierViaApi(token, "过期证照提交试验场");
        seedQual(expired, "BUSINESS_LICENSE", "PENDING", LocalDate.now().minusDays(1));
        String json2 = submit(token, expired, "{\"version\":1}");
        assertThat(codeOf(json2)).isEqualTo(40001);
        assertThat(objectMapper.readTree(json2).get("message").asText()).isEqualTo(MSG_LICENSE_REQUIRED);

        // ③ 只有 OTHER 类型（非营业执照）即便未过期也不满足硬门槛
        long otherOnly = createSupplierViaApi(token, "无执照类型试验场");
        seedQual(otherOnly, "OTHER", "PENDING", LocalDate.now().plusYears(1));
        assertThat(codeOf(submit(token, otherOnly, "{\"version\":1}"))).isEqualTo(40001);

        for (long id : new long[]{none, expired, otherOnly}) {
            Supplier supplier = supplierMapper.selectById(id);
            assertThat(supplier.getStatus()).isEqualTo("DRAFT");
            assertThat(supplier.getVersion()).isEqualTo(1);
            assertThat(auditRows("SUPPLIER", id, "SUBMIT")).isEmpty();
        }
    }

    @Test
    void submitWhenStatusIsNotDraftOrReturnedReturns40302() throws Exception {
        String token = loginToken("staff01", "Staff@123");
        long id = createSupplierViaApi(token, "重复提交状态闸试验场");
        seedQual(id, "BUSINESS_LICENSE", "PENDING", LocalDate.now().plusYears(1));
        int version = submitToPendingReview(token, id);

        // 已在途不可重复提交
        String again = submit(token, id, "{\"version\":" + supplierMapper.selectById(id).getVersion() + "}");
        assertThat(codeOf(again)).isEqualTo(40302);
        assertThat(objectMapper.readTree(again).get("message").asText()).isEqualTo(MSG_STATUS_NOT_ALLOWED);
        assertThat(supplierMapper.selectById(id).getStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(supplierMapper.selectById(id).getVersion()).isEqualTo(version + 1 - 1);

        long normal = createSupplierViaApi(token, "正常态禁提交试验场");
        seedQual(normal, "BUSINESS_LICENSE", "PENDING", LocalDate.now().plusYears(1));
        flipSupplierStatus(normal, "NORMAL");
        assertThat(codeOf(submit(token, normal,
                "{\"version\":" + supplierMapper.selectById(normal).getVersion() + "}"))).isEqualTo(40302);
    }

    @Test
    void submitOthersDraftReturns40302() throws Exception {
        String ownerToken = loginToken("staff01", "Staff@123");
        insertStaff("staff02_sm", "StaffS2@123", "王二");
        String otherToken = loginToken("staff02_sm", "StaffS2@123");

        long id = createSupplierViaApi(ownerToken, "他人草稿禁提交试验场");
        seedQual(id, "BUSINESS_LICENSE", "PENDING", LocalDate.now().plusYears(1));
        String json = submit(otherToken, id, "{\"version\":1}");
        assertThat(codeOf(json)).isEqualTo(40302);
        assertThat(objectMapper.readTree(json).get("message").asText()).isEqualTo("仅限本人操作");
    }

    @Test
    void submitMissingOrStaleVersionReturns40901() throws Exception {
        // 契约 §2.1 错误矩阵 + api-spec §2 接口表（权威源）：version 缺失或不匹配 → 40901
        String token = loginToken("staff01", "Staff@123");
        long missing = createSupplierViaApi(token, "提交缺版本试验场");
        seedQual(missing, "BUSINESS_LICENSE", "PENDING", LocalDate.now().plusYears(1));
        String jsonNull = submit(token, missing, "{}");
        assertThat(codeOf(jsonNull)).isEqualTo(40901);
        assertThat(objectMapper.readTree(jsonNull).get("message").asText()).isEqualTo(MSG_VERSION_CONFLICT);
        assertThat(supplierMapper.selectById(missing).getStatus()).isEqualTo("DRAFT");
        assertThat(auditRows("SUPPLIER", missing, "SUBMIT")).isEmpty();

        long stale = createSupplierViaApi(token, "提交脏版本试验场");
        seedQual(stale, "BUSINESS_LICENSE", "PENDING", LocalDate.now().plusYears(1));
        // 先把库内版本推进到 2（PUT §1.2 合法编辑），再用旧版本 1 提交 → 并发脏写拦截
        editViaApi(token, stale, 1);
        assertThat(supplierMapper.selectById(stale).getVersion()).isEqualTo(2);
        String jsonStale = submit(token, stale, "{\"version\":1}");
        assertThat(codeOf(jsonStale)).isEqualTo(40901);
        assertThat(objectMapper.readTree(jsonStale).get("message").asText()).isEqualTo(MSG_VERSION_CONFLICT);
        assertThat(supplierMapper.selectById(stale).getStatus()).isEqualTo("DRAFT");
        assertThat(supplierMapper.selectById(stale).getVersion()).isEqualTo(2);
    }

    /** 经 §1.2 PUT 推进 version（模拟另一端并发写），只改 remark 一个字段 */
    private void editViaApi(String token, long supplierId, int version) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/suppliers/" + supplierId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"remark\":\"另一端并发写\",\"version\":" + version + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void submitVersionBelowOneIsRejectedByParameterLayer() throws Exception {
        // 字段表：version 必填且 >= 1；0/负数属格式非法 → 40001（与缺失=40901 分层）
        String token = loginToken("staff01", "Staff@123");
        long id = createSupplierViaApi(token, "提交非法版本试验场");
        seedQual(id, "BUSINESS_LICENSE", "PENDING", LocalDate.now().plusYears(1));
        assertThat(codeOf(submit(token, id, "{\"version\":0}"))).isEqualTo(40001);
    }

    @Test
    void submitRoleGatesAndMissingSupplier() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        long id = createSupplierViaApi(staffToken, "提交角色闸试验场");
        seedQual(id, "BUSINESS_LICENSE", "PENDING", LocalDate.now().plusYears(1));

        mockMvc.perform(post("/api/v1/suppliers/" + id + "/submit")
                        .header("Authorization", "Bearer " + loginToken("auditor01", "Auditor@123"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
        mockMvc.perform(post("/api/v1/suppliers/" + id + "/submit")
                        .header("Authorization", "Bearer " + loginToken("admin", "Admin@123"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
        mockMvc.perform(post("/api/v1/suppliers/" + id + "/submit")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));

        // 不存在/已逻辑删除的供应商 → 40401（实回文案随已实现代码，契约 L36 注记）
        String gone = submit(staffToken, 987601, "{\"version\":1}");
        assertThat(codeOf(gone)).isEqualTo(40401);
        assertThat(objectMapper.readTree(gone).get("message").asText()).isEqualTo("供应商不存在");
    }

    // ==================== §2.2 POST /suppliers/{id}/audit ====================

    @Test
    void auditApproveMovesToNormalAndCascadesPendingQualificationsToApproved() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long id = createSupplierViaApi(staffToken, "准入通过连带试验场");
        SupplierQualification license = seedQual(id, "BUSINESS_LICENSE", "PENDING",
                LocalDate.now().plusYears(2));
        SupplierQualification other = seedQual(id, "OTHER", "PENDING", LocalDate.now().plusYears(2));
        int version = submitToPendingReview(staffToken, id);

        String json = audit(auditorToken, id,
                "{\"version\":" + version + ",\"decision\":\"APPROVE\"}");
        assertThat(codeOf(json)).isZero();
        JsonNode data = objectMapper.readTree(json).get("data");
        assertThat(data.get("status").asText()).isEqualTo("NORMAL");
        assertThat(data.get("version").asInt()).isEqualTo(version + 1);

        // 连带批复（同事务）：本供应商全部 PENDING → APPROVED，回写 reviewed_by/reviewed_at，version 自增
        SupplierQualification approvedLicense = qualificationMapper.selectById(license.getId());
        assertThat(approvedLicense.getStatus()).isEqualTo("APPROVED");
        assertThat(approvedLicense.getReviewedBy()).isEqualTo(userId("auditor01"));
        assertThat(approvedLicense.getReviewedAt()).isNotNull();
        assertThat(approvedLicense.getVersion()).isEqualTo(2);
        assertThat(qualificationMapper.selectById(other.getId()).getStatus()).isEqualTo("APPROVED");

        // 审计：SUPPLIER 域一笔；连带批复不另落第二笔 SUPPLIER_QUALIFICATION（方案甲）
        List<Map<String, Object>> logs = auditRows("SUPPLIER", id, "AUDIT_APPROVE");
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).get("ENTITY_TYPE")).isEqualTo("SUPPLIER");
        assertThat(logs.get(0).get("SUPPLIER_ID")).isEqualTo(id);
        assertThat(logs.get(0).get("OPERATOR_ROLE")).isEqualTo("AUDITOR");
        assertThat(logs.get(0).get("OLD_STATUS")).isEqualTo("PENDING_REVIEW");
        assertThat(logs.get(0).get("NEW_STATUS")).isEqualTo("NORMAL");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'SUPPLIER_QUALIFICATION' AND supplier_id = ?",
                Integer.class, id)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_id = ? AND action = 'REVIEW_APPROVE'",
                Integer.class, license.getId())).isZero();
    }

    @Test
    void auditApproveRetiresOldApprovedRowOfSameDocTypeWithoutPhysicalDelete() throws Exception {
        // backend-interface-design L210：APPROVE 时同 supplier+doc_type 旧 APPROVED 件"自动退位
        // （历史保留不物理删，状态不变）"——落库即 deleted=1、status 仍为 APPROVED，生效位由新件接管
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long id = createSupplierViaApi(staffToken, "连带退位试验场");
        flipSupplierStatus(id, "RETURNED");
        SupplierQualification staleApproved = seedQual(id, "BUSINESS_LICENSE", "APPROVED",
                LocalDate.of(2020, 1, 1));
        SupplierQualification otherApproved = seedQual(id, "OTHER", "APPROVED",
                LocalDate.now().plusYears(1));   // 非本批 doc_type，不受影响
        SupplierQualification renewed = seedQual(id, "BUSINESS_LICENSE", "PENDING",
                LocalDate.now().plusYears(3));
        int version = submitToPendingReview(staffToken, id);

        assertThat(codeOf(audit(auditorToken, id, "{\"version\":" + version + ",\"decision\":\"APPROVE\"}")))
                .isZero();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted FROM supplier_qualification WHERE id = ?", Integer.class,
                staleApproved.getId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM supplier_qualification WHERE id = ?", String.class,
                staleApproved.getId())).isEqualTo("APPROVED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted FROM supplier_qualification WHERE id = ?", Integer.class,
                renewed.getId())).isZero();
        assertThat(qualificationMapper.selectById(renewed.getId()).getStatus()).isEqualTo("APPROVED");
        // OTHER 的 APPROVED 件零扰动：断言锁定<b>本用例自己那一行</b>而非全局计数——
        // H2 上下文库跨类共享，全局 COUNT(*) 会被其他测试类的同类型在途顶替（合法 deleted=1）污染
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted FROM supplier_qualification WHERE id = ?", Integer.class,
                otherApproved.getId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM supplier_qualification WHERE id = ?", String.class,
                otherApproved.getId())).isEqualTo("APPROVED");
    }

    @Test
    void auditRejectMovesToReturnedAndCascadesQualificationsToRejected() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long id = createSupplierViaApi(staffToken, "准入驳回连带试验场");
        SupplierQualification license = seedQual(id, "BUSINESS_LICENSE", "PENDING",
                LocalDate.now().plusYears(2));
        int version = submitToPendingReview(staffToken, id);

        String json = audit(auditorToken, id,
                "{\"version\":" + version + ",\"decision\":\"REJECT\",\"comment\":\"营业执照已过期，请更换后重新提交。\"}");
        assertThat(codeOf(json)).isZero();
        JsonNode data = objectMapper.readTree(json).get("data");
        assertThat(data.get("status").asText()).isEqualTo("RETURNED");
        assertThat(data.get("version").asInt()).isEqualTo(version + 1);

        SupplierQualification rejected = qualificationMapper.selectById(license.getId());
        assertThat(rejected.getStatus()).isEqualTo("REJECTED");
        assertThat(rejected.getReviewedBy()).isEqualTo(userId("auditor01"));
        assertThat(rejected.getReviewComment()).isEqualTo("营业执照已过期，请更换后重新提交。");

        List<Map<String, Object>> logs = auditRows("SUPPLIER", id, "AUDIT_REJECT");
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).get("OLD_STATUS")).isEqualTo("PENDING_REVIEW");
        assertThat(logs.get(0).get("NEW_STATUS")).isEqualTo("RETURNED");
        assertThat(logs.get(0).get("COMMENT")).isEqualTo("营业执照已过期，请更换后重新提交。");
        assertThat(logs.get(0).get("SUPPLIER_ID")).isEqualTo(id);
        // 方案甲零新增码：连带批复不得另落本供应商资质域第二笔留痕。
        // 计数必须按 supplier_id 收敛：H2 上下文库在同一次 mvn test 内跨测试类共享且不回滚，
        // 全局 COUNT(*) 会被 §2.3 单独审批用例（同类合法留痕）污染成假失败。
        assertThat(auditCount("SUPPLIER_QUALIFICATION", id)).isZero();
    }

    @Test
    void auditRejectWithoutCommentReturns40001AndChangesNothing() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long id = createSupplierViaApi(staffToken, "驳回缺意见试验场");
        SupplierQualification license = seedQual(id, "BUSINESS_LICENSE", "PENDING",
                LocalDate.now().plusYears(1));
        int version = submitToPendingReview(staffToken, id);

        assertThat(codeOf(audit(auditorToken, id,
                "{\"version\":" + version + ",\"decision\":\"REJECT\"}"))).isEqualTo(40001);
        assertThat(codeOf(audit(auditorToken, id,
                "{\"version\":" + version + ",\"decision\":\"REJECT\",\"comment\":\"   \"}"))).isEqualTo(40001);
        String blank = audit(auditorToken, id,
                "{\"version\":" + version + ",\"decision\":\"REJECT\",\"comment\":\"\"}");
        assertThat(codeOf(blank)).isEqualTo(40001);
        assertThat(objectMapper.readTree(blank).get("message").asText()).isEqualTo(MSG_REJECT_COMMENT_REQUIRED);

        assertThat(supplierMapper.selectById(id).getStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(supplierMapper.selectById(id).getVersion()).isEqualTo(version);
        assertThat(qualificationMapper.selectById(license.getId()).getStatus()).isEqualTo("PENDING");
        assertThat(auditRows("SUPPLIER", id, "AUDIT_REJECT")).isEmpty();
    }

    @Test
    void auditOnDraftOrNormalSupplierReturns40302() throws Exception {
        // acceptance §1.1：DRAFT 直接 audit → 40302，状态不变
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long draft = createSupplierViaApi(staffToken, "草稿直审试验场");
        String json = audit(auditorToken, draft, "{\"version\":1,\"decision\":\"APPROVE\"}");
        assertThat(codeOf(json)).isEqualTo(40302);
        assertThat(objectMapper.readTree(json).get("message").asText()).isEqualTo(MSG_STATUS_NOT_ALLOWED);
        assertThat(supplierMapper.selectById(draft).getStatus()).isEqualTo("DRAFT");
        assertThat(supplierMapper.selectById(draft).getVersion()).isEqualTo(1);

        long normal = createSupplierViaApi(staffToken, "正常态再审试验场");
        flipSupplierStatus(normal, "NORMAL");
        assertThat(codeOf(audit(auditorToken, normal,
                "{\"version\":" + supplierMapper.selectById(normal).getVersion() + ",\"decision\":\"APPROVE\"}")))
                .isEqualTo(40302);
    }

    @Test
    void auditStaleVersionReturns40901AndFreezesSupplierAndQualifications() throws Exception {
        // acceptance §1.2：AUDITOR 持旧 version 决策 → 40901，供应商状态与资质一律不变
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long id = createSupplierViaApi(staffToken, "审核并发试验场");
        SupplierQualification license = seedQual(id, "BUSINESS_LICENSE", "PENDING",
                LocalDate.now().plusYears(1));
        int version = submitToPendingReview(staffToken, id);
        // 另一端并发把版本推进到 version+1（合法编辑本人 RETURNED 不行，此处用 STAFF 改在途记录会被拒；
        // 故直接以 mapper 推进版本，模拟并发写窗口）
        bumpSupplierVersionByIdMapper(id);

        String json = audit(auditorToken, id,
                "{\"version\":" + version + ",\"decision\":\"APPROVE\"}");
        assertThat(codeOf(json)).isEqualTo(40901);
        assertThat(objectMapper.readTree(json).get("message").asText()).isEqualTo(MSG_VERSION_CONFLICT);
        assertThat(supplierMapper.selectById(id).getStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(qualificationMapper.selectById(license.getId()).getStatus()).isEqualTo("PENDING");
        assertThat(qualificationMapper.selectById(license.getId()).getReviewedBy()).isNull();
        assertThat(auditRows("SUPPLIER", id, "AUDIT_APPROVE")).isEmpty();
    }

    /** 绕过状态闸门直推 version（模拟另一端并发写；@Version 拦截器按库内版本自增） */
    private void bumpSupplierVersionByIdMapper(long id) {
        Supplier s = supplierMapper.selectById(id);
        s.setRemark("并发另一端写入");
        supplierMapper.updateById(s);
    }

    @Test
    void auditMissingVersionOrUnknownDecisionReturns40901Or40001() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long id = createSupplierViaApi(staffToken, "审核入参边界试验场");
        seedQual(id, "BUSINESS_LICENSE", "PENDING", LocalDate.now().plusYears(1));
        int version = submitToPendingReview(staffToken, id);

        // 缺失 version → 40901（§0 乐观锁四实体 + §2.1 同口径；见留痕"契约内部不一致"备案）
        assertThat(codeOf(audit(auditorToken, id,
                "{\"decision\":\"APPROVE\"}"))).isEqualTo(40901);
        // decision 非法码值 → 入参层 40001（字段表枚举列 APPROVE/REJECT）
        assertThat(codeOf(audit(auditorToken, id,
                "{\"version\":" + version + ",\"decision\":\"PASS\"}"))).isEqualTo(40001);
        assertThat(supplierMapper.selectById(id).getStatus()).isEqualTo("PENDING_REVIEW");
    }

    @Test
    void auditRoleGatesAndMissingSupplier() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        long id = createSupplierViaApi(staffToken, "审核角色闸试验场");
        seedQual(id, "BUSINESS_LICENSE", "PENDING", LocalDate.now().plusYears(1));
        int version = submitToPendingReview(staffToken, id);

        mockMvc.perform(post("/api/v1/suppliers/" + id + "/audit")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + version + ",\"decision\":\"APPROVE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
        mockMvc.perform(post("/api/v1/suppliers/" + id + "/audit")
                        .header("Authorization", "Bearer " + loginToken("admin", "Admin@123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + version + ",\"decision\":\"APPROVE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
        mockMvc.perform(post("/api/v1/suppliers/" + id + "/audit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + version + ",\"decision\":\"APPROVE\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));

        String missing = audit(loginToken("auditor01", "Auditor@123"), 987602,
                "{\"version\":1,\"decision\":\"APPROVE\"}");
        assertThat(codeOf(missing)).isEqualTo(40401);
        assertThat(objectMapper.readTree(missing).get("message").asText()).isEqualTo("供应商不存在");
    }

    @Test
    void auditApproveWithoutAnyQualificationStillSucceeds() throws Exception {
        // 连带批复空集分支：无 PENDING 资质时不得报错、不得写资质行
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long id = createSupplierViaApi(staffToken, "空资质连带试验场");
        seedQual(id, "BUSINESS_LICENSE", "PENDING", LocalDate.now().plusYears(1));
        int version = submitToPendingReview(staffToken, id);
        qualificationMapper.delete(new LambdaQueryWrapper<SupplierQualification>()
                .eq(SupplierQualification::getSupplierId, id));

        assertThat(codeOf(audit(auditorToken, id,
                "{\"version\":" + version + ",\"decision\":\"APPROVE\"}"))).isZero();
        assertThat(supplierMapper.selectById(id).getStatus()).isEqualTo("NORMAL");
        assertThat(qualificationMapper.selectCount(new LambdaQueryWrapper<SupplierQualification>()
                .eq(SupplierQualification::getSupplierId, id))).isZero();
    }
}
