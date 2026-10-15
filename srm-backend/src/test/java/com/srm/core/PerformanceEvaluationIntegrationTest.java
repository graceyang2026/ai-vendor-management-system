package com.srm.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.srm.core.calculator.MockDataMetricAdapter;
import com.srm.core.common.enums.Role;
import com.srm.core.dto.performance.PerformanceFactRecord;
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

import java.math.BigDecimal;
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
 * 模块三·绩效评价五接口 —— 真实数据库集成测试（H2，含生成列 active_period_key 与
 * 唯一索引 uk_supplier_active_period，见 schema-h2.sql L142-176）。
 * 契约依据：docs/接口契约文档-供应商与绩效-20261013.md §3.1–§3.5 + 附录 A4（L902-955）+ §0 通用约定；
 * docs/api-spec.md §4（算分公式与五接口）与 §7（适配器命名）；docs/acceptance-criteria.md §2/§3/§4。
 *
 * <h2>端到端只锁"库内真实形态"，不接受 mock 替身给出的假绿</h2>
 * <ul>
 *   <li><b>防篡改三例（验收 §2.1/§2.2/§2.3）</b>：请求体夹带 total_score/grade/quality_exempt 一律被
 *       Jackson 静默丢弃，落库仍是算分引擎的值；mode=mock 时入站 fact_record 被忽略且不报错；
 *       qc_total_batches=0 → Q=100 + quality_exempt=true，total_batches=0 → D=100 <b>复用同一枚标识</b>，
 *       两枚分母同时为 0 也不炸（DECIMAL(5,2) 里物理存着 100.00 而非 NULL/NaN）；</li>
 *   <li><b>出站形态即契约</b>：响应 16 字段（A4.2）一个不多一个不少，fact_record 恰 8 字段（A4.1，
 *       物理无分数）、result 恰 9 字段（A4.3）；active_period_key / deleted 两列永不外泄；</li>
 *   <li><b>在途唯一双保险</b>：应用层预检 40902 + 绕过应用层直插第二条同周期在途单被
 *       uk_supplier_active_period 拒收（DuplicateKeyException 物证）；归档/逻辑删除让占位自动释放
 *       （active_period_key 置 NULL）——DDL L133 明文口径；</li>
 *   <li><b>@Version 真实自增</b>：§3.2 重提与 §3.3 复核成功后库内 version 各自 +1，
 *       脏版本/缺版本 → 40901 且状态、复核痕迹、审计三者全冻结；</li>
 *   <li><b>归档联动</b>：APPROVE 同事务回写 supplier.latest_performance_grade / risk_warning
 *       并各自 +1 供应商 version，但<b>绝不改供应商状态</b>（验收 §2.4：D 级也只打标，停用须走 §2.4/§2.6）；</li>
 *   <li><b>REJECT 沿用同一条记录</b>（验收 §2.6）：id 不变、库内该周期仍只有一行；</li>
 *   <li><b>§0 行级隔离 + §4 权限</b>：STAFF 只见本人创建的单（后端下推 created_by，非前端过滤），
 *       AUDITOR/ADMIN 全量；AUDITOR/ADMIN 发起评价与 STAFF 自审一律 403/40301；</li>
 *   <li><b>审计全量留痕</b>：POST 与 PUT 重提都写 action=SUBMIT（用户 2026-10-15 16:42 拍板 B2=甲），
 *       复核写 REVIEW_APPROVE/REVIEW_REJECT，四条链路逐字段对账，entity_type 恒为
 *       PERFORMANCE_EVALUATION（与资质域一码多域，靠 entity_type 区分）。</li>
 * </ul>
 *
 * 取数一律经 JdbcTemplate 绕过 {@code @TableLogic} 直查物理列。税号段 1100+，与既有
 * 300+/400+/500+/600+/700+/900+ 六个集成测试错开（共享上下文库不撞 uk_tax_no_active）。
 */
@SpringBootTest(properties = "srm.seed-demo-users=true")
@AutoConfigureMockMvc
class PerformanceEvaluationIntegrationTest {

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
    private com.srm.core.mapper.PerformanceEvaluationMapper evaluationMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private static final AtomicInteger TAX_SEQ = new AtomicInteger(1100);
    private static final AtomicInteger STAFF_SEQ = new AtomicInteger(70);

    /** 契约 §3.1 样例事实 → 公式实算 Q92.50 / D87.50 / P91.60 / S90.00 / C100.00 → 总分 91.45（A） */
    private static final String SAMPLE_FACTS =
            "{\"total_batches\":40,\"delayed_batches\":3,\"avg_delay_days\":2.5,"
                    + "\"qc_total_batches\":20,\"qc_failed_batches\":1,\"major_accidents\":0,"
                    + "\"price_deviation_rate\":4.2,\"complaint_overtime_count\":1}";

    private static final String MSG_SUPPLIER_NOT_FOUND = "供应商不存在";
    private static final String MSG_EVALUATION_NOT_FOUND = "评价记录不存在";
    private static final String MSG_STATUS_NOT_ALLOWED = "当前状态不允许该操作";
    private static final String MSG_SELF_ONLY = "仅限本人操作";
    private static final String MSG_VERSION_CONFLICT = "数据已被修改，请刷新后重试";
    private static final String MSG_IN_FLIGHT_EXISTS = "该供应商同期已有在途评价单";
    private static final String MSG_FACT_INVALID = "事实数据校验失败";
    private static final String MSG_PARAM_INVALID = "参数校验失败";
    private static final String MSG_REJECT_COMMENT = "驳回时必须填写意见";

    /** 附录 A4.2 出站 16 字段（顺序无关，集合必须完全一致） */
    private static final String[] RESPONSE_FIELDS = {
            "id", "supplier_id", "period_start", "period_end", "mode", "fact_record", "result",
            "status", "version", "created_by", "created_by_name", "created_at", "updated_at",
            "reviewed_by", "reviewed_at", "review_comment"
    };

    /** 附录 A4.1 入站 8 字段：物理上不存在任何 score/grade/exempt/risk 字段（防篡改硬约束） */
    private static final String[] FACT_RECORD_FIELDS = {
            "total_batches", "delayed_batches", "avg_delay_days", "qc_total_batches",
            "qc_failed_batches", "major_accidents", "price_deviation_rate", "complaint_overtime_count"
    };

    /** 附录 A4.3 算分结果 9 字段 */
    private static final String[] RESULT_FIELDS = {
            "score_quality", "score_delivery", "score_price", "score_service", "score_compliance",
            "total_score", "grade", "quality_exempt", "risk_warning"
    };

    /** 六个互不重叠的考核周期，供"同一供应商多张单"用例取用，避免撞 uk_supplier_active_period */
    private static final String[][] PERIODS = {
            {"2026-07-01", "2026-09-30"},
            {"2026-04-01", "2026-06-30"},
            {"2026-01-01", "2026-03-31"},
            {"2025-10-01", "2025-12-31"},
            {"2025-07-01", "2025-09-30"},
            {"2025-04-01", "2025-06-30"}
    };

    // ==================== 夹具与探针 ====================

    private String nextTaxNo() {
        return String.format("913301%012d", TAX_SEQ.getAndIncrement());
    }

    private String periodStart(int index) {
        return PERIODS[index][0];
    }

    private String periodEnd(int index) {
        return PERIODS[index][1];
    }

    private String loginToken(String username, String password) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        String resp = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).get("data").get("token").asText();
    }

    private long userId(String username) {
        User u = userMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query
                .LambdaQueryWrapper<User>().eq(User::getUsername, username));
        assertThat(u).isNotNull();
        return u.getId();
    }

    /** 造第二名 STAFF，用于 §0 行级隔离与非本人重提（演示账号只有 staff01） */
    private String insertStaffAndLogin(String realName) {
        String username = "staff9%02d".formatted(STAFF_SEQ.getAndIncrement());
        User u = new User();
        u.setUsername(username);
        u.setPasswordHash(passwordEncoder.encode("Staff@123"));
        u.setRealName(realName);
        u.setRole(Role.STAFF.name());
        u.setEnabled(true);
        userMapper.insert(u);
        return username;
    }

    /** 经 §1.1 建档（DRAFT 即可被评价：契约 §3.1 只要求"存在且未 ELIMINATED"） */
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

    /** 把供应商推到 NORMAL（归档联动断言要看真实存续态） */
    private long promoteToNormal(String staffToken, String auditorToken, String name) throws Exception {
        long supplierId = createSupplierViaApi(staffToken, name);
        SupplierQualification q = new SupplierQualification();
        q.setSupplierId(supplierId);
        q.setDocType("BUSINESS_LICENSE");
        q.setFileUrl("/uploads/2026/10/perf-license-" + supplierId + ".pdf");
        q.setEffectiveDate(LocalDate.of(2026, 1, 1));
        q.setExpiryDate(LocalDate.now().plusYears(2));
        q.setStatus("PENDING");
        q.setVersion(1);
        q.setCreatedAt(LocalDateTime.now());
        q.setUpdatedAt(LocalDateTime.now());
        qualificationMapper.insert(q);
        int v1 = supplierMapper.selectById(supplierId).getVersion();
        assertThat(codeOf(mockMvc.perform(post("/api/v1/suppliers/" + supplierId + "/submit")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + v1 + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())).isZero();
        int v2 = supplierMapper.selectById(supplierId).getVersion();
        assertThat(codeOf(mockMvc.perform(post("/api/v1/suppliers/" + supplierId + "/audit")
                        .header("Authorization", "Bearer " + auditorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + v2 + ",\"decision\":\"APPROVE\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())).isZero();
        assertThat(supplierMapper.selectById(supplierId).getStatus()).isEqualTo("NORMAL");
        return supplierId;
    }

    /** 种子：一枚已过期的 APPROVED 资质（C 维度唯一数据源；PENDING/REJECTED 一律不参与算分） */
    private void seedExpiredApprovedQualification(long supplierId) {
        SupplierQualification q = new SupplierQualification();
        q.setSupplierId(supplierId);
        q.setDocType("OTHER");
        q.setFileUrl("/uploads/2026/10/perf-expired-" + supplierId + ".pdf");
        q.setEffectiveDate(LocalDate.of(2023, 1, 1));
        q.setExpiryDate(LocalDate.now().minusDays(30));
        q.setStatus("APPROVED");
        q.setVersion(1);
        q.setCreatedAt(LocalDateTime.now());
        q.setUpdatedAt(LocalDateTime.now());
        qualificationMapper.insert(q);
    }

    /** 种子：一枚未过期的 REJECTED 资质——用于证明"过期但不参与"（REJECTED 不得拖 C 分） */
    private void seedExpiredRejectedQualification(long supplierId) {
        SupplierQualification q = new SupplierQualification();
        q.setSupplierId(supplierId);
        q.setDocType("OTHER");
        q.setFileUrl("/uploads/2026/10/perf-rejected-" + supplierId + ".pdf");
        q.setEffectiveDate(LocalDate.of(2023, 1, 1));
        q.setExpiryDate(LocalDate.now().minusDays(30));
        q.setStatus("REJECTED");
        q.setVersion(1);
        q.setCreatedAt(LocalDateTime.now());
        q.setUpdatedAt(LocalDateTime.now());
        qualificationMapper.insert(q);
    }

    private String createBody(long supplierId, int periodIndex, String mode, String factRecordJson) {
        StringBuilder sb = new StringBuilder("{\"supplier_id\":").append(supplierId)
                .append(",\"period_start\":\"").append(periodStart(periodIndex))
                .append("\",\"period_end\":\"").append(periodEnd(periodIndex)).append("\"");
        if (mode != null) {
            sb.append(",\"mode\":\"").append(mode).append("\"");
        }
        if (factRecordJson != null) {
            sb.append(",\"fact_record\":").append(factRecordJson);
        }
        return sb.append("}").toString();
    }

    private static String facts(int total, int delayed, String avgDelay, int qcTotal, int qcFailed,
                                int accidents, String priceDeviation, int complaintOvertime) {
        return "{\"total_batches\":" + total + ",\"delayed_batches\":" + delayed
                + ",\"avg_delay_days\":" + avgDelay + ",\"qc_total_batches\":" + qcTotal
                + ",\"qc_failed_batches\":" + qcFailed + ",\"major_accidents\":" + accidents
                + ",\"price_deviation_rate\":" + priceDeviation
                + ",\"complaint_overtime_count\":" + complaintOvertime + "}";
    }

    /** 全维度满分的干净事实（无扣分项）：C=100 时总分 100.00，C=0 时总分 90.00 */
    private static String perfectFacts() {
        return facts(10, 0, "0", 10, 0, 0, "0", 0);
    }

    private String postJson(String token, String uri, String body) throws Exception {
        return mockMvc.perform(post(uri)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
    }

    private String putUpdate(String token, long id, String body) throws Exception {
        return mockMvc.perform(put("/api/v1/performance/evaluations/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
    }

    private String getRaw(String token, String uri) throws Exception {
        return mockMvc.perform(get(uri).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
    }

    private String detailRaw(String token, long id) throws Exception {
        return getRaw(token, "/api/v1/performance/evaluations/" + id);
    }

    private String listRaw(String token, String query) throws Exception {
        return getRaw(token, "/api/v1/performance/evaluations" + query);
    }

    /** §3.1 发起评价并断言成功，返回新评价单 ID（失败即测试失败，绝不静默继续） */
    private long createEvaluation(String staffToken, long supplierId, int periodIndex,
                                 String mode, String factRecordJson) throws Exception {
        String json = postJson(staffToken, "/api/v1/performance/evaluations",
                createBody(supplierId, periodIndex, mode, factRecordJson));
        assertThat(codeOf(json)).isZero();
        return dataOf(json).get("id").asLong();
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

    /** 物理列探针：绕过 {@code @TableLogic} 与实体映射，看库内真实形态 */
    private Map<String, Object> physicalRow(long evaluationId) {
        return jdbcTemplate.queryForMap(
                "SELECT supplier_id, period_start, period_end, mode, CAST(fact_record AS VARCHAR(4000)) AS fact_raw,"
                        + " score_quality, score_delivery, score_price, score_service, score_compliance,"
                        + " total_score, grade, quality_exempt, risk_warning, status, created_by,"
                        + " reviewed_by, CAST(reviewed_at AS VARCHAR(40)) AS reviewed_at_raw, review_comment,"
                        + " CAST(created_at AS VARCHAR(40)) AS created_at_raw,"
                        + " CAST(updated_at AS VARCHAR(40)) AS updated_at_raw,"
                        + " version, deleted, active_period_key"
                        + " FROM performance_evaluation WHERE id = ?", evaluationId);
    }

    private BigDecimal physicalTotalScore(long evaluationId) {
        return (BigDecimal) physicalRow(evaluationId).get("TOTAL_SCORE");
    }

    private int physicalInt(long evaluationId, String column) {
        Object value = physicalRow(evaluationId).get(column);
        return value == null ? -1 : ((Number) value).intValue();
    }

    private String physicalActivePeriodKey(long evaluationId) {
        Object value = physicalRow(evaluationId).get("ACTIVE_PERIOD_KEY");
        return value == null ? null : value.toString();
    }

    private int physicalRowCountOfSupplier(long supplierId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM performance_evaluation WHERE supplier_id = ?",
                Integer.class, supplierId);
        return count == null ? 0 : count;
    }

    private int physicalRowCountOfPeriod(long supplierId, int periodIndex) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM performance_evaluation WHERE supplier_id = ?"
                        + " AND period_start = ? AND period_end = ?",
                Integer.class, supplierId, periodStart(periodIndex), periodEnd(periodIndex));
        return count == null ? 0 : count;
    }

    /** 绩效域按供应商聚合的留痕条数（冗余列 supplier_id 已写入才跑得通，验收 §4.4） */
    private int auditCountBySupplier(long supplierId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'PERFORMANCE_EVALUATION'"
                        + " AND supplier_id = ?", Integer.class, supplierId);
        return count == null ? 0 : count;
    }

    private List<Map<String, Object>> auditRows(long evaluationId) {
        return jdbcTemplate.queryForList(
                "SELECT entity_type, entity_id, supplier_id, operator_id, operator_role, action,"
                        + " old_status, new_status, result, comment"
                        + " FROM audit_log WHERE entity_type = 'PERFORMANCE_EVALUATION' AND entity_id = ?"
                        + " ORDER BY id", evaluationId);
    }

    private int auditCount(long evaluationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'PERFORMANCE_EVALUATION' AND entity_id = ?",
                Integer.class, evaluationId);
        return count == null ? 0 : count;
    }

    private String supplierGrade(long supplierId) {
        return jdbcTemplate.queryForObject(
                "SELECT latest_performance_grade FROM supplier WHERE id = ?", String.class, supplierId);
    }

    private Integer supplierRiskWarning(long supplierId) {
        return jdbcTemplate.queryForObject(
                "SELECT risk_warning FROM supplier WHERE id = ?", Integer.class, supplierId);
    }

    private String supplierStatus(long supplierId) {
        return jdbcTemplate.queryForObject("SELECT status FROM supplier WHERE id = ?",
                String.class, supplierId);
    }

    private int supplierVersion(long supplierId) {
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM supplier WHERE id = ?", Integer.class, supplierId);
        return version == null ? -1 : version;
    }

    private LocalDateTime supplierUpdatedAt(long supplierId) {
        return jdbcTemplate.queryForObject(
                "SELECT updated_at FROM supplier WHERE id = ?", LocalDateTime.class, supplierId);
    }

    private void flipSupplierStatus(long supplierId, String status) {
        Supplier s = supplierMapper.selectById(supplierId);
        s.setStatus(status);
        supplierMapper.updateById(s);
    }

    // ==================== 验收 §2.1 前端物理上无法提交或覆盖分数/评级 ====================

    /**
     * 请求体在顶层与 fact_record 内部同时夹带 {@code total_score}/{@code grade}/
     * {@code score_quality}/{@code quality_exempt}/{@code risk_warning} 与整个 {@code result} 对象，
     * 三重证据链锁定"分数只能由后端算"：
     * <ol>
     *   <li>出站 {@code fact_record} 恰 8 字段、{@code result} 恰 9 字段、外层恰 16 字段（A4.1/A4.3/A4.2）；</li>
     *   <li>出站分数是公式实算值（91.45/A），<b>不是</b>夹带值（99.99/S）；</li>
     *   <li>DECIMAL(5,2) 物理列同样是 91.45/A，quality_exempt/risk_warning 物理位仍为 0。</li>
     * </ol>
     */
    @Test
    void smuggledScoreFieldsAreSilentlyDroppedAndDbKeepsBackendComputedValues() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        long supplierId = createSupplierViaApi(staffToken, "绩效夹带分数试验场");
        String tampered = "{\"total_batches\":40,\"delayed_batches\":3,\"avg_delay_days\":2.5,"
                + "\"qc_total_batches\":20,\"qc_failed_batches\":1,\"major_accidents\":0,"
                + "\"price_deviation_rate\":4.2,\"complaint_overtime_count\":1,"
                + "\"score_quality\":100,\"total_score\":99.99,\"grade\":\"S\","
                + "\"quality_exempt\":false,\"risk_warning\":false}";
        String body = "{\"supplier_id\":" + supplierId
                + ",\"period_start\":\"" + periodStart(0) + "\",\"period_end\":\"" + periodEnd(0) + "\""
                + ",\"mode\":\"manual\",\"fact_record\":" + tampered
                + ",\"total_score\":99.99,\"grade\":\"S\",\"score_quality\":100"
                + ",\"result\":{\"total_score\":99.99,\"grade\":\"S\"},\"status\":\"APPROVED\",\"version\":99}";

        String json = postJson(staffToken, "/api/v1/performance/evaluations", body);

        assertThat(codeOf(json)).isZero();
        JsonNode data = dataOf(json);
        long evaluationId = data.get("id").asLong();
        assertThat(data.fieldNames()).toIterable().containsExactlyInAnyOrder(RESPONSE_FIELDS);
        assertThat(data.get("fact_record").fieldNames()).toIterable()
                .containsExactlyInAnyOrder(FACT_RECORD_FIELDS);
        assertThat(data.get("result").fieldNames()).toIterable().containsExactlyInAnyOrder(RESULT_FIELDS);
        // 夹带的 status/version 同样被丢弃：状态与版本由后端定死（提交即锁死）
        assertThat(data.get("status").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(data.get("version").asInt()).isEqualTo(1);
        assertThat(new BigDecimal(data.get("result").get("total_score").asText()))
                .isEqualByComparingTo("91.45");
        assertThat(data.get("result").get("grade").asText()).isEqualTo("A");
        assertThat(new BigDecimal(data.get("result").get("score_quality").asText()))
                .isEqualByComparingTo("92.50");
        assertThat(new BigDecimal(data.get("result").get("score_delivery").asText()))
                .isEqualByComparingTo("87.50");
        assertThat(new BigDecimal(data.get("result").get("score_price").asText()))
                .isEqualByComparingTo("91.60");
        assertThat(new BigDecimal(data.get("result").get("score_service").asText()))
                .isEqualByComparingTo("90.00");
        assertThat(new BigDecimal(data.get("result").get("score_compliance").asText()))
                .isEqualByComparingTo("100.00");
        assertThat(data.get("created_by_name").asText()).isEqualTo("张三");
        // active_period_key / deleted 属 DDL 内部列，实体不映射 → 物理上不可能出现在响应里
        assertThat(data.has("active_period_key")).isFalse();
        assertThat(data.has("deleted")).isFalse();

        assertThat(physicalTotalScore(evaluationId)).isEqualByComparingTo("91.45");
        assertThat(physicalRow(evaluationId).get("GRADE")).isEqualTo("A");
        assertThat(physicalInt(evaluationId, "QUALITY_EXEMPT")).isZero();
        assertThat(physicalInt(evaluationId, "RISK_WARNING")).isZero();
        assertThat(physicalInt(evaluationId, "VERSION")).isEqualTo(1);
        assertThat(physicalInt(evaluationId, "DELETED")).isZero();
        assertThat(physicalRow(evaluationId).get("STATUS")).isEqualTo("PENDING_REVIEW");
        // 落库的 fact_record 只有 8 枚客观字段：夹带分数在 DTO 反序列化阶段就被丢弃，没进 JSON 列
        JsonNode persistedFacts = objectMapper.readTree(
                (String) physicalRow(evaluationId).get("FACT_RAW"));
        assertThat(persistedFacts.size()).isEqualTo(8);
        assertThat(persistedFacts.has("total_score")).isFalse();
        assertThat(persistedFacts.has("grade")).isFalse();
    }

    // ==================== 验收 §2.2 mock 模式忽略入站事实且不报错 ====================

    /**
     * mode=mock 时请求体仍带一份脏 fact_record（负数、子集大于全集），必须：
     * 不报 40001、返回与落库的都是 {@code MockDataMetricAdapter} 按种子生成的演示数据。
     * 期望值由同一适配器现算（而非抄实现常量），以证明"入库的就是演示生成链的产物"。
     */
    @Test
    void mockModeIgnoresInboundFactRecordAndPersistsSeededDemoFacts() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        long supplierId = createSupplierViaApi(staffToken, "绩效演示数据试验场");
        PerformanceFactRecord expected = new MockDataMetricAdapter().fetchFacts(
                supplierId, LocalDate.parse(periodStart(0)), LocalDate.parse(periodEnd(0)), null);
        String dirty = facts(-5, 999, "-9", -1, 88, 77, "-100", 12345);

        long evaluationId = createEvaluation(staffToken, supplierId, 0, "mock", dirty);

        JsonNode data = dataOf(detailRaw(staffToken, evaluationId));
        assertThat(data.get("mode").asText()).isEqualTo("mock");
        assertThat(data.get("fact_record").get("total_batches").asInt())
                .isEqualTo(expected.getTotalBatches());
        assertThat(data.get("fact_record").get("qc_total_batches").asInt())
                .isEqualTo(expected.getQcTotalBatches());
        assertThat(data.get("fact_record").get("qc_failed_batches").asInt())
                .isEqualTo(expected.getQcFailedBatches());
        assertThat(data.get("fact_record").get("delayed_batches").asInt())
                .isEqualTo(expected.getDelayedBatches());
        // 演示数据绝不能是入参脏值：负批次、子集大于全集都没进库
        assertThat(data.get("fact_record").get("total_batches").asInt()).isPositive();
        assertThat(data.get("fact_record").get("qc_failed_batches").asInt())
                .isLessThanOrEqualTo(data.get("fact_record").get("qc_total_batches").asInt());

        String persisted = (String) physicalRow(evaluationId).get("FACT_RAW");
        JsonNode persistedFacts = objectMapper.readTree(persisted);
        assertThat(persistedFacts.get("price_deviation_rate").decimalValue())
                .isEqualByComparingTo(expected.getPriceDeviationRate());
        assertThat(persistedFacts.get("complaint_overtime_count").asInt())
                .isEqualTo(expected.getComplaintOvertimeCount());
        // 演示事实同样要过算分引擎：库内分数与五维加权和自洽
        JsonNode result = data.get("result");
        assertThat(new BigDecimal(result.get("total_score").asText()))
                .isEqualByComparingTo(weightedSumOf(result));
        assertThat(result.get("grade").asText()).isIn("A", "B", "C", "D");
        assertThat(physicalRow(evaluationId).get("MODE")).isEqualTo("mock");
    }

    /**
     * 权重 Q0.30/D0.25/P0.20/S0.15/C0.10（api-spec §4.1）：总分必须由五维分<b>复算</b>得出。
     * 复算值按 {@code DECIMAL(5,2)} 口径取 2 位 HALF_UP——这正是验收 §2.3 允许的"浮点误差范围"，
     * 断言里保留原始未取整值反而会误判成缺陷。
     */
    private static BigDecimal weightedSumOf(JsonNode result) {
        return rawWeightedSum(result).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static BigDecimal rawWeightedSum(JsonNode result) {
        return new BigDecimal(result.get("score_quality").asText()).multiply(new BigDecimal("0.30"))
                .add(new BigDecimal(result.get("score_delivery").asText()).multiply(new BigDecimal("0.25")))
                .add(new BigDecimal(result.get("score_price").asText()).multiply(new BigDecimal("0.20")))
                .add(new BigDecimal(result.get("score_service").asText()).multiply(new BigDecimal("0.15")))
                .add(new BigDecimal(result.get("score_compliance").asText()).multiply(new BigDecimal("0.10")));
    }

    // ==================== 验收 §2.3 免考硬编码，不可被参数化影响 ====================

    /** qc_total_batches=0 → Q=100 且 quality_exempt=true，其余四维仍按公式扣分（免考只封 Q 的分母） */
    @Test
    void qualityIsExemptedAtHundredWhenNoQcBatchesWithoutTouchingOtherDimensions() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        long supplierId = createSupplierViaApi(staffToken, "质量免考试验场");

        long evaluationId = createEvaluation(staffToken, supplierId, 0, "manual",
                facts(40, 3, "2.5", 0, 0, 0, "4.2", 1));

        JsonNode result = dataOf(detailRaw(staffToken, evaluationId)).get("result");
        assertThat(new BigDecimal(result.get("score_quality").asText())).isEqualByComparingTo("100.00");
        assertThat(result.get("quality_exempt").asBoolean()).isTrue();
        assertThat(new BigDecimal(result.get("score_delivery").asText())).isEqualByComparingTo("87.50");
        assertThat(new BigDecimal(result.get("score_price").asText())).isEqualByComparingTo("91.60");
        assertThat(new BigDecimal(result.get("score_service").asText())).isEqualByComparingTo("90.00");
        // 30 + 21.875 + 18.32 + 13.5 + 10 = 93.695 → 93.70
        assertThat(new BigDecimal(result.get("total_score").asText())).isEqualByComparingTo("93.70");
        assertThat(result.get("grade").asText()).isEqualTo("A");
        // 库内 DECIMAL(5,2) 与 TINYINT 布尔位的真实形态（不是 null、不是 NaN、不是异常留下的半成品）
        assertThat(physicalRow(evaluationId).get("SCORE_QUALITY"))
                .isEqualTo(new BigDecimal("100.00"));
        assertThat(physicalInt(evaluationId, "QUALITY_EXEMPT")).isEqualTo(1);
    }

    /**
     * total_batches=0（当期无交付业务而 qc_total_batches&gt;0）→ D=100，且<b>复用同一枚
     * quality_exempt 标识</b>（api-spec §4.1 L363：不新增 delivery_exempt 列），
     * delayed/total 的除零缺口被封死。
     */
    @Test
    void deliveryIsExemptedAtHundredWhenNoBatchesAndReusesTheSingleExemptionFlag() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        long supplierId = createSupplierViaApi(staffToken, "交付免考试验场");

        long evaluationId = createEvaluation(staffToken, supplierId, 0, "manual",
                facts(0, 0, "0", 20, 1, 0, "4.2", 1));

        JsonNode result = dataOf(detailRaw(staffToken, evaluationId)).get("result");
        assertThat(new BigDecimal(result.get("score_delivery").asText())).isEqualByComparingTo("100.00");
        assertThat(new BigDecimal(result.get("score_quality").asText())).isEqualByComparingTo("92.50");
        assertThat(result.get("quality_exempt").asBoolean()).isTrue();
        // 27.75 + 25.00 + 18.32 + 13.50 + 10.00 = 94.57
        assertThat(new BigDecimal(result.get("total_score").asText())).isEqualByComparingTo("94.57");
        // 物理列只有 quality_exempt 一枚免考位：H2 里若存在 delivery_exempt 列，本探针会直接报错
        assertThat(physicalRow(evaluationId).get("SCORE_DELIVERY"))
                .isEqualTo(new BigDecimal("100.00"));
        assertThat(physicalInt(evaluationId, "QUALITY_EXEMPT")).isEqualTo(1);
    }

    /** 两枚分母同时为 0（当期完全无业务）：五维全 100、总分 100.00、免考标只有一枚 */
    @Test
    void bothZeroDenominatorsExemptEveryDimensionWithoutArithmeticFailure() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        long supplierId = createSupplierViaApi(staffToken, "双零业务试验场");

        long evaluationId = createEvaluation(staffToken, supplierId, 0, "manual",
                facts(0, 0, "0", 0, 0, 0, "0", 0));

        JsonNode result = dataOf(detailRaw(staffToken, evaluationId)).get("result");
        assertThat(new BigDecimal(result.get("score_quality").asText())).isEqualByComparingTo("100.00");
        assertThat(new BigDecimal(result.get("score_delivery").asText())).isEqualByComparingTo("100.00");
        assertThat(new BigDecimal(result.get("total_score").asText())).isEqualByComparingTo("100.00");
        assertThat(result.get("grade").asText()).isEqualTo("A");
        assertThat(result.get("quality_exempt").asBoolean()).isTrue();
        assertThat(result.get("risk_warning").asBoolean()).isFalse();
    }

    // ==================== §3.1 在途唯一（应用层预检 + DB 生成列兜底） ====================

    /** 同 supplier + 同周期第二张在途单 → 40902，且库内仍只有一行、审计仍只有一条 */
    @Test
    void secondInFlightEvaluationOfSamePeriodIsRejectedWith40902() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        long supplierId = createSupplierViaApi(staffToken, "同期重复评价试验场");
        long firstId = createEvaluation(staffToken, supplierId, 0, null, SAMPLE_FACTS);

        String duplicate = postJson(staffToken, "/api/v1/performance/evaluations",
                createBody(supplierId, 0, "manual", perfectFacts()));

        assertThat(codeOf(duplicate)).isEqualTo(40902);
        assertThat(messageOf(duplicate)).isEqualTo(MSG_IN_FLIGHT_EXISTS);
        assertThat(physicalRowCountOfPeriod(supplierId, 0)).isEqualTo(1);
        // 被拒的第二张单零留痕：绩效域该供应商仍只有首单那一条 SUBMIT（SUBMIT 只在写库成功后落）
        assertThat(auditCountBySupplier(supplierId)).isEqualTo(1);
        assertThat(auditRows(firstId)).hasSize(1);
        // RETURNED 也算在途：退回后同周期再新建同样 40902
        String auditorToken = loginToken("auditor01", "Auditor@123");
        assertThat(codeOf(postJson(auditorToken, "/api/v1/performance/evaluations/" + firstId + "/review",
                "{\"decision\":\"REJECT\",\"comment\":\"事实与台账不符。\",\"version\":1}"))).isZero();
        assertThat(codeOf(postJson(staffToken, "/api/v1/performance/evaluations",
                createBody(supplierId, 0, "manual", perfectFacts())))).isEqualTo(40902);
        assertThat(physicalRowCountOfPeriod(supplierId, 0)).isEqualTo(1);
    }

    /**
     * 绕过应用层直插第二条同周期在途单必须被 DB 唯一索引拒收（并发窗口的物证），
     * 同时锁定生成列取值形态 {@code supplierId_periodStart_periodEnd}（DATE 自动转 YYYY-MM-DD）。
     */
    @Test
    void databaseGeneratedColumnRejectsSecondInFlightRowInsertedDirectly() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        long supplierId = createSupplierViaApi(staffToken, "生成列兜底试验场");
        long firstId = createEvaluation(staffToken, supplierId, 0, null, SAMPLE_FACTS);
        assertThat(physicalActivePeriodKey(firstId))
                .isEqualTo(supplierId + "_" + periodStart(0) + "_" + periodEnd(0));

        com.srm.core.entity.PerformanceEvaluation rival = new com.srm.core.entity.PerformanceEvaluation();
        rival.setSupplierId(supplierId);
        rival.setPeriodStart(LocalDate.parse(periodStart(0)));
        rival.setPeriodEnd(LocalDate.parse(periodEnd(0)));
        rival.setMode("manual");
        rival.setFactRecord(objectMapper.readValue(SAMPLE_FACTS, PerformanceFactRecord.class));
        rival.setTotalScore(new BigDecimal("91.45"));
        rival.setGrade("A");
        rival.setQualityExempt(false);
        rival.setRiskWarning(false);
        rival.setStatus("PENDING_REVIEW");
        rival.setCreatedBy(userId("staff01"));
        rival.setVersion(1);
        rival.setCreatedAt(LocalDateTime.now());
        rival.setUpdatedAt(LocalDateTime.now());
        rival.setDeleted(0);

        assertThatThrownBy(() -> evaluationMapper.insert(rival))
                .isInstanceOf(DuplicateKeyException.class);

        // 不同周期不受影响：生成列按 start+end 组合占位，历史周期可并行在途
        long otherPeriod = createEvaluation(staffToken, supplierId, 1, null, SAMPLE_FACTS);
        assertThat(physicalActivePeriodKey(otherPeriod))
                .isEqualTo(supplierId + "_" + periodStart(1) + "_" + periodEnd(1));
        assertThat(physicalRowCountOfSupplier(supplierId)).isEqualTo(2);
    }

    /**
     * DDL L133 明文：仅未删除且在途（PENDING_REVIEW/RETURNED）时占位，
     * 故 APPROVED 归档后 {@code active_period_key} 变 NULL、同周期可再次发起。
     * <b>注</b>：acceptance §2.5 第二条要求"同周期已有归档记录时 POST 也应 40902 拦截"，
     * 与本 DDL 注释、契约 §3.1 矩阵（40902 触发条件只列 PENDING_REVIEW/RETURNED）直接冲突，
     * 本用例按"DDL + 契约"锁定现状并留痕上报，不自裁。
     */
    @Test
    void archivedEvaluationReleasesGeneratedColumnPlaceholderPerDdlSemantics() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = createSupplierViaApi(staffToken, "归档让位试验场");
        long firstId = createEvaluation(staffToken, supplierId, 0, null, SAMPLE_FACTS);

        assertThat(codeOf(postJson(auditorToken, "/api/v1/performance/evaluations/" + firstId + "/review",
                "{\"decision\":\"APPROVE\",\"comment\":\"核实无误。\",\"version\":1}"))).isZero();
        assertThat(physicalRow(firstId).get("STATUS")).isEqualTo("APPROVED");
        assertThat(physicalActivePeriodKey(firstId)).isNull();

        long secondId = createEvaluation(staffToken, supplierId, 0, null, perfectFacts());
        assertThat(physicalRowCountOfPeriod(supplierId, 0)).isEqualTo(2);
        assertThat(physicalActivePeriodKey(secondId))
                .isEqualTo(supplierId + "_" + periodStart(0) + "_" + periodEnd(0));
        // 归档单永久不可改（§3.2 只允许 RETURNED）：新单是"另一张"，旧单分毫未动
        assertThat(codeOf(putUpdate(staffToken, firstId, "{\"fact_record\":" + perfectFacts() + ",\"version\":2}")))
                .isEqualTo(40302);
        assertThat(physicalRow(firstId).get("STATUS")).isEqualTo("APPROVED");
    }

    // ==================== §4 权限矩阵（40301） ====================

    /** AUDITOR 零业务写：代提评价一律 403/40301（验收 §4.3） */
    @Test
    void auditorCannotInitiateEvaluationAndNothingIsWritten() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = createSupplierViaApi(staffToken, "审核人代提评价试验场");

        mockMvc.perform(post("/api/v1/performance/evaluations")
                        .header("Authorization", "Bearer " + auditorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(supplierId, 0, "manual", SAMPLE_FACTS)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));

        assertThat(physicalRowCountOfSupplier(supplierId)).isZero();
    }

    /** ADMIN 零业务写：发起评价与复核都是 403/40301（验收 §4.2） */
    @Test
    void adminCannotWritePerformanceEndpointsAtAll() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String adminToken = loginToken("admin", "Admin@123");
        long supplierId = createSupplierViaApi(staffToken, "管理员越权试验场");

        mockMvc.perform(post("/api/v1/performance/evaluations")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(supplierId, 0, "manual", SAMPLE_FACTS)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));

        long evaluationId = createEvaluation(staffToken, supplierId, 0, null, SAMPLE_FACTS);
        mockMvc.perform(post("/api/v1/performance/evaluations/" + evaluationId + "/review")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"version\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
        assertThat(physicalRow(evaluationId).get("STATUS")).isEqualTo("PENDING_REVIEW");

        // 但 ADMIN 可读（§3.4/§3.5 允许 ALL）
        assertThat(codeOf(detailRaw(adminToken, evaluationId))).isZero();
    }

    /** STAFF 不得自审自己提交的评价：403/40301（验收 §4.1，前端隐藏按钮不算安全边界） */
    @Test
    void staffCannotReviewTheEvaluationItSubmitted() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        long supplierId = createSupplierViaApi(staffToken, "自审越权试验场");
        long evaluationId = createEvaluation(staffToken, supplierId, 0, null, SAMPLE_FACTS);

        mockMvc.perform(post("/api/v1/performance/evaluations/" + evaluationId + "/review")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"comment\":\"自评自过。\",\"version\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));

        assertThat(physicalRow(evaluationId).get("STATUS")).isEqualTo("PENDING_REVIEW");
        assertThat(physicalRow(evaluationId).get("REVIEWED_BY")).isNull();
        assertThat(auditRows(evaluationId)).hasSize(1);
    }

    // ==================== §3.2 重提闸门（40302） ====================

    /** 验收 §2.5：提交后立即锁死——PENDING_REVIEW 状态下原 STAFF 也改不了事实 */
    @Test
    void resubmitOnPendingReviewEvaluationIsRejectedWith40302() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        long supplierId = createSupplierViaApi(staffToken, "在途锁定试验场");
        long evaluationId = createEvaluation(staffToken, supplierId, 0, null, SAMPLE_FACTS);

        String json = putUpdate(staffToken, evaluationId,
                "{\"fact_record\":" + perfectFacts() + ",\"version\":1}");

        assertThat(codeOf(json)).isEqualTo(40302);
        assertThat(messageOf(json)).isEqualTo(MSG_STATUS_NOT_ALLOWED);
        assertThat(physicalTotalScore(evaluationId)).isEqualByComparingTo("91.45");
        assertThat(physicalInt(evaluationId, "VERSION")).isEqualTo(1);
        assertThat(auditRows(evaluationId)).hasSize(1);
    }

    /** 非本人重提 → 40302「仅限本人操作」（归属闸先于状态闸，口径同 §1.2） */
    @Test
    void resubmitByAnotherStaffIsRejectedWith40302AndRowUntouched() throws Exception {
        String ownerToken = loginToken("staff01", "Staff@123");
        String otherUsername = insertStaffAndLogin("赵六");
        String otherToken = loginToken(otherUsername, "Staff@123");
        long supplierId = createSupplierViaApi(ownerToken, "他人重提试验场");
        long evaluationId = createEvaluation(ownerToken, supplierId, 0, null, SAMPLE_FACTS);
        String auditorToken = loginToken("auditor01", "Auditor@123");
        assertThat(codeOf(postJson(auditorToken, "/api/v1/performance/evaluations/" + evaluationId + "/review",
                "{\"decision\":\"REJECT\",\"comment\":\"事实与台账不符。\",\"version\":1}"))).isZero();
        assertThat(physicalRow(evaluationId).get("STATUS")).isEqualTo("RETURNED");

        String json = putUpdate(otherToken, evaluationId,
                "{\"fact_record\":" + perfectFacts() + ",\"version\":2}");

        assertThat(codeOf(json)).isEqualTo(40302);
        assertThat(messageOf(json)).isEqualTo(MSG_SELF_ONLY);
        assertThat(physicalRow(evaluationId).get("STATUS")).isEqualTo("RETURNED");
        assertThat(physicalInt(evaluationId, "VERSION")).isEqualTo(2);
        assertThat(auditRows(evaluationId)).hasSize(2);
    }

    /** 已归档单不得被再次复核（状态机终态，永久不可改） */
    @Test
    void reviewOnArchivedEvaluationIsRejectedWith40302() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = createSupplierViaApi(staffToken, "重复复审试验场");
        long evaluationId = createEvaluation(staffToken, supplierId, 0, null, SAMPLE_FACTS);
        assertThat(codeOf(postJson(auditorToken, "/api/v1/performance/evaluations/" + evaluationId + "/review",
                "{\"decision\":\"APPROVE\",\"comment\":\"核实无误。\",\"version\":1}"))).isZero();

        String json = postJson(auditorToken, "/api/v1/performance/evaluations/" + evaluationId + "/review",
                "{\"decision\":\"REJECT\",\"comment\":\"再看一遍。\",\"version\":2}");

        assertThat(codeOf(json)).isEqualTo(40302);
        assertThat(messageOf(json)).isEqualTo(MSG_STATUS_NOT_ALLOWED);
        assertThat(physicalRow(evaluationId).get("STATUS")).isEqualTo("APPROVED");
        assertThat(physicalInt(evaluationId, "VERSION")).isEqualTo(2);
        assertThat(auditRows(evaluationId)).hasSize(2);
    }

    // ==================== §0/§3 40401 绩效域实回文案（用户 2026-10-15 16:42 拍板 B5） ====================

    /**
     * 绩效域 40401 分两种子文案，逐接口对账：
     * §3.1 被评 supplier_id 不存在 / 供应商已 ELIMINATED → {@code 供应商不存在}；
     * §3.2/§3.3/§3.5 评价单不存在 → {@code 评价记录不存在}。
     * 三条读写的失败路径一律零写入、零留痕。
     */
    @Test
    void missingSupplierAndMissingEvaluationEachReturnTheirOwn40401Message() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");

        String unknownSupplier = postJson(staffToken, "/api/v1/performance/evaluations",
                createBody(999999999L, 0, "manual", SAMPLE_FACTS));
        assertThat(codeOf(unknownSupplier)).isEqualTo(40401);
        assertThat(messageOf(unknownSupplier)).isEqualTo(MSG_SUPPLIER_NOT_FOUND);

        long eliminated = createSupplierViaApi(staffToken, "已淘汰供应商试验场");
        flipSupplierStatus(eliminated, "ELIMINATED");
        String onEliminated = postJson(staffToken, "/api/v1/performance/evaluations",
                createBody(eliminated, 0, "manual", SAMPLE_FACTS));
        assertThat(codeOf(onEliminated)).isEqualTo(40401);
        assertThat(messageOf(onEliminated)).isEqualTo(MSG_SUPPLIER_NOT_FOUND);
        assertThat(physicalRowCountOfSupplier(eliminated)).isZero();

        String detail = detailRaw(staffToken, 999999999L);
        assertThat(codeOf(detail)).isEqualTo(40401);
        assertThat(messageOf(detail)).isEqualTo(MSG_EVALUATION_NOT_FOUND);

        String update = putUpdate(staffToken, 999999999L,
                "{\"fact_record\":" + SAMPLE_FACTS + ",\"version\":1}");
        assertThat(codeOf(update)).isEqualTo(40401);
        assertThat(messageOf(update)).isEqualTo(MSG_EVALUATION_NOT_FOUND);

        String review = postJson(auditorToken,
                "/api/v1/performance/evaluations/999999999/review",
                "{\"decision\":\"APPROVE\",\"version\":1}");
        assertThat(codeOf(review)).isEqualTo(40401);
        assertThat(messageOf(review)).isEqualTo(MSG_EVALUATION_NOT_FOUND);
        assertThat(auditCountBySupplier(eliminated)).isZero();
    }

    // ==================== 验收 §1.8/§2.3 乐观锁三态（40901 / 入参层 40001） ====================

    /**
     * §3.3 复核的并发三态：脏版本 40901、缺版本 40901、{@code version=0} 由入参层
     * {@code @Min(1)} 折 40001。三条失败路径都必须冻结状态、复核痕迹与审计条数。
     */
    @Test
    void staleOrMissingVersionOnReviewIs40901WhileZeroVersionIsInputLayer40001() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = createSupplierViaApi(staffToken, "复核并发版本试验场");
        long evaluationId = createEvaluation(staffToken, supplierId, 0, null, SAMPLE_FACTS);

        String stale = postJson(auditorToken,
                "/api/v1/performance/evaluations/" + evaluationId + "/review",
                "{\"decision\":\"APPROVE\",\"comment\":\"陈旧版本。\",\"version\":999}");
        assertThat(codeOf(stale)).isEqualTo(40901);
        assertThat(messageOf(stale)).isEqualTo(MSG_VERSION_CONFLICT);

        String missing = postJson(auditorToken,
                "/api/v1/performance/evaluations/" + evaluationId + "/review",
                "{\"decision\":\"APPROVE\",\"comment\":\"忘了带版本。\"}");
        assertThat(codeOf(missing)).isEqualTo(40901);
        assertThat(messageOf(missing)).isEqualTo(MSG_VERSION_CONFLICT);

        String zero = postJson(auditorToken,
                "/api/v1/performance/evaluations/" + evaluationId + "/review",
                "{\"decision\":\"APPROVE\",\"version\":0}");
        assertThat(codeOf(zero)).isEqualTo(40001);

        // 全部失败后：单还是那张在途单，复核三枚痕迹仍为空，审计仍只有首提那一条
        assertThat(physicalRow(evaluationId).get("STATUS")).isEqualTo("PENDING_REVIEW");
        assertThat(physicalInt(evaluationId, "VERSION")).isEqualTo(1);
        assertThat(physicalRow(evaluationId).get("REVIEWED_BY")).isNull();
        assertThat(physicalRow(evaluationId).get("REVIEW_COMMENT")).isNull();
        assertThat(auditRows(evaluationId)).hasSize(1);
        assertThat(supplierGrade(supplierId)).isNull();
    }

    /** decision 码值非法（含缺失）由入参层 @NotBlank/@Pattern 折 40001，不得被 Service 静默当作 REJECT */
    @Test
    void illegalDecisionCodeIsRejectedWith40001WithoutAnyWrite() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = createSupplierViaApi(staffToken, "决策码值试验场");
        long evaluationId = createEvaluation(staffToken, supplierId, 0, null, SAMPLE_FACTS);

        assertThat(codeOf(postJson(auditorToken,
                "/api/v1/performance/evaluations/" + evaluationId + "/review",
                "{\"decision\":\"APPROVED\",\"version\":1}"))).isEqualTo(40001);
        assertThat(codeOf(postJson(auditorToken,
                "/api/v1/performance/evaluations/" + evaluationId + "/review",
                "{\"comment\":\"没带决策\",\"version\":1}"))).isEqualTo(40001);

        assertThat(physicalRow(evaluationId).get("STATUS")).isEqualTo("PENDING_REVIEW");
        assertThat(auditRows(evaluationId)).hasSize(1);
    }

    /** §3.2 重提的脏版本 → 40901，事实与分数一分不改 */
    @Test
    void resubmitWithStaleVersionIs40901AndFreezesFactsScoreAndAudit() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = createSupplierViaApi(staffToken, "重提并发试验场");
        long evaluationId = createEvaluation(staffToken, supplierId, 0, null, SAMPLE_FACTS);
        assertThat(codeOf(postJson(auditorToken,
                "/api/v1/performance/evaluations/" + evaluationId + "/review",
                "{\"decision\":\"REJECT\",\"comment\":\"事实与质检台账不符。\",\"version\":1}"))).isZero();

        String stale = putUpdate(staffToken, evaluationId,
                "{\"fact_record\":" + perfectFacts() + ",\"version\":1}");

        assertThat(codeOf(stale)).isEqualTo(40901);
        assertThat(messageOf(stale)).isEqualTo(MSG_VERSION_CONFLICT);
        assertThat(physicalRow(evaluationId).get("STATUS")).isEqualTo("RETURNED");
        assertThat(physicalTotalScore(evaluationId)).isEqualByComparingTo("91.45");
        assertThat(physicalInt(evaluationId, "VERSION")).isEqualTo(2);
        assertThat(auditRows(evaluationId)).hasSize(2);
    }

    // ==================== §3.1 事实与入参非法（40001） ====================

    /**
     * 手工链四条校验 + 周期语义校验全部折 40001，且<b>零建单零留痕</b>：
     * delayed&gt;total、qc_failed&gt;qc_total、缺 fact_record、周期同日、周期倒挂、
     * 形状合法但历法不存在的 2026-02-31。
     */
    @Test
    void illegalFactsAndPeriodsAreAll40001WithoutCreatingAnyRow() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        long supplierId = createSupplierViaApi(staffToken, "事实非法试验场");

        assertThat(codeOf(postJson(staffToken, "/api/v1/performance/evaluations",
                createBody(supplierId, 0, "manual", facts(40, 41, "2.5", 20, 1, 0, "4.2", 1)))))
                .isEqualTo(40001);
        assertThat(codeOf(postJson(staffToken, "/api/v1/performance/evaluations",
                createBody(supplierId, 0, "manual", facts(40, 3, "2.5", 20, 21, 0, "4.2", 1)))))
                .isEqualTo(40001);
        assertThat(messageOf(postJson(staffToken, "/api/v1/performance/evaluations",
                createBody(supplierId, 0, "manual", facts(40, 3, "2.5", 20, 21, 0, "4.2", 1)))))
                .isEqualTo(MSG_FACT_INVALID);
        assertThat(codeOf(postJson(staffToken, "/api/v1/performance/evaluations",
                createBody(supplierId, 0, "manual", facts(40, 3, "-1", 20, 1, 0, "4.2", 1)))))
                .isEqualTo(40001);
        // mode=manual 却不传事实：契约 §3.1 条件必填，同样 40001「事实数据校验失败」
        assertThat(codeOf(postJson(staffToken, "/api/v1/performance/evaluations",
                createBody(supplierId, 0, "manual", null)))).isEqualTo(40001);
        // 周期同日与倒挂
        assertThat(codeOf(postJson(staffToken, "/api/v1/performance/evaluations",
                "{\"supplier_id\":" + supplierId + ",\"period_start\":\"2026-07-01\","
                        + "\"period_end\":\"2026-07-01\",\"fact_record\":" + SAMPLE_FACTS + "}")))
                .isEqualTo(40001);
        assertThat(codeOf(postJson(staffToken, "/api/v1/performance/evaluations",
                "{\"supplier_id\":" + supplierId + ",\"period_start\":\"2026-09-30\","
                        + "\"period_end\":\"2026-07-01\",\"fact_record\":" + SAMPLE_FACTS + "}")))
                .isEqualTo(40001);
        // 形状合法（@Pattern 放行）但历法不存在 → 不得冒 50001
        assertThat(codeOf(postJson(staffToken, "/api/v1/performance/evaluations",
                "{\"supplier_id\":" + supplierId + ",\"period_start\":\"2026-02-31\","
                        + "\"period_end\":\"2026-09-30\",\"fact_record\":" + SAMPLE_FACTS + "}")))
                .isEqualTo(40001);
        // 入参层缺 supplier_id / 周期格式非法同样 40001
        assertThat(codeOf(postJson(staffToken, "/api/v1/performance/evaluations",
                "{\"period_start\":\"2026-07-01\",\"period_end\":\"2026-09-30\","
                        + "\"fact_record\":" + SAMPLE_FACTS + "}"))).isEqualTo(40001);

        assertThat(physicalRowCountOfSupplier(supplierId)).isZero();
        assertThat(auditCountBySupplier(supplierId)).isZero();
    }

    /** mode 只允许 manual/mock 两值（api-spec §7 适配器命名）：未知 mode 入参层 40001，绝不回退默认值 */
    @Test
    void modeOutsideManualAndMockIs40001AndOmittedModeDefaultsToManual() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        long supplierId = createSupplierViaApi(staffToken, "取数模式试验场");

        String external = postJson(staffToken, "/api/v1/performance/evaluations",
                createBody(supplierId, 0, "external", SAMPLE_FACTS));
        assertThat(codeOf(external)).isEqualTo(40001);
        assertThat(messageOf(external)).isEqualTo(MSG_PARAM_INVALID);
        assertThat(physicalRowCountOfSupplier(supplierId)).isZero();

        long id = createEvaluation(staffToken, supplierId, 0, null, SAMPLE_FACTS);
        assertThat(dataOf(detailRaw(staffToken, id)).get("mode").asText()).isEqualTo("manual");
        assertThat(physicalRow(id).get("MODE")).isEqualTo("manual");
        // 空串 mode 由入参层 @Pattern 拦下，不被静默当缺省（避免"前端传空却走了 manual"的隐式行为）
        assertThat(codeOf(postJson(staffToken, "/api/v1/performance/evaluations",
                createBody(supplierId, 1, "", SAMPLE_FACTS)))).isEqualTo(40001);
    }

    /** REJECT 必须带意见（缺失/纯空白都算未填），失败后单仍是 PENDING_REVIEW */
    @Test
    void rejectWithoutCommentIs40001AndLeavesEvaluationPendingReview() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = createSupplierViaApi(staffToken, "驳回意见试验场");
        long evaluationId = createEvaluation(staffToken, supplierId, 0, null, SAMPLE_FACTS);

        String absent = postJson(auditorToken,
                "/api/v1/performance/evaluations/" + evaluationId + "/review",
                "{\"decision\":\"REJECT\",\"version\":1}");
        assertThat(codeOf(absent)).isEqualTo(40001);
        assertThat(messageOf(absent)).isEqualTo(MSG_REJECT_COMMENT);

        assertThat(codeOf(postJson(auditorToken,
                "/api/v1/performance/evaluations/" + evaluationId + "/review",
                "{\"decision\":\"REJECT\",\"comment\":\"   \",\"version\":1}"))).isEqualTo(40001);

        assertThat(physicalRow(evaluationId).get("STATUS")).isEqualTo("PENDING_REVIEW");
        assertThat(physicalInt(evaluationId, "VERSION")).isEqualTo(1);
        assertThat(auditRows(evaluationId)).hasSize(1);

        // APPROVE 不带 comment 则合法（契约 §3.3 comment 仅 REJECT 条件必填）
        assertThat(codeOf(postJson(auditorToken,
                "/api/v1/performance/evaluations/" + evaluationId + "/review",
                "{\"decision\":\"APPROVE\",\"version\":1}"))).isZero();
        assertThat(physicalRow(evaluationId).get("STATUS")).isEqualTo("APPROVED");
        assertThat(physicalRow(evaluationId).get("REVIEW_COMMENT")).isNull();
    }

    // ==================== 验收 §2.6 REJECT 沿用同一条记录 ====================

    /**
     * REJECT → RETURNED（id 不变）→ §3.2 改事实重提 → PENDING_REVIEW（同一 id、version 自增、
     * 分数<b>重算</b>）：库内该供应商该周期始终只有一行，绝不因退回重提而产生第二条。
     */
    @Test
    void rejectThenResubmitKeepsTheSameRowAndRecomputesScoreWithoutSecondRecord() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = createSupplierViaApi(staffToken, "退回重提试验场");
        long evaluationId = createEvaluation(staffToken, supplierId, 0, null, SAMPLE_FACTS);

        String reject = postJson(auditorToken,
                "/api/v1/performance/evaluations/" + evaluationId + "/review",
                "{\"decision\":\"REJECT\",\"comment\":\"  交付批次与台账不符，请修正后重提。  \",\"version\":1}");
        assertThat(codeOf(reject)).isZero();
        JsonNode returned = dataOf(reject);
        assertThat(returned.get("id").asLong()).isEqualTo(evaluationId);
        assertThat(returned.get("status").asText()).isEqualTo("RETURNED");
        assertThat(returned.get("version").asInt()).isEqualTo(2);
        assertThat(returned.get("reviewed_by").asLong()).isEqualTo(userId("auditor01"));
        assertThat(returned.get("reviewed_at").asText()).isNotBlank();
        // comment 入库存 trim 后形态（与 §2.2/§2.6 同口径）
        assertThat(returned.get("review_comment").asText()).isEqualTo("交付批次与台账不符，请修正后重提。");
        assertThat(supplierGrade(supplierId)).isNull();
        assertThat(physicalRow(evaluationId).get("STATUS")).isEqualTo("RETURNED");

        // 重提交一份干净事实 → 分数必须由 91.45 变成 100.00（证明重提真的重新走了算分引擎）
        String resubmit = putUpdate(staffToken, evaluationId,
                "{\"fact_record\":" + perfectFacts() + ",\"version\":2}");
        assertThat(codeOf(resubmit)).isZero();
        JsonNode resubmitted = dataOf(resubmit);
        assertThat(resubmitted.get("id").asLong()).isEqualTo(evaluationId);
        assertThat(resubmitted.get("status").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(resubmitted.get("version").asInt()).isEqualTo(3);
        assertThat(new BigDecimal(resubmitted.get("result").get("total_score").asText()))
                .isEqualByComparingTo("100.00");
        assertThat(resubmitted.get("result").get("grade").asText()).isEqualTo("A");

        assertThat(physicalRowCountOfPeriod(supplierId, 0)).isEqualTo(1);
        assertThat(physicalTotalScore(evaluationId)).isEqualByComparingTo("100.00");
        assertThat(physicalInt(evaluationId, "VERSION")).isEqualTo(3);
        // 裁决③（用户 2026-10-15 18:50）：重提即回到待审新状态，上一次复核痕迹三字段必须清空，
        // 不得残留误导复核人——出站响应与库内物理列（绕过 @TableLogic 直查）双双断言为 NULL
        assertThat(resubmitted.get("reviewed_by").isNull()).isTrue();
        assertThat(resubmitted.get("reviewed_at").isNull()).isTrue();
        assertThat(resubmitted.get("review_comment").isNull()).isTrue();
        assertThat(physicalRow(evaluationId).get("REVIEWED_BY")).isNull();
        assertThat(physicalRow(evaluationId).get("REVIEWED_AT_RAW")).isNull();
        assertThat(physicalRow(evaluationId).get("REVIEW_COMMENT")).isNull();
        // 入参不带的 mode 沿用库内值，mock 单不会因重提变成 manual 单
        assertThat(physicalRow(evaluationId).get("MODE")).isEqualTo("manual");
    }

    // ==================== §3.3 归档联动：回写供应商两列且只打标不改状态 ====================

    /** APPROVE 归档：评价单终态 + 同事务回写 supplier.latest_performance_grade=A、risk_warning=0 */
    @Test
    void approveArchivesEvaluationAndWritesBackSupplierGradeWithoutRiskFlagOrStatusChange() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = promoteToNormal(staffToken, auditorToken, "归档回写A试验场");
        int supplierVersionBefore = supplierVersion(supplierId);
        long evaluationId = createEvaluation(staffToken, supplierId, 0, null, SAMPLE_FACTS);

        String json = postJson(auditorToken,
                "/api/v1/performance/evaluations/" + evaluationId + "/review",
                "{\"decision\":\"APPROVE\",\"comment\":\"经核实事实数据无误，予以归档。\",\"version\":1}");

        assertThat(codeOf(json)).isZero();
        JsonNode data = dataOf(json);
        assertThat(data.get("status").asText()).isEqualTo("APPROVED");
        assertThat(data.get("version").asInt()).isEqualTo(2);
        assertThat(data.get("reviewed_by").asLong()).isEqualTo(userId("auditor01"));
        assertThat(data.get("review_comment").asText()).isEqualTo("经核实事实数据无误，予以归档。");
        assertThat(physicalRow(evaluationId).get("REVIEWED_AT_RAW")).isNotNull();
        // 供应商两列回写 + version 自增 + 状态分毫不动（停用必须走 §2.4/§2.6 两步流程）
        assertThat(supplierGrade(supplierId)).isEqualTo("A");
        assertThat(supplierRiskWarning(supplierId)).isZero();
        assertThat(supplierStatus(supplierId)).isEqualTo("NORMAL");
        assertThat(supplierVersion(supplierId)).isEqualTo(supplierVersionBefore + 1);
    }

    /**
     * 验收 §2.4：评级 D 归档后 {@code risk_warning=true} 但 {@code status} 保持不变——
     * 评价接口无权自动停用供应商。
     */
    @Test
    void approveWithGradeDDoesNotAutoSuspendSupplierButMarksRiskWarning() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = promoteToNormal(staffToken, auditorToken, "归档回写D试验场");
        // 全维扣穿到 S=50：Q=0/D=0/P=0/S=50/C=100 → 总分 17.50 → D
        long evaluationId = createEvaluation(staffToken, supplierId, 0, null,
                facts(10, 10, "5", 10, 10, 2, "50", 5));

        assertThat(new BigDecimal(dataOf(detailRaw(staffToken, evaluationId))
                .get("result").get("total_score").asText())).isEqualByComparingTo("17.50");
        assertThat(dataOf(detailRaw(staffToken, evaluationId)).get("result").get("grade").asText())
                .isEqualTo("D");
        assertThat(codeOf(postJson(auditorToken,
                "/api/v1/performance/evaluations/" + evaluationId + "/review",
                "{\"decision\":\"APPROVE\",\"comment\":\"D 级，纳入停用评估。\",\"version\":1}"))).isZero();

        assertThat(supplierGrade(supplierId)).isEqualTo("D");
        assertThat(supplierRiskWarning(supplierId)).isEqualTo(1);
        assertThat(supplierStatus(supplierId)).isEqualTo("NORMAL");
        // 评价单自身的 risk_warning 只反映"存在过期已批资质"，本例资质有效 → 0（两枚口径分列，见 A4.3 注记）
        assertThat(physicalInt(evaluationId, "RISK_WARNING")).isZero();
    }

    /**
     * C 维度唯一数据源：后端自动读该供应商 status=APPROVED 资质的 expiry_date。
     * 过期已批件 → C=0.00 且评价单 risk_warning=true（其余四维满分 → 总分 90.00 恰为 A 下界）；
     * 只有过期但状态是 REJECTED 的件不参与（PENDING/REJECTED 一律忽略）→ C=100.00 总分 100.00。
     */
    @Test
    void expiredApprovedQualificationZeroesComplianceWhileRejectedOneIsIgnored() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");

        long withExpired = createSupplierViaApi(staffToken, "过期已批资质试验场");
        seedExpiredApprovedQualification(withExpired);
        long evalId = createEvaluation(staffToken, withExpired, 0, null, perfectFacts());
        JsonNode result = dataOf(detailRaw(staffToken, evalId)).get("result");
        assertThat(new BigDecimal(result.get("score_compliance").asText())).isEqualByComparingTo("0.00");
        assertThat(new BigDecimal(result.get("total_score").asText())).isEqualByComparingTo("90.00");
        assertThat(result.get("grade").asText()).isEqualTo("A");
        assertThat(result.get("risk_warning").asBoolean()).isTrue();
        assertThat(physicalInt(evalId, "RISK_WARNING")).isEqualTo(1);

        long onlyRejected = createSupplierViaApi(staffToken, "过期未批资质试验场");
        seedExpiredRejectedQualification(onlyRejected);
        long cleanEvalId = createEvaluation(staffToken, onlyRejected, 0, null, perfectFacts());
        JsonNode cleanResult = dataOf(detailRaw(staffToken, cleanEvalId)).get("result");
        assertThat(new BigDecimal(cleanResult.get("score_compliance").asText()))
                .isEqualByComparingTo("100.00");
        assertThat(new BigDecimal(cleanResult.get("total_score").asText())).isEqualByComparingTo("100.00");
        assertThat(cleanResult.get("risk_warning").asBoolean()).isFalse();

        // 过期件归档联动：供应商 risk_warning 由评价单风险标传导过来，且不改状态
        assertThat(codeOf(postJson(auditorToken,
                "/api/v1/performance/evaluations/" + evalId + "/review",
                "{\"decision\":\"APPROVE\",\"comment\":\"存在过期已批证照。\",\"version\":1}"))).isZero();
        assertThat(supplierRiskWarning(withExpired)).isEqualTo(1);
        assertThat(supplierStatus(withExpired)).isEqualTo("DRAFT");
    }

    // ==================== §3.4 列表：§0 行级隔离 + 分页收敛 + 只读 ====================

    /**
     * 隔离与收敛四连：STAFF 只见本人创建的单（后端下推 created_by，非全量后前端过滤）、
     * AUDITOR/ADMIN 全量、{@code page<=0} 折 1、{@code page_size<=0} 折 20、{@code >100} 折 100，
     * 列表按 created_at 倒序（同秒以 id 倒序兜底），且列表全程零写库零审计。
     */
    @Test
    void listEnforcesRowIsolationConvergesPagingAndStaysReadOnly() throws Exception {
        String ownerToken = loginToken("staff01", "Staff@123");
        String otherUsername = insertStaffAndLogin("孙七");
        String otherToken = loginToken(otherUsername, "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        String adminToken = loginToken("admin", "Admin@123");

        long supplierA = createSupplierViaApi(ownerToken, "列表隔离A试验场");
        long firstEval = createEvaluation(ownerToken, supplierA, 2, null, SAMPLE_FACTS);
        long secondEval = createEvaluation(ownerToken, supplierA, 1, null, perfectFacts());
        long supplierB = createSupplierViaApi(otherToken, "列表隔离B试验场");
        long otherEval = createEvaluation(otherToken, supplierB, 0, null, SAMPLE_FACTS);

        // STAFF-A 带 supplier_id=B 过滤：条件由后端 AND 上 created_by=本人，必然空集
        JsonNode ownA = dataOf(listRaw(ownerToken, "?supplier_id=" + supplierA));
        assertThat(ownA.get("total").asLong()).isEqualTo(2);
        assertThat(idsOf(ownA.get("list"))).containsExactly(secondEval, firstEval);
        JsonNode crossA = dataOf(listRaw(ownerToken, "?supplier_id=" + supplierB));
        assertThat(crossA.get("total").asLong()).isZero();
        assertThat(crossA.get("list")).isEmpty();
        // STAFF-B 只见自己那张
        JsonNode ownB = dataOf(listRaw(otherToken, "?supplier_id=" + supplierB));
        assertThat(idsOf(ownB.get("list"))).containsExactly(otherEval);

        // AUDITOR / ADMIN 全量（规则 B：准入即公有资产）
        assertThat(dataOf(listRaw(auditorToken, "?supplier_id=" + supplierA)).get("total").asLong())
                .isEqualTo(2);
        assertThat(dataOf(listRaw(adminToken, "?supplier_id=" + supplierA)).get("total").asLong())
                .isEqualTo(2);

        // 分页收敛（沿用 §1.3 口径，非法值不报错而是折回边界）
        JsonNode converged = dataOf(listRaw(auditorToken,
                "?supplier_id=" + supplierA + "&page=0&page_size=999"));
        assertThat(converged.get("page").asInt()).isEqualTo(1);
        assertThat(converged.get("page_size").asInt()).isEqualTo(100);
        JsonNode negative = dataOf(listRaw(auditorToken,
                "?supplier_id=" + supplierA + "&page=-3&page_size=-5"));
        assertThat(negative.get("page").asInt()).isEqualTo(1);
        assertThat(negative.get("page_size").asInt()).isEqualTo(20);
        // 越界页码不报错、返回空集且 total 仍是真值（防止"为跑赢而退化全表"的假空页）
        JsonNode beyond = dataOf(listRaw(auditorToken, "?supplier_id=" + supplierA + "&page=99"));
        assertThat(beyond.get("total").asLong()).isEqualTo(2);
        assertThat(beyond.get("list")).isEmpty();

        // 只读铁律：列表与详情都不写库、不留痕（前后两次物理探针取到的时间戳/版本必须同值）
        String updatedBefore = String.valueOf(physicalRow(firstEval).get("UPDATED_AT_RAW"));
        assertThat(physicalRow(firstEval).get("STATUS")).isEqualTo("PENDING_REVIEW");
        assertThat(physicalInt(firstEval, "VERSION")).isEqualTo(1);
        assertThat(auditRows(firstEval)).hasSize(1);
        assertThat(auditRows(secondEval)).hasSize(1);
        dataOf(listRaw(auditorToken, "?supplier_id=" + supplierA + "&page=1&page_size=20"));
        detailRaw(auditorToken, firstEval);
        detailRaw(ownerToken, firstEval);
        assertThat(physicalRow(firstEval).get("UPDATED_AT_RAW")).isEqualTo(updatedBefore);
        assertThat(auditRows(firstEval)).hasSize(1);
        assertThat(auditRows(otherEval)).hasSize(1);
    }

    /** 状态过滤器按码值原样下推：非法码值查不到即空集（不新造 40001 规则，口径同 §1.3） */
    @Test
    void listStatusFilterMatchesExactCodeAndUnknownCodeYieldsEmptyPage() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = createSupplierViaApi(staffToken, "列表状态过滤试验场");
        long pendingEval = createEvaluation(staffToken, supplierId, 0, null, SAMPLE_FACTS);
        long approvedEval = createEvaluation(staffToken, supplierId, 1, null, perfectFacts());
        assertThat(codeOf(postJson(auditorToken,
                "/api/v1/performance/evaluations/" + approvedEval + "/review",
                "{\"decision\":\"APPROVE\",\"comment\":\"归档。\",\"version\":1}"))).isZero();

        assertThat(idsOf(dataOf(listRaw(auditorToken,
                "?supplier_id=" + supplierId + "&status=PENDING_REVIEW")).get("list")))
                .containsExactly(pendingEval);
        assertThat(idsOf(dataOf(listRaw(auditorToken,
                "?supplier_id=" + supplierId + "&status=APPROVED")).get("list")))
                .containsExactly(approvedEval);
        assertThat(dataOf(listRaw(auditorToken,
                "?supplier_id=" + supplierId + "&status=RUNNING")).get("total").asLong()).isZero();
        // 大小写敏感：码值必须精确匹配，不做"宽容"归一（避免与前端约定两套口径）
        assertThat(dataOf(listRaw(auditorToken,
                "?supplier_id=" + supplierId + "&status=pending_review")).get("total").asLong()).isZero();
    }

    // ==================== §3.5 详情：越权 40302 且不泄露存在性之外信息 ====================

    /** 他人单详情 → 40302；AUDITOR/ADMIN 可读；详情全程只读零留痕 */
    @Test
    void detailOfOthersEvaluationIs40302WhileAuditorAndAdminCanReadIt() throws Exception {
        String ownerToken = loginToken("staff01", "Staff@123");
        String otherUsername = insertStaffAndLogin("周八");
        String otherToken = loginToken(otherUsername, "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        String adminToken = loginToken("admin", "Admin@123");
        long supplierId = createSupplierViaApi(ownerToken, "详情越权试验场");
        long evaluationId = createEvaluation(ownerToken, supplierId, 0, null, SAMPLE_FACTS);

        String forbidden = detailRaw(otherToken, evaluationId);
        assertThat(codeOf(forbidden)).isEqualTo(40302);
        assertThat(messageOf(forbidden)).isEqualTo(MSG_SELF_ONLY);
        // 与 §1.4 同口径：越权响应只回固定文案，不外泄该单的任何人/任何数值
        assertThat(dataOf(forbidden).isNull()).isTrue();

        assertThat(codeOf(detailRaw(auditorToken, evaluationId))).isZero();
        assertThat(codeOf(detailRaw(adminToken, evaluationId))).isZero();
        JsonNode data = dataOf(detailRaw(ownerToken, evaluationId));
        assertThat(data.fieldNames()).toIterable().containsExactlyInAnyOrder(RESPONSE_FIELDS);
        assertThat(data.get("fact_record").fieldNames()).toIterable()
                .containsExactlyInAnyOrder(FACT_RECORD_FIELDS);
        assertThat(data.get("result").fieldNames()).toIterable().containsExactlyInAnyOrder(RESULT_FIELDS);
        assertThat(data.get("created_by_name").asText()).isEqualTo("张三");
        assertThat(data.get("reviewed_by").isNull()).isTrue();
        assertThat(data.get("reviewed_at").isNull()).isTrue();
        assertThat(data.get("review_comment").isNull()).isTrue();
        assertThat(auditRows(evaluationId)).hasSize(1);
    }

    /**
     * 逻辑删除行对 {@code @TableLogic} 彻底隐身：详情 40401、列表不含，
     * 且生成列随之变 NULL（DDL L133"已删除历史不阻塞新建"），同周期可再次发起。
     */
    @Test
    void logicallyDeletedEvaluationIsInvisibleAndReleasesItsPlaceholder() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierId = createSupplierViaApi(staffToken, "逻辑删除隐身试验场");
        long evaluationId = createEvaluation(staffToken, supplierId, 0, null, SAMPLE_FACTS);

        jdbcTemplate.update("UPDATE performance_evaluation SET deleted = 1 WHERE id = ?", evaluationId);

        assertThat(codeOf(detailRaw(staffToken, evaluationId))).isEqualTo(40401);
        assertThat(dataOf(listRaw(auditorToken, "?supplier_id=" + supplierId)).get("total").asLong())
                .isZero();
        assertThat(physicalActivePeriodKey(evaluationId)).isNull();
        assertThat(physicalInt(evaluationId, "DELETED")).isEqualTo(1);

        long revived = createEvaluation(staffToken, supplierId, 0, null, perfectFacts());
        assertThat(revived).isNotEqualTo(evaluationId);
        assertThat(physicalRowCountOfPeriod(supplierId, 0)).isEqualTo(2);
    }

    // ==================== 验收 §3.1 审计全量留痕（含本轮拍板的重提 SUBMIT） ====================

    /**
     * 一张单走完 SUBMIT → REVIEW_REJECT → SUBMIT（重提）→ REVIEW_APPROVE 四条留痕，逐字段对账：
     * {@code entity_type} 恒为 PERFORMANCE_EVALUATION、{@code entity_id} 为评价单 ID、
     * {@code supplier_id} 冗余列已写（§0 规则 C 的前提）、operator_role 随动作切换、
     * REJECT/APPROVE 携带意见而 SUBMIT 不带。
     * 同时锁定"一码多域"：绩效域的 SUBMIT/REVIEW_* 不会与资质/供应商域混用
     * （同一 entity_id 在其他 entity_type 下必须为 0 条），也不存在裸 APPROVE/REJECT。
     */
    @Test
    void auditChainCoversSubmitRejectResubmitApproveWithPerformanceEntityType() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");
        // 走完整准入链（§1.1→§1.7→§2.1→§2.2）：既铺出可评价的存续供应商，又让该供应商名下
        // 先留下一条 SUPPLIER 域的 action=SUBMIT 留痕，与绩效域 SUBMIT 构成"一码多域"对照
        long supplierId = promoteToNormal(staffToken, auditorToken, "审计链路试验场");
        long evaluationId = createEvaluation(staffToken, supplierId, 0, null, SAMPLE_FACTS);
        assertThat(codeOf(postJson(auditorToken,
                "/api/v1/performance/evaluations/" + evaluationId + "/review",
                "{\"decision\":\"REJECT\",\"comment\":\"事实与质检台账不符。\",\"version\":1}"))).isZero();
        assertThat(codeOf(putUpdate(staffToken, evaluationId,
                "{\"fact_record\":" + perfectFacts() + ",\"version\":2}"))).isZero();
        assertThat(codeOf(postJson(auditorToken,
                "/api/v1/performance/evaluations/" + evaluationId + "/review",
                "{\"decision\":\"APPROVE\",\"comment\":\"修正后核实无误。\",\"version\":3}"))).isZero();

        List<Map<String, Object>> logs = auditRows(evaluationId);
        assertThat(logs).hasSize(4);
        long staffId = userId("staff01");
        long auditorId = userId("auditor01");

        Map<String, Object> submit = logs.get(0);
        assertThat(submit.get("ACTION")).isEqualTo("SUBMIT");
        assertThat(submit.get("ENTITY_TYPE")).isEqualTo("PERFORMANCE_EVALUATION");
        assertThat(submit.get("ENTITY_ID")).isEqualTo(evaluationId);
        assertThat(submit.get("SUPPLIER_ID")).isEqualTo(supplierId);
        assertThat(submit.get("OPERATOR_ID")).isEqualTo(staffId);
        assertThat(submit.get("OPERATOR_ROLE")).isEqualTo("STAFF");
        assertThat(submit.get("OLD_STATUS")).isNull();
        assertThat(submit.get("NEW_STATUS")).isEqualTo("PENDING_REVIEW");
        assertThat(submit.get("RESULT")).isEqualTo("SUCCESS");
        assertThat(submit.get("COMMENT")).isNull();

        Map<String, Object> reject = logs.get(1);
        assertThat(reject.get("ACTION")).isEqualTo("REVIEW_REJECT");
        assertThat(reject.get("OPERATOR_ID")).isEqualTo(auditorId);
        assertThat(reject.get("OPERATOR_ROLE")).isEqualTo("AUDITOR");
        assertThat(reject.get("OLD_STATUS")).isEqualTo("PENDING_REVIEW");
        assertThat(reject.get("NEW_STATUS")).isEqualTo("RETURNED");
        assertThat(reject.get("COMMENT")).isEqualTo("事实与质检台账不符。");

        // 本轮拍板项：重提成功同样写 SUBMIT（与首次提交一码，靠 entity_type 区分域）
        Map<String, Object> resubmit = logs.get(2);
        assertThat(resubmit.get("ACTION")).isEqualTo("SUBMIT");
        assertThat(resubmit.get("OLD_STATUS")).isEqualTo("RETURNED");
        assertThat(resubmit.get("NEW_STATUS")).isEqualTo("PENDING_REVIEW");
        assertThat(resubmit.get("OPERATOR_ROLE")).isEqualTo("STAFF");
        assertThat(resubmit.get("SUPPLIER_ID")).isEqualTo(supplierId);
        assertThat(resubmit.get("COMMENT")).isNull();

        Map<String, Object> approve = logs.get(3);
        assertThat(approve.get("ACTION")).isEqualTo("REVIEW_APPROVE");
        assertThat(approve.get("OLD_STATUS")).isEqualTo("PENDING_REVIEW");
        assertThat(approve.get("NEW_STATUS")).isEqualTo("APPROVED");
        assertThat(approve.get("COMMENT")).isEqualTo("修正后核实无误。");

        // 一码多域不串域（契约 §3.3 注记）：同一 action=SUBMIT 在本供应商名下分属两个域，
        // 唯一区分手段就是 entity_type —— SUPPLIER 域 1 条（§2.1 提交）+ 绩效域 2 条（首提 + 重提）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE supplier_id = ? AND action = 'SUBMIT'"
                        + " AND entity_type = 'SUPPLIER'", Integer.class, supplierId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE supplier_id = ? AND action = 'SUBMIT'"
                        + " AND entity_type = 'PERFORMANCE_EVALUATION'",
                Integer.class, supplierId)).isEqualTo(2);
        // 绩效域留痕的 entity_id 只能是评价单 ID，绝不会被写成供应商 ID
        assertThat(logs).allSatisfy(row ->
                assertThat(row.get("ENTITY_ID")).isEqualTo(evaluationId));
        // 只允许 SUBMIT / REVIEW_* 三种动作，裸 APPROVE/REJECT 全表为 0
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'PERFORMANCE_EVALUATION'"
                        + " AND action IN ('APPROVE','REJECT','AUDIT_APPROVE','AUDIT_REJECT',"
                        + " 'DECISION_APPROVE','DECISION_REJECT')", Integer.class)).isZero();
    }

    private List<Long> idsOf(JsonNode list) {
        List<Long> ids = new java.util.ArrayList<>();
        list.forEach(node -> ids.add(node.get("id").asLong()));
        return ids;
    }
}
