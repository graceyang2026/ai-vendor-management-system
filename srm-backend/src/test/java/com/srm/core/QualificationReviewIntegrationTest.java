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
import org.springframework.dao.DuplicateKeyException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 模块二 §2.3 存续期资质单独审批 —— 真实数据库集成测试（H2，含生成列 approved_key 与
 * 唯一索引 uk_supplier_approved_doc，见 schema-h2.sql）。契约依据：§2.3（L374-429）+ 附录 A2。
 *
 * 端到端锁定（不接受 mock 替身给出的"假绿"）：
 * - APPROVE：PENDING→APPROVED、version 真自增（@Version 拦截器）、同类型旧 APPROVED 件逻辑退位
 *   （deleted=1 且 status 仍 APPROVED，历史保留不物理删）、生效位由新件接管；
 * - REJECT：PENDING→REJECTED，旧 APPROVED 件<b>绝不被退位</b>（api-spec L281 批复真空约束）、
 *   供应商 NORMAL 原地不动（本接口零改供应商状态）；
 * - 40302 双闸门：供应商非 NORMAL（准入期件）、资质非 PENDING（已批/已驳重复审）；
 * - 40001：REJECT 缺 review_comment（含 null/空串/纯空格）；action 非法码值；version 0/负数；
 * - 40901：version 缺失与脏版本，且失败路径状态/审计零变化；
 * - 40401：资质记录不存在（实回契约 §2.3 矩阵泛义文案「资源不存在」）；
 * - 40301/40101：STAFF/ADMIN 与匿名；
 * - 审计留痕：action 必带 REVIEW_ 前缀（禁止裸 APPROVE 进审计表）、entity_type=SUPPLIER_QUALIFICATION、
 *   entity_id=资质 ID、冗余 supplier_id、old/new_status 与 comment 落库；
 * - DB 兜底：uk_supplier_approved_doc 在同供应商同类型第二条 APPROVED 真行上确实抛
 *   DuplicateKeyException（§2.3 L383 竞态封堵的物证；服务层转译 40902 由单测锁定）。
 *
 * 取数一律经 JdbcTemplate 绕过 {@code @TableLogic} 直查物理列，避免"逻辑删除行隐身"掩盖真实落库形态。
 */
@SpringBootTest(properties = "srm.seed-demo-users=true")
@AutoConfigureMockMvc
class QualificationReviewIntegrationTest {

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

    /** 独立税号段（600+），与 500+/700+/900+ 三个既有集成测试错开，共享上下文库不撞 uk */
    private static final AtomicInteger TAX_SEQ = new AtomicInteger(600);

    private static final String MSG_STATUS_NOT_ALLOWED = "当前状态不允许该操作";
    private static final String MSG_VERSION_CONFLICT = "数据已被修改，请刷新后重试";
    private static final String MSG_REJECT_COMMENT_REQUIRED = "驳回时必须填写意见";
    private static final String MSG_PARAM_INVALID = "参数校验失败";
    private static final String MSG_QUAL_NOT_FOUND = "资源不存在";

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

    /** 造数：把供应商状态直改成存续期 NORMAL（模块二流转态无法全部经 API 铺出，直写以聚焦本接口行为） */
    private void flipSupplierStatus(long id, String status) {
        Supplier s = supplierMapper.selectById(id);
        s.setStatus(status);
        supplierMapper.updateById(s);
    }

    /** 直插资质行（批复态与旧生效位只能造数产生） */
    private SupplierQualification seedQual(long supplierId, String docType, String status, LocalDate expiry) {
        SupplierQualification q = new SupplierQualification();
        q.setSupplierId(supplierId);
        q.setDocType(docType);
        q.setFileUrl("/uploads/2026/10/review-" + docType.toLowerCase() + "-" + status.toLowerCase()
                + "-" + System.nanoTime() + ".pdf");
        q.setEffectiveDate(LocalDate.of(2026, 1, 1));
        q.setExpiryDate(expiry);
        q.setStatus(status);
        q.setVersion(1);
        q.setCreatedAt(LocalDateTime.now());
        q.setUpdatedAt(LocalDateTime.now());
        qualificationMapper.insert(q);
        return q;
    }

    private String review(String token, long qualificationId, String jsonBody) throws Exception {
        return mockMvc.perform(put("/api/v1/qualifications/" + qualificationId + "/review")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private int codeOf(String json) throws Exception {
        return objectMapper.readTree(json).get("code").asInt();
    }

    private String messageOf(String json) throws Exception {
        return objectMapper.readTree(json).get("message").asText();
    }

    /** NORMAL 供应商 + 一张存续期 PENDING 营业执照，返回该资质行 */
    private SupplierQualification seedNormalPendingLicense(String staffToken, String scene) throws Exception {
        long supplierId = createSupplierViaApi(staffToken, scene);
        flipSupplierStatus(supplierId, "NORMAL");
        return seedQual(supplierId, "BUSINESS_LICENSE", "PENDING", LocalDate.now().plusYears(2));
    }

    private List<Map<String, Object>> auditRowsForQualification(long qualificationId) {
        return jdbcTemplate.queryForList(
                "SELECT entity_type, entity_id, supplier_id, operator_id, operator_role, action,"
                        + " old_status, new_status, result, comment"
                        + " FROM audit_log WHERE entity_type = 'SUPPLIER_QUALIFICATION' AND entity_id = ?",
                qualificationId);
    }

    private Integer physicalDeleted(long qualificationId) {
        return jdbcTemplate.queryForObject(
                "SELECT deleted FROM supplier_qualification WHERE id = ?", Integer.class, qualificationId);
    }

    private String physicalStatus(long qualificationId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM supplier_qualification WHERE id = ?", String.class, qualificationId);
    }

    // ==================== APPROVE 分支 ====================

    @Test
    void reviewApproveTurnsPendingToApprovedIncrementsVersionAndRetiresOldApprovedRow() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        SupplierQualification pending = seedNormalPendingLicense(staffToken, "存续期批复试验场");
        long supplierId = pending.getSupplierId();
        SupplierQualification staleApproved = seedQual(supplierId, "BUSINESS_LICENSE", "APPROVED",
                LocalDate.of(2020, 1, 1));   // 存续期补传场景：旧证照已过期但仍占生效位

        String json = review(auditorToken, pending.getId(),
                "{\"action\":\"APPROVE\",\"review_comment\":\"新证照清晰，予以通过。\",\"version\":1}");

        assertThat(codeOf(json)).isZero();
        JsonNode data = objectMapper.readTree(json).get("data");
        assertThat(data.get("status").asText()).isEqualTo("APPROVED");
        // @Version 拦截器真自增：单测层看不到的那一位，这里必须是 2
        assertThat(data.get("version").asInt()).isEqualTo(2);
        assertThat(data.get("reviewed_by").asLong()).isEqualTo(userId("auditor01"));
        assertThat(data.get("reviewed_at").asText()).isNotBlank();
        assertThat(data.get("review_comment").asText()).isEqualTo("新证照清晰，予以通过。");
        assertThat(data.get("expired").asBoolean()).isFalse();
        // 附录 A2 十三字段一个不能少
        assertThat(data.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("id", "supplier_id", "doc_type", "file_url", "effective_date",
                        "expiry_date", "status", "version", "expired", "created_at",
                        "reviewed_by", "reviewed_at", "review_comment");

        // 旧 APPROVED 件逻辑退位：deleted=1 但 status 仍是 APPROVED（历史保留不物理删、不改写状态）
        assertThat(physicalDeleted(staleApproved.getId())).isEqualTo(1);
        assertThat(physicalStatus(staleApproved.getId())).isEqualTo("APPROVED");
        assertThat(physicalDeleted(pending.getId())).isZero();
        // 新件接管生效位后，同 supplier+doc_type 的 deleted=0 APPROVED 行只能有一条（唯一索引未被破坏）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM supplier_qualification WHERE supplier_id = ? AND doc_type = ?"
                        + " AND status = 'APPROVED' AND deleted = 0",
                Integer.class, supplierId, "BUSINESS_LICENSE")).isEqualTo(1);
        // 本接口零改供应商状态：资质批复与供应商六态完全解耦
        assertThat(supplierMapper.selectById(supplierId).getStatus()).isEqualTo("NORMAL");
    }

    @Test
    void reviewApproveWritesOneReviewPrefixedAuditWithSupplierIdAndNeverBareAction() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        SupplierQualification pending = seedNormalPendingLicense(staffToken, "批复留痕试验场");

        assertThat(codeOf(review(auditorToken, pending.getId(),
                "{\"action\":\"APPROVE\",\"review_comment\":null,\"version\":1}"))).isZero();

        List<Map<String, Object>> logs = auditRowsForQualification(pending.getId());
        assertThat(logs).hasSize(1);
        Map<String, Object> log = logs.get(0);
        // 术语区分铁律（契约 §2.3 L384）：审计动作必带 REVIEW_ 前缀
        assertThat(log.get("ACTION")).isEqualTo("REVIEW_APPROVE");
        assertThat(log.get("ENTITY_TYPE")).isEqualTo("SUPPLIER_QUALIFICATION");
        assertThat(log.get("ENTITY_ID")).isEqualTo(pending.getId());
        assertThat(log.get("SUPPLIER_ID")).isEqualTo(pending.getSupplierId());
        assertThat(log.get("OPERATOR_ROLE")).isEqualTo("AUDITOR");
        assertThat(log.get("OPERATOR_ID")).isEqualTo(userId("auditor01"));
        assertThat(log.get("OLD_STATUS")).isEqualTo("PENDING");
        assertThat(log.get("NEW_STATUS")).isEqualTo("APPROVED");
        assertThat(log.get("RESULT")).isEqualTo("SUCCESS");
        assertThat(log.get("COMMENT")).isNull();
    }

    @Test
    void auditLogNeverContainsBareApproveActionForQualificationDomain() throws Exception {
        // 反向闸：整张审计表里不允许出现 action=APPROVE/REJECT 这类裸入参码（只允许 REVIEW_/AUDIT_/DECISION_ 族）
        String staffToken = loginToken("staff01", "Staff@123");
        SupplierQualification pending = seedNormalPendingLicense(staffToken, "裸码入审计防御场");
        assertThat(codeOf(loginAndReviewApprove(pending))).isZero();

        Integer bare = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action IN ('APPROVE', 'REJECT', 'PASS', 'approve')",
                Integer.class);
        assertThat(bare).isZero();
    }

    private String loginAndReviewApprove(SupplierQualification pending) throws Exception {
        return review(loginToken("auditor01", "Auditor@123"), pending.getId(),
                "{\"action\":\"APPROVE\",\"version\":1}");
    }

    @Test
    void reviewApproveMakesRowReadableThroughListWithNewVersionChain() throws Exception {
        // 取值链闭合：§1.8 列表返回的 version 就是 §2.3 的合法入参；批复后列表回读为新态新版本
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        SupplierQualification pending = seedNormalPendingLicense(staffToken, "取值链闭合试验场");

        assertThat(codeOf(review(auditorToken, pending.getId(), "{\"action\":\"APPROVE\",\"version\":1}")))
                .isZero();

        String listJson = mockMvc.perform(get("/api/v1/suppliers/" + pending.getSupplierId() + "/qualifications")
                        .header("Authorization", "Bearer " + auditorToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode rows = objectMapper.readTree(listJson).get("data");
        JsonNode row = null;
        for (JsonNode node : rows) {
            if (node.get("id").asLong() == pending.getId()) {
                row = node;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("status").asText()).isEqualTo("APPROVED");
        assertThat(row.get("version").asInt()).isEqualTo(2);
        assertThat(row.get("reviewed_by").asLong()).isEqualTo(userId("auditor01"));
    }

    // ==================== REJECT 分支 ====================

    @Test
    void reviewRejectTurnsPendingToRejectedAndKeepsOldApprovedRowInPlace() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        SupplierQualification pending = seedNormalPendingLicense(staffToken, "存续期驳回试验场");
        long supplierId = pending.getSupplierId();
        SupplierQualification staleApproved = seedQual(supplierId, "OTHER", "APPROVED",
                LocalDate.of(2019, 6, 30));

        String json = review(auditorToken, pending.getId(),
                "{\"action\":\"REJECT\",\"review_comment\":\"证照模糊无法辨识有效期，请重新上传。\",\"version\":1}");
        assertThat(codeOf(json)).isZero();
        JsonNode data = objectMapper.readTree(json).get("data");
        assertThat(data.get("status").asText()).isEqualTo("REJECTED");
        assertThat(data.get("version").asInt()).isEqualTo(2);
        assertThat(data.get("review_comment").asText()).isEqualTo("证照模糊无法辨识有效期，请重新上传。");

        // 驳回绝不退位旧件（批复真空约束）：同类型旧 APPROVED 仍 deleted=0
        assertThat(physicalDeleted(staleApproved.getId())).isZero();
        assertThat(physicalDeleted(pending.getId())).isZero();
        assertThat(supplierMapper.selectById(supplierId).getStatus()).isEqualTo("NORMAL");

        List<Map<String, Object>> logs = auditRowsForQualification(pending.getId());
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).get("ACTION")).isEqualTo("REVIEW_REJECT");
        assertThat(logs.get(0).get("OLD_STATUS")).isEqualTo("PENDING");
        assertThat(logs.get(0).get("NEW_STATUS")).isEqualTo("REJECTED");
        assertThat(logs.get(0).get("COMMENT")).isEqualTo("证照模糊无法辨识有效期，请重新上传。");
        assertThat(logs.get(0).get("SUPPLIER_ID")).isEqualTo(supplierId);
    }

    @Test
    void reviewRejectWithoutCommentOrWithBlankCommentReturns40001AndChangesNothing() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        SupplierQualification pending = seedNormalPendingLicense(staffToken, "驳回缺意见试验场");

        assertThat(codeOf(review(auditorToken, pending.getId(),
                "{\"action\":\"REJECT\",\"version\":1}"))).isEqualTo(40001);
        String blank = review(auditorToken, pending.getId(),
                "{\"action\":\"REJECT\",\"review_comment\":\"   \",\"version\":1}");
        assertThat(codeOf(blank)).isEqualTo(40001);
        assertThat(messageOf(blank)).isEqualTo(MSG_REJECT_COMMENT_REQUIRED);
        assertThat(codeOf(review(auditorToken, pending.getId(),
                "{\"action\":\"REJECT\",\"review_comment\":\"\",\"version\":1}"))).isEqualTo(40001);

        assertThat(physicalStatus(pending.getId())).isEqualTo("PENDING");
        assertThat(physicalDeleted(pending.getId())).isZero();
        assertThat(qualificationMapper.selectById(pending.getId()).getVersion()).isEqualTo(1);
        assertThat(auditRowsForQualification(pending.getId())).isEmpty();
    }

    // ==================== 40302 双闸门 ====================

    @Test
    void reviewOnNonNormalSupplierReturns40302BecauseAdmissionCasesRideAudit() throws Exception {
        // 准入期件（供应商 PENDING_REVIEW）禁走本接口，必须随 §2.2 连带批复（裁决 20261012 范围限定）
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = createSupplierViaApi(staffToken, "准入件误走单独审批试验场");
        seedQual(supplierId, "BUSINESS_LICENSE", "PENDING", LocalDate.now().plusYears(1));
        flipSupplierStatus(supplierId, "PENDING_REVIEW");
        SupplierQualification pending = firstPendingQual(supplierId);

        String json = review(auditorToken, pending.getId(), "{\"action\":\"APPROVE\",\"version\":1}");
        assertThat(codeOf(json)).isEqualTo(40302);
        assertThat(messageOf(json)).isEqualTo(MSG_STATUS_NOT_ALLOWED);
        assertThat(physicalStatus(pending.getId())).isEqualTo("PENDING");
        assertThat(auditRowsForQualification(pending.getId())).isEmpty();
    }

    @Test
    void reviewOnSuspendedOrEliminatedSupplierReturns40302() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = createSupplierViaApi(staffToken, "非存续态资质审批试验场");
        SupplierQualification pending = seedQual(supplierId, "OTHER", "PENDING", LocalDate.now().plusYears(1));

        flipSupplierStatus(supplierId, "SUSPENDED");
        assertThat(codeOf(review(auditorToken, pending.getId(), "{\"action\":\"APPROVE\",\"version\":1}")))
                .isEqualTo(40302);
        flipSupplierStatus(supplierId, "ELIMINATED");
        assertThat(codeOf(review(auditorToken, pending.getId(), "{\"action\":\"REJECT\",\"review_comment\":\"x\",\"version\":1}")))
                .isEqualTo(40302);
        assertThat(physicalStatus(pending.getId())).isEqualTo("PENDING");
    }

    @Test
    void reviewAlreadyDecidedQualificationReturns40302AndFreezesRow() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        SupplierQualification pending = seedNormalPendingLicense(staffToken, "重复审防御场");

        assertThat(codeOf(review(auditorToken, pending.getId(), "{\"action\":\"APPROVE\",\"version\":1}")))
                .isZero();

        // 已批件再批 / 再驳：一律 40302，且落库形态与第一笔留痕都不动
        String twice = review(auditorToken, pending.getId(), "{\"action\":\"APPROVE\",\"version\":2}");
        assertThat(codeOf(twice)).isEqualTo(40302);
        assertThat(messageOf(twice)).isEqualTo(MSG_STATUS_NOT_ALLOWED);
        assertThat(codeOf(review(auditorToken, pending.getId(),
                "{\"action\":\"REJECT\",\"review_comment\":\"再来一次\",\"version\":2}"))).isEqualTo(40302);

        assertThat(physicalStatus(pending.getId())).isEqualTo("APPROVED");
        assertThat(qualificationMapper.selectById(pending.getId()).getVersion()).isEqualTo(2);
        assertThat(auditRowsForQualification(pending.getId())).hasSize(1);
    }

    private SupplierQualification firstPendingQual(long supplierId) {
        List<SupplierQualification> rows = qualificationMapper.selectList(
                new LambdaQueryWrapper<SupplierQualification>()
                        .eq(SupplierQualification::getSupplierId, supplierId)
                        .eq(SupplierQualification::getStatus, "PENDING")
                        .orderByAsc(SupplierQualification::getId));
        assertThat(rows).isNotEmpty();
        return rows.get(0);
    }

    // ==================== 40901 / 40001 / 40401 / 角色闸 ====================

    @Test
    void reviewWithStaleVersionReturns40901AndFreezesEverything() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        SupplierQualification pending = seedNormalPendingLicense(staffToken, "脏版本防御场");

        String json = review(auditorToken, pending.getId(), "{\"action\":\"APPROVE\",\"version\":99}");
        assertThat(codeOf(json)).isEqualTo(40901);
        assertThat(messageOf(json)).isEqualTo(MSG_VERSION_CONFLICT);

        assertThat(physicalStatus(pending.getId())).isEqualTo("PENDING");
        assertThat(qualificationMapper.selectById(pending.getId()).getVersion()).isEqualTo(1);
        assertThat(qualificationMapper.selectById(pending.getId()).getReviewedBy()).isNull();
        assertThat(auditRowsForQualification(pending.getId())).isEmpty();
    }

    @Test
    void reviewWithMissingVersionReturns40901Not40001() throws Exception {
        // 缺失属并发语义（契约 §0 全量四实体 + api-spec「不回传或版本不匹配 → 40901」），不得折成格式失败
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        SupplierQualification pending = seedNormalPendingLicense(staffToken, "缺版本防御场");

        String json = review(auditorToken, pending.getId(), "{\"action\":\"APPROVE\"}");
        assertThat(codeOf(json)).isEqualTo(40901);
        assertThat(physicalStatus(pending.getId())).isEqualTo("PENDING");
    }

    @Test
    void reviewNonPositiveVersionReturns40001AtInputLayer() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        SupplierQualification pending = seedNormalPendingLicense(staffToken, "版本号格式防御场");

        assertThat(codeOf(review(auditorToken, pending.getId(), "{\"action\":\"APPROVE\",\"version\":0}")))
                .isEqualTo(40001);
        assertThat(codeOf(review(auditorToken, pending.getId(), "{\"action\":\"APPROVE\",\"version\":-5}")))
                .isEqualTo(40001);
        assertThat(physicalStatus(pending.getId())).isEqualTo("PENDING");
    }

    @Test
    void reviewIllegalActionReturns40001AndNeverTouchesRow() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        SupplierQualification pending = seedNormalPendingLicense(staffToken, "非法动作防御场");

        String json = review(auditorToken, pending.getId(), "{\"action\":\"PASS\",\"version\":1}");
        assertThat(codeOf(json)).isEqualTo(40001);
        assertThat(messageOf(json)).isEqualTo(MSG_PARAM_INVALID);
        assertThat(codeOf(review(auditorToken, pending.getId(), "{\"action\":\"approve\",\"version\":1}")))
                .isEqualTo(40001);
        assertThat(codeOf(review(auditorToken, pending.getId(), "{\"version\":1}"))).isEqualTo(40001);
        assertThat(physicalStatus(pending.getId())).isEqualTo("PENDING");
        assertThat(auditRowsForQualification(pending.getId())).isEmpty();
    }

    @Test
    void reviewMissingQualificationRowReturns40401AndLogicDeletedRowIsAlsoGone() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        SupplierQualification pending = seedNormalPendingLicense(staffToken, "资质不存在防御场");

        String json = review(auditorToken, 987701L, "{\"action\":\"APPROVE\",\"version\":1}");
        assertThat(codeOf(json)).isEqualTo(40401);
        assertThat(messageOf(json)).isEqualTo(MSG_QUAL_NOT_FOUND);

        // 已逻辑删除（被 §1.7 同类型顶替后的旧件形态）同样 40401：@TableLogic 使其从 selectById 隐身
        jdbcTemplate.update("UPDATE supplier_qualification SET deleted = 1 WHERE id = ?", pending.getId());
        assertThat(codeOf(review(auditorToken, pending.getId(), "{\"action\":\"APPROVE\",\"version\":1}")))
                .isEqualTo(40401);
    }

    @Test
    void reviewRoleGatesRejectStaffAdminAndAnonymous() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        SupplierQualification pending = seedNormalPendingLicense(staffToken, "角色闸防御场");
        String body = "{\"action\":\"APPROVE\",\"version\":1}";

        mockMvc.perform(put("/api/v1/qualifications/" + pending.getId() + "/review")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
        mockMvc.perform(put("/api/v1/qualifications/" + pending.getId() + "/review")
                        .header("Authorization", "Bearer " + loginToken("admin", "Admin@123"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
        mockMvc.perform(put("/api/v1/qualifications/" + pending.getId() + "/review")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));

        assertThat(physicalStatus(pending.getId())).isEqualTo("PENDING");
        assertThat(auditRowsForQualification(pending.getId())).isEmpty();
    }

    // ==================== DB 兜底物证 ====================

    @Test
    void approvedUniqueIndexReallyBlocksSecondActiveApprovedRowOfSameDocType() throws Exception {
        // §2.3 L383「APPROVE 后置生效位的并发竞态由 DB 生成列兜底」的物证：
        // 服务层预检+退位之外，绕过应用层直插第二条同类型 APPROVED 现行版必须被 uk_supplier_approved_doc 拒收
        String staffToken = loginToken("staff01", "Staff@123");
        long supplierId = createSupplierViaApi(staffToken, "生效位唯一索引物证场");
        seedQual(supplierId, "BUSINESS_LICENSE", "APPROVED", LocalDate.now().plusYears(1));

        SupplierQualification rival = new SupplierQualification();
        rival.setSupplierId(supplierId);
        rival.setDocType("BUSINESS_LICENSE");
        rival.setFileUrl("/uploads/2026/10/review-rival.pdf");
        rival.setEffectiveDate(LocalDate.of(2026, 1, 1));
        rival.setExpiryDate(LocalDate.now().plusYears(3));
        rival.setStatus("APPROVED");
        rival.setVersion(1);
        rival.setCreatedAt(LocalDateTime.now());
        rival.setUpdatedAt(LocalDateTime.now());

        assertThatThrownBy(() -> qualificationMapper.insert(rival))
                .isInstanceOf(DuplicateKeyException.class);
        // 服务层把同样的异常转译成 40902（QualificationServiceImplTest 锁定），绝不冒 50001
    }

    @Test
    void retiringOldApprovedRowReleasesUniqueSlotSoNewApprovedRowCanTakeOver() throws Exception {
        // 反向物证：旧件 deleted=1 后 approved_key 置 NULL，同类型位可再被占用（"退位让路"在 DB 层成立）
        String staffToken = loginToken("staff01", "Staff@123");
        long supplierId = createSupplierViaApi(staffToken, "退位释放占位场");
        SupplierQualification old = seedQual(supplierId, "OTHER", "APPROVED", LocalDate.of(2021, 1, 1));

        jdbcTemplate.update("UPDATE supplier_qualification SET deleted = 1 WHERE id = ?", old.getId());

        SupplierQualification successor = new SupplierQualification();
        successor.setSupplierId(supplierId);
        successor.setDocType("OTHER");
        successor.setFileUrl("/uploads/2026/10/review-successor.pdf");
        successor.setEffectiveDate(LocalDate.of(2026, 1, 1));
        successor.setExpiryDate(LocalDate.now().plusYears(2));
        successor.setStatus("APPROVED");
        successor.setVersion(1);
        successor.setCreatedAt(LocalDateTime.now());
        successor.setUpdatedAt(LocalDateTime.now());
        qualificationMapper.insert(successor);

        assertThat(successor.getId()).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT approved_key FROM supplier_qualification WHERE id = ?", String.class,
                successor.getId())).isEqualTo(supplierId + "_OTHER");
    }
}
