package com.srm.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.srm.core.common.enums.Role;
import com.srm.core.entity.Supplier;
import com.srm.core.entity.SupplierQualification;
import com.srm.core.entity.User;
import com.srm.core.mapper.LifecycleRequestMapper;
import com.srm.core.mapper.SupplierMapper;
import com.srm.core.mapper.SupplierQualificationMapper;
import com.srm.core.mapper.UserMapper;
import com.srm.core.entity.LifecycleRequest;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 模块二 §2.4/§2.5/§2.6 生命周期申请三接口 —— 真实数据库集成测试（H2，含生成列 active_key 与
 * 唯一索引 uk_supplier_active_lifecycle，见 schema-h2.sql L101-123）。
 * 契约依据：§2.4-§2.6（L433-556）+ 附录 A3（L844）+ docs/api-spec.md §3（L288-326）。
 *
 * 端到端只锁"库内真实形态"，不接受 mock 替身给出的假绿：
 * - <b>两步流程</b>：§2.4 建单后供应商 status 与 version 分毫不动（唯一改状态入口是 §2.6 APPROVE）；
 * - §2.6 APPROVE 三条联动 SUSPEND→SUSPENDED / RESUME→NORMAL / ELIMINATE→ELIMINATED，
 *   申请单与供应商<b>两张表的 version 同时各自 +1</b>（@Version 拦截器真实生效，单测层看不到那一位）；
 * - §2.6 REJECT 只改申请单，供应商状态与 version 一律不动；
 * - <b>在途唯一双保险</b>：应用层预检 40902 + 绕过应用层直插第二条同类型 PENDING 被
 *   uk_supplier_active_lifecycle 拒收（DuplicateKeyException 物证）；已决/驳回单让占位自动释放
 *   （active_key 置 NULL），同类型可再次建单；
 * - <b>终态阻断</b>：ELIMINATED 之后三种类型一律 40302；
 * - <b>决策时重判门槛</b>：建单后供应商被并发流转 → 40302 且申请单仍是 PENDING（无"半改"）；
 * - 乐观锁 40901（脏版本 / 缺版本）失败路径状态、决策痕迹、审计三者全冻结；
 * - §2.5 只读：按 applied_at 倒序、逻辑删除行隐身、<b>零审计零写库</b>、每行携带 version（取值链闭合）；
 * - 审计动作只允许 SUBMIT / DECISION_APPROVE / DECISION_REJECT，裸 APPROVE/REJECT 全表计数为 0；
 * - <b>仅建档人可提</b>（用户裁决 20261015 收紧 §2.4）：非建档人提他人 NORMAL/SUSPENDED 供应商的
 *   任何类型一律 40302「仅限本人操作」且零建单零审计，归属闸先于状态门槛与在途预检；建档人照常。
 *
 * 取数一律经 JdbcTemplate 绕过 {@code @TableLogic} 直查物理列。税号段 300+，与 500+/600+/700+/900+
 * 四个既有集成测试错开（共享上下文库不撞 uk_tax_no_unique）。
 */
@SpringBootTest(properties = "srm.seed-demo-users=true")
@AutoConfigureMockMvc
class LifecycleRequestIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SupplierMapper supplierMapper;

    @Autowired
    private SupplierQualificationMapper qualificationMapper;

    @Autowired
    private LifecycleRequestMapper lifecycleRequestMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private static final AtomicInteger TAX_SEQ = new AtomicInteger(300);

    private static final String MSG_SUPPLIER_NOT_FOUND = "供应商不存在";
    private static final String MSG_REQUEST_NOT_FOUND = "资源不存在";
    private static final String MSG_STATUS_NOT_ALLOWED = "当前状态不允许该操作";
    private static final String MSG_VERSION_CONFLICT = "数据已被修改，请刷新后重试";
    private static final String MSG_IN_FLIGHT_EXISTS = "已存在在途申请，请勿重复提交";
    private static final String MSG_PARAM_INVALID = "参数校验失败";
    /** requireSelf 固定文案（裁决 20261015 后 §2.4 与 §1.2/§1.5/§2.1 同口径） */
    private static final String MSG_SELF_ONLY = "仅限本人操作";

    /** 附录 A3 的 13 字段（顺序无关，集合必须完全一致） */
    private static final String[] A3_FIELDS = {
            "id", "supplier_id", "type", "reason", "status", "applied_by", "applied_by_name",
            "applied_at", "decided_by", "decided_by_name", "decided_at", "decision_comment", "version"
    };

    // ==================== 夹具与探针 ====================

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
        User u = userMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query
                .LambdaQueryWrapper<User>().eq(User::getUsername, username));
        assertThat(u).isNotNull();
        return u.getId();
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

    /**
     * 走完整准入链把供应商推到 NORMAL（§1.1 建档 → §1.7 传照 → §2.1 提交 → §2.2 批复）：
     * 存续期申请的前提态一律经<b>生产接口</b>产出，绝不用直改状态掩盖真实流转。
     */
    private long promoteToNormal(String staffToken, String auditorToken, String name) throws Exception {
        long supplierId = createSupplierViaApi(staffToken, name);
        seedLicense(supplierId);
        String submit = mockMvc.perform(post("/api/v1/suppliers/" + supplierId + "/submit")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + supplierMapper.selectById(supplierId).getVersion() + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(codeOf(submit)).isZero();
        int pendingReviewVersion = objectMapper.readTree(submit).get("data").get("version").asInt();
        String audit = mockMvc.perform(post("/api/v1/suppliers/" + supplierId + "/audit")
                        .header("Authorization", "Bearer " + auditorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + pendingReviewVersion + ",\"decision\":\"APPROVE\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(codeOf(audit)).isZero();
        assertThat(supplierMapper.selectById(supplierId).getStatus()).isEqualTo("NORMAL");
        return supplierId;
    }

    private void seedLicense(long supplierId) {
        SupplierQualification q = new SupplierQualification();
        q.setSupplierId(supplierId);
        q.setDocType("BUSINESS_LICENSE");
        q.setFileUrl("/uploads/2026/10/lifecycle-license-" + supplierId + ".pdf");
        q.setEffectiveDate(LocalDate.of(2026, 1, 1));
        q.setExpiryDate(LocalDate.now().plusYears(2));
        q.setStatus("PENDING");
        q.setVersion(1);
        q.setCreatedAt(LocalDateTime.now());
        q.setUpdatedAt(LocalDateTime.now());
        qualificationMapper.insert(q);
    }

    /** 造数：直改供应商状态（只为铺出"并发流转后"的前提态，不用于本域正常流） */
    private void flipSupplierStatus(long id, String status) {
        Supplier s = supplierMapper.selectById(id);
        s.setStatus(status);
        supplierMapper.updateById(s);
    }

    private String createViaApi(String token, long supplierId, String jsonBody) throws Exception {
        return mockMvc.perform(post("/api/v1/suppliers/" + supplierId + "/lifecycle-requests")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String listViaApi(String token, long supplierId) throws Exception {
        return mockMvc.perform(get("/api/v1/suppliers/" + supplierId + "/lifecycle-requests")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String decideViaApi(String token, long requestId, String jsonBody) throws Exception {
        return mockMvc.perform(post("/api/v1/lifecycle-requests/" + requestId + "/decision")
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

    private JsonNode dataOf(String json) throws Exception {
        return objectMapper.readTree(json).get("data");
    }

    /** 建单快捷链，返回新申请单 ID */
    private long createRequest(String staffToken, long supplierId, String type, String reason) throws Exception {
        String json = createViaApi(staffToken, supplierId,
                "{\"type\":\"" + type + "\",\"reason\":\"" + reason + "\"}");
        assertThat(codeOf(json)).isZero();
        return dataOf(json).get("id").asLong();
    }

    private Map<String, Object> physicalRequest(long requestId) {
        return jdbcTemplate.queryForMap(
                "SELECT supplier_id, type, reason, status, version, active_key, deleted,"
                        + " applied_by, applied_at, decided_by, decided_at, decision_comment"
                        + " FROM lifecycle_request WHERE id = ?", requestId);
    }

    /** TINYINT 经 JDBC 返回类型不定（Short/Integer），逻辑删除位一律按 Integer 取数断言 */
    private int physicalDeleted(long requestId) {
        Integer deleted = jdbcTemplate.queryForObject(
                "SELECT deleted FROM lifecycle_request WHERE id = ?", Integer.class, requestId);
        return deleted == null ? -1 : deleted;
    }

    private String physicalRequestStatus(long requestId) {
        return (String) physicalRequest(requestId).get("STATUS");
    }

    private int physicalActiveCount(long supplierId, String type) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lifecycle_request WHERE supplier_id = ? AND type = ?"
                        + " AND status = 'PENDING' AND deleted = 0", Integer.class, supplierId, type);
        return count == null ? 0 : count;
    }

    private int physicalTotalCount(long supplierId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lifecycle_request WHERE supplier_id = ?", Integer.class, supplierId);
        return count == null ? 0 : count;
    }

    private List<Map<String, Object>> auditRows(long requestId) {
        return jdbcTemplate.queryForList(
                "SELECT entity_type, entity_id, supplier_id, operator_id, operator_role, action,"
                        + " old_status, new_status, result, comment"
                        + " FROM audit_log WHERE entity_type = 'LIFECYCLE_REQUEST' AND entity_id = ?",
                requestId);
    }

    private int auditCount(long supplierId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'LIFECYCLE_REQUEST' AND supplier_id = ?",
                Integer.class, supplierId);
        return count == null ? 0 : count;
    }

    private int supplierVersion(long supplierId) {
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM supplier WHERE id = ?", Integer.class, supplierId);
        return version == null ? -1 : version;
    }

    private String supplierStatus(long supplierId) {
        return jdbcTemplate.queryForObject("SELECT status FROM supplier WHERE id = ?",
                String.class, supplierId);
    }

    // ==================== §2.4 建单：只建单、绝不动主表 ====================

    @Test
    void createPersistsPendingRequestAndLeavesSupplierStatusAndVersionUntouched() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = promoteToNormal(staffToken, auditorToken, "停用申请试验场");
        int versionBefore = supplierVersion(supplierId);

        String json = createViaApi(staffToken, supplierId,
                "{\"type\":\"SUSPEND\",\"reason\":\"连续两期绩效 D 级，暂停新订单分配。\"}");

        assertThat(codeOf(json)).isZero();
        JsonNode data = dataOf(json);
        long requestId = data.get("id").asLong();
        assertThat(data.fieldNames()).toIterable().containsExactlyInAnyOrder(A3_FIELDS);
        assertThat(data.get("status").asText()).isEqualTo("PENDING");
        assertThat(data.get("version").asInt()).isEqualTo(1);
        assertThat(data.get("supplier_id").asLong()).isEqualTo(supplierId);
        assertThat(data.get("type").asText()).isEqualTo("SUSPEND");
        assertThat(data.get("applied_by_name").asText()).isEqualTo("张三");
        assertThat(data.get("applied_by").asLong()).isEqualTo(userId("staff01"));
        // 未决单的决策痕迹三枚必须为 JSON null（不得伪造空串或时间）
        assertThat(data.get("decided_by").isNull()).isTrue();
        assertThat(data.get("decided_by_name").isNull()).isTrue();
        assertThat(data.get("decided_at").isNull()).isTrue();
        assertThat(data.get("decision_comment").isNull()).isTrue();

        // 两步流程铁律的库内物证：建单这一跳物理上碰不到 supplier.status / supplier.version
        assertThat(supplierStatus(supplierId)).isEqualTo("NORMAL");
        assertThat(supplierVersion(supplierId)).isEqualTo(versionBefore);

        Map<String, Object> row = physicalRequest(requestId);
        assertThat(row.get("STATUS")).isEqualTo("PENDING");
        assertThat(row.get("VERSION")).isEqualTo(1);
        assertThat(physicalDeleted(requestId)).isZero();
        assertThat(row.get("REASON")).isEqualTo("连续两期绩效 D 级，暂停新订单分配。");
        assertThat(row.get("DECIDED_BY")).isNull();
        assertThat(row.get("DECIDED_AT")).isNull();
        assertThat(row.get("DECISION_COMMENT")).isNull();
        // 生成列占位：在途单必须真实占用 supplier_id_TYPE（uk_supplier_active_lifecycle 的载体）
        assertThat(row.get("ACTIVE_KEY")).isEqualTo(supplierId + "_SUSPEND");
    }

    @Test
    void createTrimsReasonAndWritesExactlyOneSubmitAuditWithSupplierIdAndNullOldStatus() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = promoteToNormal(staffToken, auditorToken, "留痕归属试验场");

        long requestId = createRequest(staffToken, supplierId, "ELIMINATE", "  资不抵债，依据法院破产裁定。  ");

        assertThat(physicalRequest(requestId).get("REASON"))
                .isEqualTo("资不抵债，依据法院破产裁定。");

        List<Map<String, Object>> logs = auditRows(requestId);
        assertThat(logs).hasSize(1);
        Map<String, Object> log = logs.get(0);
        assertThat(log.get("ACTION")).isEqualTo("SUBMIT");
        assertThat(log.get("ENTITY_TYPE")).isEqualTo("LIFECYCLE_REQUEST");
        assertThat(log.get("ENTITY_ID")).isEqualTo(requestId);
        assertThat(log.get("SUPPLIER_ID")).isEqualTo(supplierId);
        assertThat(log.get("OPERATOR_ID")).isEqualTo(userId("staff01"));
        assertThat(log.get("OPERATOR_ROLE")).isEqualTo("STAFF");
        // 申请单自身的状态维度：从无到 PENDING，故 old_status 为 NULL；reason 已落业务行，审计不冗存
        assertThat(log.get("OLD_STATUS")).isNull();
        assertThat(log.get("NEW_STATUS")).isEqualTo("PENDING");
        assertThat(log.get("RESULT")).isEqualTo("SUCCESS");
        assertThat(log.get("COMMENT")).isNull();
    }

    @Test
    void createRejectsSuspendOnSuspendedAndResumeOnNormalBecauseThresholdIsExact() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = promoteToNormal(staffToken, auditorToken, "门槛错配试验场");

        // NORMAL 状态下 RESUME 不合法（只有 SUSPENDED 能申请恢复）
        String resumeOnNormal = createViaApi(staffToken, supplierId,
                "{\"type\":\"RESUME\",\"reason\":\"整改已完成。\"}");
        assertThat(codeOf(resumeOnNormal)).isEqualTo(40302);
        assertThat(messageOf(resumeOnNormal)).isEqualTo(MSG_STATUS_NOT_ALLOWED);

        flipSupplierStatus(supplierId, "SUSPENDED");
        assertThat(codeOf(createViaApi(staffToken, supplierId,
                "{\"type\":\"SUSPEND\",\"reason\":\"再次停用。\"}"))).isEqualTo(40302);

        assertThat(physicalTotalCount(supplierId)).isZero();
        assertThat(auditCount(supplierId)).isZero();
        assertThat(supplierStatus(supplierId)).isEqualTo("SUSPENDED");
    }

    @Test
    void createAllowsEliminateFromBothNormalAndSuspended() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");

        long normal = promoteToNormal(staffToken, auditorToken, "正常区淘汰试验场");
        assertThat(codeOf(createViaApi(staffToken, normal,
                "{\"type\":\"ELIMINATE\",\"reason\":\"连续违规。\"}"))).isZero();

        long suspended = promoteToNormal(staffToken, auditorToken, "停用区淘汰试验场");
        flipSupplierStatus(suspended, "SUSPENDED");
        assertThat(codeOf(createViaApi(staffToken, suspended,
                "{\"type\":\"ELIMINATE\",\"reason\":\"停用期满仍未整改。\"}"))).isZero();

        assertThat(physicalRequestStatus(firstRequestIdOf(suspended))).isEqualTo("PENDING");
    }

    @Test
    void createRejectsRequestsOnSuppliersOutsideLifecycleZoneWith40302() throws Exception {
        // 准入区三态（DRAFT/PENDING_REVIEW/RETURNED）不属于生命周期流转范围
        String staffToken = loginToken("staff01", "Staff@123");
        long draft = createSupplierViaApi(staffToken, "草稿区申请防御场");

        assertThat(codeOf(createViaApi(staffToken, draft,
                "{\"type\":\"SUSPEND\",\"reason\":\"草稿也要停用？\"}"))).isEqualTo(40302);
        assertThat(codeOf(createViaApi(staffToken, draft,
                "{\"type\":\"RESUME\",\"reason\":\"草稿要恢复？\"}"))).isEqualTo(40302);
        // ELIMINATE 门槛的两个条件同时为假（DRAFT 既非 NORMAL 也非 SUSPENDED）
        assertThat(codeOf(createViaApi(staffToken, draft,
                "{\"type\":\"ELIMINATE\",\"reason\":\"草稿直接淘汰？\"}"))).isEqualTo(40302);
        assertThat(physicalTotalCount(draft)).isZero();
        assertThat(auditCount(draft)).isZero();
    }

    @Test
    void createRejectsEveryTypeOnceSupplierIsEliminatedWith40302() throws Exception {
        // 终态不可逆：ELIMINATED 之后 SUSPEND/RESUME/ELIMINATE 三种申请一律禁建（契约 §2.4 L443）
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = promoteToNormal(staffToken, auditorToken, "终态阻断试验场");
        long eliminateRequest = createRequest(staffToken, supplierId, "ELIMINATE", "资不抵债。");
        assertThat(codeOf(decideViaApi(auditorToken, eliminateRequest,
                "{\"decision\":\"APPROVE\",\"comment\":\"同意淘汰。\",\"version\":1}"))).isZero();
        assertThat(supplierStatus(supplierId)).isEqualTo("ELIMINATED");

        int rowsBefore = physicalTotalCount(supplierId);
        int auditBefore = auditCount(supplierId);
        assertThat(auditBefore).isEqualTo(2);   // 建单 SUBMIT + 终审 DECISION_APPROVE 各一笔
        for (String type : List.of("SUSPEND", "RESUME", "ELIMINATE")) {
            String json = createViaApi(staffToken, supplierId,
                    "{\"type\":\"" + type + "\",\"reason\":\"终态之后还要建单？\"}");
            assertThat(codeOf(json)).as("终态禁建 %s", type).isEqualTo(40302);
            assertThat(messageOf(json)).isEqualTo(MSG_STATUS_NOT_ALLOWED);
        }
        assertThat(physicalTotalCount(supplierId)).isEqualTo(rowsBefore);
        // 三次被拒的建单一律不落审计（40302 失败路径零留痕，只有真正成功的操作才写库）
        assertThat(auditCount(supplierId)).isEqualTo(auditBefore);
    }

    @Test
    void createRejectsSecondInFlightSameTypeWith40902AndKeepsSingleActiveSlot() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = promoteToNormal(staffToken, auditorToken, "在途重复试验场");

        long first = createRequest(staffToken, supplierId, "SUSPEND", "绩效连续两期 D 级。");

        String twice = createViaApi(staffToken, supplierId,
                "{\"type\":\"SUSPEND\",\"reason\":\"换个理由再提一次。\"}");
        assertThat(codeOf(twice)).isEqualTo(40902);
        assertThat(messageOf(twice)).isEqualTo(MSG_IN_FLIGHT_EXISTS);
        // 文案绝不泄露索引名/SQL 片段
        assertThat(messageOf(twice)).doesNotContain("uk_supplier_active_lifecycle", "Duplicate entry", "SQL");

        assertThat(physicalActiveCount(supplierId, "SUSPEND")).isEqualTo(1);
        assertThat(physicalTotalCount(supplierId)).isEqualTo(1);
        assertThat(physicalRequest(first).get("ACTIVE_KEY")).isEqualTo(supplierId + "_SUSPEND");

        // 不同类型不占用同一 slot，可并行在途（SUSPEND + ELIMINATE 两张 PENDING 单）
        assertThat(codeOf(createViaApi(staffToken, supplierId,
                "{\"type\":\"ELIMINATE\",\"reason\":\"同步评估淘汰。\"}"))).isZero();
        assertThat(physicalTotalCount(supplierId)).isEqualTo(2);
    }

    @Test
    void createAllowsSameTypeAgainOncePreviousRequestIsRejectedBecauseSlotIsReleased() throws Exception {
        // 生成列的 CASE WHEN 只在 deleted=0 AND status='PENDING' 时占位：被驳回即释放，允许再次申请
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = promoteToNormal(staffToken, auditorToken, "占位释放试验场");
        long first = createRequest(staffToken, supplierId, "SUSPEND", "首次停用申请。");

        assertThat(codeOf(decideViaApi(auditorToken, first,
                "{\"decision\":\"REJECT\",\"version\":1}"))).isZero();
        assertThat(physicalRequest(first).get("ACTIVE_KEY")).isNull();
        assertThat(physicalActiveCount(supplierId, "SUSPEND")).isZero();

        assertThat(codeOf(createViaApi(staffToken, supplierId,
                "{\"type\":\"SUSPEND\",\"reason\":\"补足材料后二次停用申请。\"}"))).isZero();
        assertThat(physicalActiveCount(supplierId, "SUSPEND")).isEqualTo(1);
    }

    @Test
    void activeKeyUniqueIndexReallyBlocksSecondPendingRowOfSameSupplierAndType() throws Exception {
        // §2.4「同类型存在 PENDING 在途 → 40902」的 DB 物证：绕过应用层预检直插必须被唯一索引拒收
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = promoteToNormal(staffToken, auditorToken, "唯一索引物证场");
        createRequest(staffToken, supplierId, "SUSPEND", "应用层建的那张在途单。");

        LifecycleRequest rival = new LifecycleRequest();
        rival.setSupplierId(supplierId);
        rival.setType("SUSPEND");
        rival.setReason("并发窗口里另一台实例建的单。");
        rival.setStatus("PENDING");
        rival.setVersion(1);
        rival.setAppliedBy(userId("staff01"));
        rival.setAppliedAt(LocalDateTime.now());
        rival.setCreatedAt(LocalDateTime.now());
        rival.setUpdatedAt(LocalDateTime.now());

        assertThatThrownBy(() -> lifecycleRequestMapper.insert(rival))
                .isInstanceOf(DuplicateKeyException.class);
        // 服务层把同样的异常转译成 40902（LifecycleRequestServiceImplTest 锁定），绝不冒 50001
    }

    @Test
    void createRejectsUnknownSupplierWith40401AndIllegalInputWith40001() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = promoteToNormal(staffToken, auditorToken, "入参与存在性防御场");

        assertThat(codeOf(createViaApi(staffToken, 987631L,
                "{\"type\":\"SUSPEND\",\"reason\":\"不存在的供应商。\"}"))).isEqualTo(40401);
        assertThat(messageOf(createViaApi(staffToken, 987631L,
                "{\"type\":\"SUSPEND\",\"reason\":\"不存在的供应商。\"}"))).isEqualTo(MSG_SUPPLIER_NOT_FOUND);

        assertThat(codeOf(createViaApi(staffToken, supplierId,
                "{\"type\":\"suspend\",\"reason\":\"小写码值。\"}"))).isEqualTo(40001);
        assertThat(messageOf(createViaApi(staffToken, supplierId,
                "{\"type\":\"suspend\",\"reason\":\"小写码值。\"}"))).isEqualTo(MSG_PARAM_INVALID);
        assertThat(codeOf(createViaApi(staffToken, supplierId,
                "{\"type\":\"BLACKLIST\",\"reason\":\"未注册码值。\"}"))).isEqualTo(40001);
        assertThat(codeOf(createViaApi(staffToken, supplierId,
                "{\"type\":\"SUSPEND\"}"))).isEqualTo(40001);
        assertThat(codeOf(createViaApi(staffToken, supplierId,
                "{\"type\":\"SUSPEND\",\"reason\":\"   \"}"))).isEqualTo(40001);

        assertThat(physicalTotalCount(supplierId)).isZero();
        assertThat(auditCount(supplierId)).isZero();
    }

    @Test
    void createRoleGatesRejectAuditorAdminAndAnonymous() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = promoteToNormal(staffToken, auditorToken, "建单角色闸防御场");
        String body = "{\"type\":\"SUSPEND\",\"reason\":\"只有 STAFF 能提申请。\"}";

        mockMvc.perform(post("/api/v1/suppliers/" + supplierId + "/lifecycle-requests")
                        .header("Authorization", "Bearer " + auditorToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
        mockMvc.perform(post("/api/v1/suppliers/" + supplierId + "/lifecycle-requests")
                        .header("Authorization", "Bearer " + loginToken("admin", "Admin@123"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
        mockMvc.perform(post("/api/v1/suppliers/" + supplierId + "/lifecycle-requests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));

        assertThat(physicalTotalCount(supplierId)).isZero();
    }

    // ==================== §2.5 只读历史 ====================

    @Test
    void listReturnsFullHistoryNewestFirstWithoutAuditingOrWriting() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = promoteToNormal(staffToken, auditorToken, "历史倒序试验场");

        // 物理插入三行，applied_at 刻意乱序 + 其中一行申请人已不存在（人名回退空串）
        insertHistoricalRow(supplierId, "SUSPEND", "PENDING", 1, userId("staff01"), null,
                LocalDateTime.of(2026, 10, 10, 9, 0), null);
        long newest = insertHistoricalRow(supplierId, "RESUME", "APPROVED", 2, userId("staff01"),
                userId("auditor01"), LocalDateTime.of(2026, 10, 12, 9, 0), "同意恢复。");
        long middle = insertHistoricalRow(supplierId, "ELIMINATE", "REJECTED", 2, 987631L, null,
                LocalDateTime.of(2026, 10, 11, 9, 0), null);
        // 逻辑删除行必须从列表隐身（@TableLogic）
        long deleted = insertHistoricalRow(supplierId, "ELIMINATE", "PENDING", 1, userId("staff01"),
                null, LocalDateTime.of(2026, 10, 13, 9, 0), null);
        jdbcTemplate.update("UPDATE lifecycle_request SET deleted = 1 WHERE id = ?", deleted);

        int auditBefore = auditCount(supplierId);
        String json = listViaApi(auditorToken, supplierId);
        assertThat(codeOf(json)).isZero();
        JsonNode rows = dataOf(json);

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).get("id").asLong()).isEqualTo(newest);       // 2026-10-12
        assertThat(rows.get(1).get("id").asLong()).isEqualTo(middle);       // 2026-10-11
        assertThat(rows.get(2).get("status").asText()).isEqualTo("PENDING"); // 2026-10-10
        assertThat(rows.get(0).fieldNames()).toIterable().containsExactlyInAnyOrder(A3_FIELDS);
        assertThat(rows.get(0).get("version").asInt()).isEqualTo(2);        // §2.6 入参取值链
        assertThat(rows.get(0).get("applied_by_name").asText()).isEqualTo("张三");
        assertThat(rows.get(0).get("decided_by_name").asText()).isEqualTo("李四");
        assertThat(rows.get(0).get("decision_comment").asText()).isEqualTo("同意恢复。");
        assertThat(rows.get(0).get("applied_at").asText()).startsWith("2026-10-12T09:00:00");
        // 申请人查不到（历史脏数据）→ 空串而非 null / 500
        assertThat(rows.get(1).get("applied_by").asLong()).isEqualTo(987631L);
        assertThat(rows.get(1).get("applied_by_name").asText()).isEmpty();
        assertThat(rows.get(2).get("decided_by").isNull()).isTrue();

        // 只读铁律（契约 §2.5 L493）：零审计、零写库
        assertThat(auditCount(supplierId)).isEqualTo(auditBefore);
        assertThat(physicalRequest(newest).get("STATUS")).isEqualTo("APPROVED");
        assertThat(physicalDeleted(deleted)).isEqualTo(1);
    }

    @Test
    void listIgnoresPagingQueryParamsBecauseContractDefinesFullHistory() throws Exception {
        // 契约 §2.5 明文"无 query 参数、返回该供应商全部申请历史"，本接口不做分页（非 §1.3 列表族）
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = promoteToNormal(staffToken, auditorToken, "无分页口径试验场");
        createRequest(staffToken, supplierId, "SUSPEND", "在途停用单。");
        createRequest(staffToken, supplierId, "ELIMINATE", "在途淘汰单。");

        String json = mockMvc.perform(get("/api/v1/suppliers/" + supplierId + "/lifecycle-requests")
                        .param("page", "1").param("page_size", "1")
                        .header("Authorization", "Bearer " + auditorToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(dataOf(json)).hasSize(2);   // 传了分页参数也不截断
    }

    @Test
    void listReturnsEmptyArrayForSupplierWithoutHistoryAndRejectsUnknownSupplier() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        long supplierId = createSupplierViaApi(staffToken, "无历史供应商");

        String json = listViaApi(staffToken, supplierId);
        assertThat(codeOf(json)).isZero();
        assertThat(dataOf(json)).isEmpty();

        String missing = listViaApi(staffToken, 987633L);
        assertThat(codeOf(missing)).isEqualTo(40401);
        assertThat(messageOf(missing)).isEqualTo(MSG_SUPPLIER_NOT_FOUND);
    }

    @Test
    void listIsReadableByAllThreeRolesAndRejectsAnonymous() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = promoteToNormal(staffToken, auditorToken, "只读角色闸试验场");
        createRequest(staffToken, supplierId, "SUSPEND", "在途停用单。");

        for (String token : List.of(staffToken, auditorToken, loginToken("admin", "Admin@123"))) {
            mockMvc.perform(get("/api/v1/suppliers/" + supplierId + "/lifecycle-requests")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data[0].type").value("SUSPEND"));
        }
        mockMvc.perform(get("/api/v1/suppliers/" + supplierId + "/lifecycle-requests"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    // ==================== §2.6 终审：APPROVE 联动主表 ====================

    @Test
    void approveSuspendFlipsSupplierToSuspendedAndIncrementsBothEntityVersions() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = promoteToNormal(staffToken, auditorToken, "停用终审试验场");
        int supplierVersionBefore = supplierVersion(supplierId);
        long requestId = createRequest(staffToken, supplierId, "SUSPEND", "连续两期绩效 D 级。");

        String json = decideViaApi(auditorToken, requestId,
                "{\"decision\":\"APPROVE\",\"comment\":\"确认停用，暂停新订单分配。\",\"version\":1}");

        assertThat(codeOf(json)).isZero();
        JsonNode data = dataOf(json);
        assertThat(data.fieldNames()).toIterable().containsExactlyInAnyOrder(A3_FIELDS);
        assertThat(data.get("status").asText()).isEqualTo("APPROVED");
        assertThat(data.get("version").asInt()).isEqualTo(2);            // 申请单 @Version 真自增
        assertThat(data.get("decided_by").asLong()).isEqualTo(userId("auditor01"));
        assertThat(data.get("decided_by_name").asText()).isEqualTo("李四");
        assertThat(data.get("decided_at").asText()).isNotBlank();
        assertThat(data.get("decision_comment").asText()).isEqualTo("确认停用，暂停新订单分配。");

        // 同事务联动供应商主表：状态迁移 + version 同步 +1
        assertThat(supplierStatus(supplierId)).isEqualTo("SUSPENDED");
        assertThat(supplierVersion(supplierId)).isEqualTo(supplierVersionBefore + 1);

        Map<String, Object> row = physicalRequest(requestId);
        assertThat(row.get("STATUS")).isEqualTo("APPROVED");
        assertThat(row.get("VERSION")).isEqualTo(2);
        assertThat(row.get("ACTIVE_KEY")).isNull();                      // 已决即释放占位
        assertThat(row.get("DECIDED_BY")).isEqualTo(userId("auditor01"));
        assertThat(row.get("DECISION_COMMENT")).isEqualTo("确认停用，暂停新订单分配。");

        List<Map<String, Object>> logs = auditRows(requestId);
        assertThat(logs).hasSize(2);                                     // SUBMIT + DECISION_APPROVE
        Map<String, Object> decision = logs.stream()
                .filter(l -> "DECISION_APPROVE".equals(l.get("ACTION"))).findFirst().orElseThrow();
        assertThat(decision.get("SUPPLIER_ID")).isEqualTo(supplierId);
        assertThat(decision.get("OPERATOR_ROLE")).isEqualTo("AUDITOR");
        assertThat(decision.get("OPERATOR_ID")).isEqualTo(userId("auditor01"));
        assertThat(decision.get("OLD_STATUS")).isEqualTo("PENDING");
        assertThat(decision.get("NEW_STATUS")).isEqualTo("APPROVED");
        assertThat(decision.get("COMMENT")).isEqualTo("确认停用，暂停新订单分配。");
    }

    @Test
    void approveResumeFlipsSupplierBackToNormal() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = promoteToNormal(staffToken, auditorToken, "恢复终审试验场");

        long suspendRequest = createRequest(staffToken, supplierId, "SUSPEND", "绩效连续 D 级。");
        assertThat(codeOf(decideViaApi(auditorToken, suspendRequest,
                "{\"decision\":\"APPROVE\",\"version\":1}"))).isZero();
        assertThat(supplierStatus(supplierId)).isEqualTo("SUSPENDED");

        long resumeRequest = createRequest(staffToken, supplierId, "RESUME", "整改完成，申请恢复合作。");
        String json = decideViaApi(auditorToken, resumeRequest,
                "{\"decision\":\"APPROVE\",\"comment\":\"确认整改完成，同意恢复合作。\",\"version\":1}");

        assertThat(codeOf(json)).isZero();
        assertThat(supplierStatus(supplierId)).isEqualTo("NORMAL");
        assertThat(dataOf(json).get("status").asText()).isEqualTo("APPROVED");
        assertThat(dataOf(json).get("version").asInt()).isEqualTo(2);
    }

    @Test
    void approveEliminateFlipsSupplierToEliminatedAndBlocksAnyFurtherRequest() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = promoteToNormal(staffToken, auditorToken, "淘汰终审试验场");
        long requestId = createRequest(staffToken, supplierId, "ELIMINATE", "资不抵债，依据法院破产裁定。");

        String json = decideViaApi(auditorToken, requestId,
                "{\"decision\":\"APPROVE\",\"comment\":\"同意淘汰。\",\"version\":1}");

        assertThat(codeOf(json)).isZero();
        assertThat(supplierStatus(supplierId)).isEqualTo("ELIMINATED");
        assertThat(physicalRequest(requestId).get("ACTIVE_KEY")).isNull();

        assertThat(codeOf(createViaApi(staffToken, supplierId,
                "{\"type\":\"RESUME\",\"reason\":\"被淘汰后想复活。\"}"))).isEqualTo(40302);
    }

    // ==================== §2.6 终审：REJECT 只改申请单 ====================

    @Test
    void rejectLeavesSupplierStatusAndVersionUntouchedButWritesDecisionTrace() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = promoteToNormal(staffToken, auditorToken, "驳回冻结主表试验场");
        int supplierVersionBefore = supplierVersion(supplierId);
        long requestId = createRequest(staffToken, supplierId, "SUSPEND", "停用理由存疑。");

        String json = decideViaApi(auditorToken, requestId,
                "{\"decision\":\"REJECT\",\"comment\":\"停用理由不充分，继续观察一期。\",\"version\":1}");

        assertThat(codeOf(json)).isZero();
        assertThat(dataOf(json).get("status").asText()).isEqualTo("REJECTED");
        assertThat(dataOf(json).get("version").asInt()).isEqualTo(2);
        assertThat(dataOf(json).get("decision_comment").asText()).isEqualTo("停用理由不充分，继续观察一期。");

        // acceptance §1.3：驳回不动供应商状态，也不占供应商乐观锁版本
        assertThat(supplierStatus(supplierId)).isEqualTo("NORMAL");
        assertThat(supplierVersion(supplierId)).isEqualTo(supplierVersionBefore);
        assertThat(physicalRequest(requestId).get("ACTIVE_KEY")).isNull();

        Map<String, Object> decision = auditRows(requestId).stream()
                .filter(l -> "DECISION_REJECT".equals(l.get("ACTION"))).findFirst().orElseThrow();
        assertThat(decision.get("OLD_STATUS")).isEqualTo("PENDING");
        assertThat(decision.get("NEW_STATUS")).isEqualTo("REJECTED");
        assertThat(decision.get("SUPPLIER_ID")).isEqualTo(supplierId);
    }

    @Test
    void rejectWithoutCommentIsAcceptedBecauseContractMarksCommentOptional() throws Exception {
        // 契约 §2.6 字段表 comment 必填=否：本域不得套用 §2.2/§2.3 的"驳回必须给理由"
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = promoteToNormal(staffToken, auditorToken, "意见选填试验场");
        long requestId = createRequest(staffToken, supplierId, "ELIMINATE", "拟淘汰。");

        assertThat(codeOf(decideViaApi(auditorToken, requestId,
                "{\"decision\":\"REJECT\",\"version\":1}"))).isZero();

        Map<String, Object> row = physicalRequest(requestId);
        assertThat(row.get("STATUS")).isEqualTo("REJECTED");
        assertThat(row.get("DECISION_COMMENT")).isNull();
        assertThat(row.get("DECIDED_BY")).isEqualTo(userId("auditor01"));
        assertThat(row.get("DECIDED_AT")).isNotNull();
        assertThat(supplierStatus(supplierId)).isEqualTo("NORMAL");
    }

    // ==================== §2.6 终审：并发与闸门 ====================

    @Test
    void decisionWithStaleVersionReturns40901AndFreezesEverything() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = promoteToNormal(staffToken, auditorToken, "脏版本防御场");
        long requestId = createRequest(staffToken, supplierId, "SUSPEND", "停用申请。");

        String json = decideViaApi(auditorToken, requestId,
                "{\"decision\":\"APPROVE\",\"comment\":\"拿旧版本试一下。\",\"version\":99}");
        assertThat(codeOf(json)).isEqualTo(40901);
        assertThat(messageOf(json)).isEqualTo(MSG_VERSION_CONFLICT);

        assertThat(physicalRequestStatus(requestId)).isEqualTo("PENDING");
        assertThat(physicalRequest(requestId).get("VERSION")).isEqualTo(1);
        assertThat(physicalRequest(requestId).get("DECIDED_BY")).isNull();
        assertThat(supplierStatus(supplierId)).isEqualTo("NORMAL");
        assertThat(auditRows(requestId)).hasSize(1);   // 只有 SUBMIT 那一笔
    }

    @Test
    void decisionWithoutVersionReturns40901Not40001() throws Exception {
        // 契约 §2.6 L512 + api-spec §3 L318「不回传或版本不匹配 → 40901」；L552 矩阵写 40001 属自相矛盾，
        // 按契约文档 L950「口径冲突以 api-spec 为权威」落地（差异已在会话留痕上报待裁决）
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = promoteToNormal(staffToken, auditorToken, "缺版本防御场");
        long requestId = createRequest(staffToken, supplierId, "SUSPEND", "停用申请。");

        assertThat(codeOf(decideViaApi(auditorToken, requestId, "{\"decision\":\"APPROVE\"}")))
                .isEqualTo(40901);
        assertThat(physicalRequestStatus(requestId)).isEqualTo("PENDING");
        assertThat(supplierStatus(supplierId)).isEqualTo("NORMAL");
    }

    @Test
    void decisionWithNonPositiveVersionReturns40001AtInputLayer() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = promoteToNormal(staffToken, auditorToken, "版本格式防御场");
        long requestId = createRequest(staffToken, supplierId, "SUSPEND", "停用申请。");

        assertThat(codeOf(decideViaApi(auditorToken, requestId, "{\"decision\":\"APPROVE\",\"version\":0}")))
                .isEqualTo(40001);
        assertThat(codeOf(decideViaApi(auditorToken, requestId, "{\"decision\":\"APPROVE\",\"version\":-5}")))
                .isEqualTo(40001);
        assertThat(physicalRequestStatus(requestId)).isEqualTo("PENDING");
    }

    @Test
    void decisionOnAlreadyDecidedRequestReturns40302AndFreezesBothRows() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = promoteToNormal(staffToken, auditorToken, "重复审防御场");
        long requestId = createRequest(staffToken, supplierId, "SUSPEND", "停用申请。");
        assertThat(codeOf(decideViaApi(auditorToken, requestId, "{\"decision\":\"APPROVE\",\"version\":1}")))
                .isZero();
        int supplierVersionAfter = supplierVersion(supplierId);

        String twice = decideViaApi(auditorToken, requestId, "{\"decision\":\"REJECT\",\"version\":2}");
        assertThat(codeOf(twice)).isEqualTo(40302);
        assertThat(messageOf(twice)).isEqualTo(MSG_STATUS_NOT_ALLOWED);

        assertThat(physicalRequestStatus(requestId)).isEqualTo("APPROVED");
        assertThat(supplierStatus(supplierId)).isEqualTo("SUSPENDED");
        assertThat(supplierVersion(supplierId)).isEqualTo(supplierVersionAfter);
        assertThat(auditRows(requestId)).hasSize(2);
    }

    @Test
    void decisionApproveReJudgesThresholdWhenSupplierWasConcurrentlyMovedAndKeepsRequestPending()
            throws Exception {
        // 契约 §2.6 L554「供应商已被并发淘汰 → 40302」：建单到终审之间状态变了就必须在改库前拦下，
        // 且申请单不能被"半改"成 APPROVED（无副作用回滚物证）
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = promoteToNormal(staffToken, auditorToken, "并发流转防御场");
        long requestId = createRequest(staffToken, supplierId, "SUSPEND", "停用申请。");

        flipSupplierStatus(supplierId, "ELIMINATED");   // 模拟并发改判

        String json = decideViaApi(auditorToken, requestId, "{\"decision\":\"APPROVE\",\"version\":1}");
        assertThat(codeOf(json)).isEqualTo(40302);
        assertThat(physicalRequestStatus(requestId)).isEqualTo("PENDING");
        assertThat(physicalRequest(requestId).get("DECIDED_BY")).isNull();
        assertThat(supplierStatus(supplierId)).isEqualTo("ELIMINATED");
        assertThat(auditRows(requestId)).hasSize(1);
    }

    @Test
    void decisionOnMissingOrLogicallyDeletedRequestReturns40401() throws Exception {
        String auditorToken = loginToken("auditor01", "Auditor@123");

        String json = decideViaApi(auditorToken, 987635L, "{\"decision\":\"APPROVE\",\"version\":1}");
        assertThat(codeOf(json)).isEqualTo(40401);
        assertThat(messageOf(json)).isEqualTo(MSG_REQUEST_NOT_FOUND);

        String staffToken = loginToken("staff01", "Staff@123");
        long supplierId = promoteToNormal(staffToken, auditorToken, "逻辑删除隐身场");
        long requestId = createRequest(staffToken, supplierId, "SUSPEND", "停用申请。");
        jdbcTemplate.update("UPDATE lifecycle_request SET deleted = 1 WHERE id = ?", requestId);

        assertThat(codeOf(decideViaApi(auditorToken, requestId, "{\"decision\":\"APPROVE\",\"version\":1}")))
                .isEqualTo(40401);
        assertThat(supplierStatus(supplierId)).isEqualTo("NORMAL");
    }

    @Test
    void decisionRejectsIllegalDecisionCodeWith40001AndNeverWrites() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = promoteToNormal(staffToken, auditorToken, "决策码值防御场");
        long requestId = createRequest(staffToken, supplierId, "SUSPEND", "停用申请。");

        assertThat(codeOf(decideViaApi(auditorToken, requestId, "{\"decision\":\"PASS\",\"version\":1}")))
                .isEqualTo(40001);
        assertThat(codeOf(decideViaApi(auditorToken, requestId, "{\"decision\":\"approve\",\"version\":1}")))
                .isEqualTo(40001);
        assertThat(codeOf(decideViaApi(auditorToken, requestId, "{\"version\":1}"))).isEqualTo(40001);
        assertThat(physicalRequestStatus(requestId)).isEqualTo("PENDING");
    }

    @Test
    void decisionRoleGatesRejectStaffAdminAndAnonymous() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = promoteToNormal(staffToken, auditorToken, "决策角色闸防御场");
        long requestId = createRequest(staffToken, supplierId, "SUSPEND", "停用申请。");
        String body = "{\"decision\":\"APPROVE\",\"version\":1}";

        mockMvc.perform(post("/api/v1/lifecycle-requests/" + requestId + "/decision")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
        mockMvc.perform(post("/api/v1/lifecycle-requests/" + requestId + "/decision")
                        .header("Authorization", "Bearer " + loginToken("admin", "Admin@123"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
        mockMvc.perform(post("/api/v1/lifecycle-requests/" + requestId + "/decision")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));

        assertThat(physicalRequestStatus(requestId)).isEqualTo("PENDING");
        assertThat(supplierStatus(supplierId)).isEqualTo("NORMAL");
        assertThat(auditRows(requestId)).hasSize(1);
    }

    @Test
    void auditLogNeverContainsBareDecisionCodeForLifecycleDomain() throws Exception {
        // 反向闸：跑完一轮完整生命周期后，审计表里不允许出现 action=APPROVE/REJECT 这类裸入参码
        String staffToken = loginToken("staff01", "Staff@123");
        String otherStaff = "lifecycle.staff." + TAX_SEQ.get();
        insertStaff(otherStaff, "Staff@123", "张三");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = promoteToNormal(loginToken(otherStaff, "Staff@123"), auditorToken, "裸码审计防御场");
        long approved = createRequest(loginToken(otherStaff, "Staff@123"), supplierId, "SUSPEND", "停用。");
        assertThat(codeOf(decideViaApi(auditorToken, approved, "{\"decision\":\"APPROVE\",\"version\":1}")))
                .isZero();
        long rejected = createRequest(loginToken(otherStaff, "Staff@123"), supplierId, "RESUME", "恢复。");
        assertThat(codeOf(decideViaApi(auditorToken, rejected, "{\"decision\":\"REJECT\",\"version\":1}")))
                .isZero();

        Integer bare = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'LIFECYCLE_REQUEST'"
                        + " AND action IN ('APPROVE', 'REJECT', 'PASS', 'DECISION', 'approve')",
                Integer.class);
        assertThat(bare).isZero();

        List<String> actions = jdbcTemplate.queryForList(
                "SELECT action FROM audit_log WHERE entity_type = 'LIFECYCLE_REQUEST' AND supplier_id = ?",
                String.class, supplierId);
        assertThat(actions).containsExactlyInAnyOrder("SUBMIT", "DECISION_APPROVE", "SUBMIT", "DECISION_REJECT");
    }

    @Test
    void nonCreatorStaffIsRejectedWith40302BecauseOnlyCreatorMayApply() throws Exception {
        // §2.4 仅建档人可提（用户裁决 20261015，契约 §2.4 L440 + api-spec §3 L324）：依据 §0 规则 B
        // 公有资产区对非建档人只读——他人建档的 NORMAL/SUSPENDED 供应商，SUSPEND/RESUME/ELIMINATE
        // 一律 40302「仅限本人操作」；归属闸先于状态门槛与在途预检，建档人本人提单不受影响
        String ownerToken = loginToken("staff01", "Staff@123");
        insertStaff("lifecycle.noncreator.staff", "NonCreator@123", "王二");
        String otherToken = loginToken("lifecycle.noncreator.staff", "NonCreator@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = promoteToNormal(ownerToken, auditorToken, "非建档人禁提试验场");

        // NORMAL：SUSPEND/ELIMINATE 的状态门槛本会放行，仍必须先被归属闸拦下（文案自证判序）
        String suspend = createViaApi(otherToken, supplierId, "{\"type\":\"SUSPEND\",\"reason\":\"协同提停用。\"}");
        assertThat(codeOf(suspend)).isEqualTo(40302);
        assertThat(messageOf(suspend)).isEqualTo(MSG_SELF_ONLY);
        String eliminateOnNormal = createViaApi(otherToken, supplierId,
                "{\"type\":\"ELIMINATE\",\"reason\":\"协同提淘汰。\"}");
        assertThat(codeOf(eliminateOnNormal)).isEqualTo(40302);
        assertThat(messageOf(eliminateOnNormal)).isEqualTo(MSG_SELF_ONLY);
        // NORMAL 上 RESUME 属"双非"（归属+门槛都不过），归属闸先行 → 文案必须是「仅限本人操作」
        assertThat(messageOf(createViaApi(otherToken, supplierId,
                "{\"type\":\"RESUME\",\"reason\":\"越权探测状态门槛。\"}"))).isEqualTo(MSG_SELF_ONLY);

        flipSupplierStatus(supplierId, "SUSPENDED");
        String resume = createViaApi(otherToken, supplierId, "{\"type\":\"RESUME\",\"reason\":\"协同提恢复。\"}");
        assertThat(codeOf(resume)).isEqualTo(40302);
        assertThat(messageOf(resume)).isEqualTo(MSG_SELF_ONLY);
        String eliminateOnSuspended = createViaApi(otherToken, supplierId,
                "{\"type\":\"ELIMINATE\",\"reason\":\"停用区协同淘汰。\"}");
        assertThat(codeOf(eliminateOnSuspended)).isEqualTo(40302);
        assertThat(messageOf(eliminateOnSuspended)).isEqualTo(MSG_SELF_ONLY);

        // 被拒路径零留痕：五连拒既不建单也不落审计
        assertThat(physicalTotalCount(supplierId)).isZero();
        assertThat(auditCount(supplierId)).isZero();
        assertThat(supplierStatus(supplierId)).isEqualTo("SUSPENDED");

        // 建档人本人照常成功建 PENDING 单（收紧只拦他人，不伤 §2.4 主流程；applied_by 记本人）
        long requestId = createRequest(ownerToken, supplierId, "RESUME", "整改完成，本人提出恢复合作。");
        assertThat(physicalRequest(requestId).get("STATUS")).isEqualTo("PENDING");
        assertThat(physicalRequest(requestId).get("APPLIED_BY")).isEqualTo(userId("staff01"));
        assertThat(auditRows(requestId).get(0).get("OPERATOR_ID")).isEqualTo(userId("staff01"));
    }

    // ==================== 造数辅助 ====================

    private long firstRequestIdOf(long supplierId) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT MIN(id) FROM lifecycle_request WHERE supplier_id = ?", Long.class, supplierId);
        assertThat(id).isNotNull();
        return id;
    }

    /** 直插历史申请行（造数：applied_at/决策态无法全部经 API 铺出，用于锁定 §2.5 真实行序） */
    private long insertHistoricalRow(long supplierId, String type, String status, int version,
                                     Long appliedBy, Long decidedBy, LocalDateTime appliedAt,
                                     String decisionComment) {
        jdbcTemplate.update(
                "INSERT INTO lifecycle_request (supplier_id, type, reason, status, version, applied_by,"
                        + " applied_at, decided_by, decided_at, decision_comment, created_at, updated_at,"
                        + " deleted) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                supplierId, type, "历史申请：" + type, status, version, appliedBy, appliedAt,
                decidedBy, decidedBy == null ? null : appliedAt.plusDays(1), decisionComment,
                appliedAt, appliedAt, 0);
        Long id = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM lifecycle_request WHERE supplier_id = ?", Long.class, supplierId);
        assertThat(id).isNotNull();
        return id;
    }
}
