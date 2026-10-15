package com.srm.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 模块二 §2.7 GET /api/v1/lifecycle-requests 跨供应商聚合查询（终审待办队列）——
 * 真实数据库集成测试（H2；契约 §2.7 L562-593 + docs/api-spec.md §3 LifecycleRequestTodoItem，
 * 用户裁决 20261015）。与 §2.5（单供应商历史，LifecycleRequestIntegrationTest 已锁）共存不互代。
 *
 * 端到端锁定（全部经 JdbcTemplate 直查物理列拿库内物证，不接受替身式假绿）：
 * - <b>AUDITOR 专属</b>：STAFF/ADMIN → 403+40301，匿名 → 401+40101（@PreAuthorize 真实生效）；
 * - 过滤组合：status / type / supplier_id 单用与合用，谓词逐行回验 + total 与物理 COUNT 对齐；
 *   非法码值（含小写与越权串门的供应商态 PENDING_REVIEW）一律 40001「参数校验失败」，
 *   空白=未提供不过滤，trim 后合法码值放行；
 * - <b>分页与行序</b>：applied_at 倒序、同刻主键倒序（稳定分页），page/page_size 回显收敛值
 *   （page&lt;=0 折 1、page_size&lt;=0 折 20、&gt;100 折 100，与 §1.3 同一范式——契约矩阵"分页参数
 *   非法 40001"按任务书"沿用现有分页范式"裁决取收敛口径，留痕已备案）；越尾页空列表但 total 真值；
 * - 出参 15 字段 = 附录 A3 全 13 字段 + supplier_name/supplier_status（物理 snake_case）；
 *   <b>supplier_status 是当前合作状态</b>，与申请目标 type 两码事（用"SUSPENDED 供应商 + 已批
 *   RESUME 单"的历史组合钉死二者互不覆盖）；
 * - 供应商已逻辑删除/脏 FK 不存在：行仍返回、两枚冗余字段 null、不报错（AUDITOR 全量终审视图，
 *   不受 §0 规则 A 行级隔离限制——DRAFT 私有在途供应商的申请单同样可见）；
 * - 申请单逻辑删除行（deleted=1）绝不出现；
 * - <b>只读铁律</b>：连续多次查询前后，lifecycle_request 全行快照（status/version/decided_*）、
 *   supplier 状态与版本、audit_log 全表行数分毫不动；
 * - <b>version 取值链闭合</b>：从本接口拿到的 version 直接喂 §2.6 决策接口可成功终审
 *   （契约 §2.7 L571"合法取值源之一"的端到端物证）。
 *
 * 造数口径：§2.7 是纯读侧查询，供应商三态/申请三态/逻辑删除行/脏 FK 等前提组合经 API 铺不出，
 * 采用直插 SQL（与 §2.5 既有用例 insertHistoricalRow 同范式）；写侧行为（建单/终审）由 §2.4–§2.6
 * 既有集成测试锁定，本类不重复演绎。时间戳刻意取本测试独占的未来年份（2037/2040），跨供应商
 * 无过滤断言用"相对次序 + 存在性"子序列校验，绝不与共享上下文库里其他测试类的数据相互干扰。
 * 税号段 400+，与 300+/500+/600+/700+/900+ 五个既有集成测试错开（共享上下文库不撞 uk_tax_no_active）。
 */
@SpringBootTest(properties = "srm.seed-demo-users=true")
@AutoConfigureMockMvc
class LifecycleRequestTodoIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final AtomicInteger TAX_SEQ = new AtomicInteger(400);

    /** 附录 A3 的 13 字段 + supplier_name/supplier_status（契约 §2.7 L585，共 15 字段） */
    private static final String[] TODO_FIELDS_15 = {
            "id", "supplier_id", "type", "reason", "status", "applied_by", "applied_by_name",
            "applied_at", "decided_by", "decided_by_name", "decided_at", "decision_comment",
            "version", "supplier_name", "supplier_status"
    };

    private static final String MSG_PARAM_INVALID = "参数校验失败";

    // ==================== 夹具与探针 ====================

    private String loginToken(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data").get("token").asText();
    }

    private long userId(String username) {
        Long id = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = ?",
                Long.class, username);
        assertThat(id).isNotNull();
        return id;
    }

    /** 直插供应商造数（纯读侧查询的前提态，与 §2.5 直插历史单同一造数范式） */
    private long insertSupplier(String name, String status, int deleted) {
        String taxNo = String.format("913301%012d", TAX_SEQ.getAndIncrement());
        LocalDateTime now = LocalDateTime.of(2026, 6, 1, 8, 0);
        jdbcTemplate.update("INSERT INTO supplier (name, tax_no, type, status, created_by, version,"
                        + " created_at, updated_at, deleted) VALUES (?,?,?,?,?,?,?,?,?)",
                name, taxNo, "MANUFACTURING", status, userId("staff01"), 1, now, now, deleted);
        Long id = jdbcTemplate.queryForObject("SELECT MAX(id) FROM supplier", Long.class);
        assertThat(id).isNotNull();
        return id;
    }

    /** 直插申请单造数，返回新行 ID */
    private long insertRequest(long supplierId, String type, String status, int version,
                               LocalDateTime appliedAt, Long decidedBy, int deleted) {
        jdbcTemplate.update("INSERT INTO lifecycle_request (supplier_id, type, reason, status, version,"
                        + " applied_by, applied_at, decided_by, decided_at, decision_comment,"
                        + " created_at, updated_at, deleted) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                supplierId, type, "终审待办造数：" + type + "/" + status, status, version,
                userId("staff01"), appliedAt, decidedBy,
                decidedBy == null ? null : appliedAt.plusDays(1),
                decidedBy == null ? null : "同意。", appliedAt, appliedAt, deleted);
        Long id = jdbcTemplate.queryForObject("SELECT MAX(id) FROM lifecycle_request", Long.class);
        assertThat(id).isNotNull();
        return id;
    }

    private String todoViaApi(String token, Map<String, String> params) throws Exception {
        var request = get("/api/v1/lifecycle-requests");
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                request = request.param(e.getKey(), e.getValue());
            }
        }
        return mockMvc.perform(request.header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private Map<String, String> params(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
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

    private long physicalActiveCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lifecycle_request WHERE deleted = 0", Integer.class);
        return count == null ? 0 : count;
    }

    private long physicalActiveCountByStatus(String status) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lifecycle_request WHERE deleted = 0 AND status = ?",
                Integer.class, status);
        return count == null ? 0 : count;
    }

    private long physicalActiveCountByType(String type) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lifecycle_request WHERE deleted = 0 AND type = ?",
                Integer.class, type);
        return count == null ? 0 : count;
    }

    private int auditTotalCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class);
        return count == null ? 0 : count;
    }

    /** 全表读侧快照：申请单行 + 供应商行 + 审计行数，只读铁律的"前后分毫不动"对比基准 */
    private List<Map<String, Object>> snapshotRequests(long supplierId) {
        return jdbcTemplate.queryForList(
                "SELECT id, status, version, applied_at, decided_by, decided_at, decision_comment,"
                        + " deleted FROM lifecycle_request WHERE supplier_id = ? ORDER BY id", supplierId);
    }

    private Map<String, Object> snapshotSupplier(long supplierId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, version, updated_at, deleted FROM supplier WHERE id = ?", supplierId);
    }

    /** 在返回的 list 里按 id 找节点；找不到返回 null（存在性断言用） */
    private JsonNode findItem(JsonNode list, long id) {
        for (JsonNode node : list) {
            if (node.get("id").asLong() == id) {
                return node;
            }
        }
        return null;
    }

    /** 子序列断言：expectedIds 在返回 list 中的命中位置必须严格递增（相对倒序次序不被其他数据干扰） */
    private void assertRelativeOrder(JsonNode list, List<Long> expectedIds) {
        int cursor = -1;
        for (long id : expectedIds) {
            int index = -1;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).get("id").asLong() == id) {
                    index = i;
                    break;
                }
            }
            assertThat(index).as("待办行 %d 必须出现在返回 list 中（page_size=100 覆盖测试类全部造数）", id)
                    .isGreaterThan(cursor);
            cursor = index;
        }
    }

    // ==================== 角色闸（契约 §2.7 L567/L592：AUDITOR 专属） ====================

    @Test
    void todoListRejectsStaffAdminAndAnonymous() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String adminToken = loginToken("admin", "Admin@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");

        mockMvc.perform(get("/api/v1/lifecycle-requests")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301))
                .andExpect(jsonPath("$.message").value("无权限"));
        mockMvc.perform(get("/api/v1/lifecycle-requests")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
        mockMvc.perform(get("/api/v1/lifecycle-requests"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));

        // AUDITOR 本体畅通（哪怕库里没有其可见数据也只应是 code 0，不许 4xx）
        assertThat(codeOf(todoViaApi(auditorToken, params("status", "APPROVED")))).isZero();
    }

    // ==================== 跨供应商聚合：行序、字段、冗余两列、隐身规则 ====================

    @Test
    void todoListAggregatesAcrossSuppliersNewestFirstWithFifteenFields() throws Exception {
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplierNormal = insertSupplier("聚合场-正常合作", "NORMAL", 0);
        long supplierSuspended = insertSupplier("聚合场-已停用", "SUSPENDED", 0);
        long supplierDeleted = insertSupplier("聚合场-已删除", "ELIMINATED", 1);   // 逻辑删除供应商
        long ghostSupplierId = 987650711L;                                        // 脏 FK：供应商从未存在

        // 时间戳 2037-* 严格新于其他测试类（2026-*），倒序时本组数据必进 page_size=100 首页窗口
        long rSuspendedApproved = insertRequest(supplierSuspended, "RESUME", "APPROVED", 2,
                LocalDateTime.of(2037, 6, 4, 9, 0), userId("auditor01"), 0);
        long rNormalPending = insertRequest(supplierNormal, "SUSPEND", "PENDING", 1,
                LocalDateTime.of(2037, 6, 5, 9, 0), null, 0);
        long rDeletedSupplier = insertRequest(supplierDeleted, "ELIMINATE", "PENDING", 1,
                LocalDateTime.of(2037, 6, 6, 9, 0), null, 0);
        long rGhostSupplier = insertRequest(ghostSupplierId, "SUSPEND", "REJECTED", 2,
                LocalDateTime.of(2037, 6, 3, 9, 0), userId("auditor01"), 0);
        long rLogicallyDeletedRequest = insertRequest(supplierNormal, "RESUME", "PENDING", 1,
                LocalDateTime.of(2037, 6, 7, 9, 0), null, 1);                     // 逻辑删除申请单

        String json = todoViaApi(auditorToken, params("page", "1", "page_size", "100"));
        assertThat(codeOf(json)).isZero();
        JsonNode data = dataOf(json);
        assertThat(data.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("list", "total", "page", "page_size");
        JsonNode list = data.get("list");

        // total = 物理 deleted=0 全表 COUNT（逻辑删除行不计），且回显分页参数原值
        assertThat(data.get("total").asLong()).isEqualTo(physicalActiveCount());
        assertThat(data.get("page").asInt()).isEqualTo(1);
        assertThat(data.get("page_size").asInt()).isEqualTo(100);

        // 逻辑删除申请单隐身；其余四行全部存在（已决单也返回——终审队列含历史结论）
        assertThat(findItem(list, rLogicallyDeletedRequest)).isNull();
        for (long id : List.of(rDeletedSupplier, rNormalPending, rSuspendedApproved, rGhostSupplier)) {
            assertThat(findItem(list, id)).as("行 %d 应出现在终审队列", id).isNotNull();
        }

        // applied_at 倒序的相对次序：06-06 > 06-05 > 06-04 > 06-03（其他测试类数据穿插也不破子序列）
        assertRelativeOrder(list, List.of(rDeletedSupplier, rNormalPending,
                rSuspendedApproved, rGhostSupplier));

        // 15 字段一个不能少，物理 snake_case
        assertThat(findItem(list, rNormalPending).fieldNames()).toIterable()
                .containsExactlyInAnyOrder(TODO_FIELDS_15);

        JsonNode pendingItem = findItem(list, rNormalPending);
        assertThat(pendingItem.get("supplier_name").asText()).isEqualTo("聚合场-正常合作");
        assertThat(pendingItem.get("supplier_status").asText()).isEqualTo("NORMAL");
        assertThat(pendingItem.get("applied_by_name").asText()).isEqualTo("张三");
        assertThat(pendingItem.get("version").asInt()).isEqualTo(1);              // §2.6 取值链字段就位
        assertThat(pendingItem.get("decided_by").isNull()).isTrue();
        // 未决行 decided_by_name 与 §2.5 同口径：人名反查 miss 回空串（契约附录 A3 派生列既有裁决）
        assertThat(pendingItem.get("decided_by_name").asText()).isEmpty();

        // supplier_status=当前合作状态（SUSPENDED），申请目标 type=RESUME、单状态 status=APPROVED：
        // 三个概念同屏互不覆盖，正是契约 L585"与申请目标 type 区分展示"的钉死用例
        JsonNode approvedItem = findItem(list, rSuspendedApproved);
        assertThat(approvedItem.get("supplier_status").asText()).isEqualTo("SUSPENDED");
        assertThat(approvedItem.get("type").asText()).isEqualTo("RESUME");
        assertThat(approvedItem.get("status").asText()).isEqualTo("APPROVED");
        assertThat(approvedItem.get("decided_by_name").asText()).isEqualTo("李四");
        assertThat(approvedItem.get("decision_comment").asText()).isEqualTo("同意。");

        // 供应商被逻辑删除/根本不存在：行仍返回、冗余两列 null、不报错（AUDITOR 全量终审视图）
        JsonNode deletedSupplierItem = findItem(list, rDeletedSupplier);
        assertThat(deletedSupplierItem.get("supplier_name").isNull()).isTrue();
        assertThat(deletedSupplierItem.get("supplier_status").isNull()).isTrue();
        assertThat(deletedSupplierItem.get("reason").asText()).contains("ELIMINATE");
        JsonNode ghostItem = findItem(list, rGhostSupplier);
        assertThat(ghostItem.get("supplier_id").asLong()).isEqualTo(ghostSupplierId);
        assertThat(ghostItem.get("supplier_name").isNull()).isTrue();
        assertThat(ghostItem.get("supplier_status").isNull()).isTrue();
    }

    @Test
    void todoListHonorsAuditorFullViewWithoutSectionZeroRowIsolation() throws Exception {
        // §0 规则 A 行级隔离卡的是 STAFF 列表；本接口是 AUDITOR 全量终审视图——
        // 他人 DRAFT 私有在途供应商的申请单一律可见（直插造一张 DRAFT 供应商的 PENDING 单）
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long draftSupplier = insertSupplier("隔离豁免场-草稿区", "DRAFT", 0);
        long requestId = insertRequest(draftSupplier, "SUSPEND", "PENDING", 1,
                LocalDateTime.of(2037, 7, 1, 9, 0), null, 0);

        String json = todoViaApi(auditorToken, params("supplier_id", String.valueOf(draftSupplier)));

        assertThat(codeOf(json)).isZero();
        JsonNode list = dataOf(json).get("list");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("id").asLong()).isEqualTo(requestId);
        assertThat(list.get(0).get("supplier_status").asText()).isEqualTo("DRAFT");
    }

    // ==================== 过滤组合（status × type × supplier_id） ====================

    @Test
    void todoListFiltersByStatusTypeAndSupplierIdInEveryCombination() throws Exception {
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplier = insertSupplier("过滤组合场", "NORMAL", 0);
        LocalDateTime base = LocalDateTime.of(2037, 8, 1, 9, 0);
        long pendingSuspend = insertRequest(supplier, "SUSPEND", "PENDING", 1, base.plusDays(4), null, 0);
        long approvedSuspend = insertRequest(supplier, "SUSPEND", "APPROVED", 2, base.plusDays(3),
                userId("auditor01"), 0);
        long rejectedResume = insertRequest(supplier, "RESUME", "REJECTED", 2, base.plusDays(2),
                userId("auditor01"), 0);
        long pendingEliminate = insertRequest(supplier, "ELIMINATE", "PENDING", 1, base.plusDays(1), null, 0);
        insertRequest(supplier, "SUSPEND", "PENDING", 1, base, null, 1);          // 逻辑删除行防泄漏

        // supplier_id 单用：4 行，删除行隐身，total 恰为 4
        JsonNode all = dataOf(todoViaApi(auditorToken, params("supplier_id", String.valueOf(supplier))));
        assertThat(all.get("total").asLong()).isEqualTo(4);
        assertThat(all.get("list")).hasSize(4);

        // supplier_id + status：PENDING 两张
        JsonNode pendings = dataOf(todoViaApi(auditorToken,
                params("supplier_id", String.valueOf(supplier), "status", "PENDING")));
        assertThat(pendings.get("total").asLong()).isEqualTo(2);
        for (JsonNode node : pendings.get("list")) {
            assertThat(node.get("status").asText()).isEqualTo("PENDING");
        }
        assertThat(findItem(pendings.get("list"), pendingSuspend)).isNotNull();
        assertThat(findItem(pendings.get("list"), pendingEliminate)).isNotNull();

        // supplier_id + type：SUSPEND 两张（含已决单——type 过滤与状态无关）
        JsonNode suspends = dataOf(todoViaApi(auditorToken,
                params("supplier_id", String.valueOf(supplier), "type", "SUSPEND")));
        assertThat(suspends.get("total").asLong()).isEqualTo(2);
        assertThat(findItem(suspends.get("list"), approvedSuspend)).isNotNull();

        // 三条件合用：RESUME+REJECTED 恰一张
        JsonNode combo = dataOf(todoViaApi(auditorToken,
                params("supplier_id", String.valueOf(supplier), "status", "REJECTED", "type", "RESUME")));
        assertThat(combo.get("total").asLong()).isEqualTo(1);
        assertThat(combo.get("list").get(0).get("id").asLong()).isEqualTo(rejectedResume);

        // 互斥组合（本供应商没有 APPROVED 的 RESUME 单）→ 空列表 total 0，不是 4xx
        JsonNode none = dataOf(todoViaApi(auditorToken,
                params("supplier_id", String.valueOf(supplier), "status", "APPROVED", "type", "RESUME")));
        assertThat(none.get("total").asLong()).isZero();
        assertThat(none.get("list")).isEmpty();

        // 全表级谓词回验：无 supplier_id 的 status/type 过滤，每一行都满足谓词且 total=物理 COUNT
        JsonNode globalPending = dataOf(todoViaApi(auditorToken, params("status", "PENDING")));
        assertThat(globalPending.get("total").asLong()).isEqualTo(physicalActiveCountByStatus("PENDING"));
        for (JsonNode node : globalPending.get("list")) {
            assertThat(node.get("status").asText()).isEqualTo("PENDING");
        }
        JsonNode globalSuspend = dataOf(todoViaApi(auditorToken, params("type", "SUSPEND")));
        assertThat(globalSuspend.get("total").asLong()).isEqualTo(physicalActiveCountByType("SUSPEND"));
        for (JsonNode node : globalSuspend.get("list")) {
            assertThat(node.get("type").asText()).isEqualTo("SUSPEND");
        }

        // 不存在的 supplier_id → 空结果而非 404（聚合查询语义：过滤条件不是存在性断言，
        // 与 §2.5"供应商不存在 40401"的单资源语义刻意不同）
        JsonNode missing = dataOf(todoViaApi(auditorToken, params("supplier_id", "987650811")));
        assertThat(missing.get("total").asLong()).isZero();
        assertThat(missing.get("list")).isEmpty();
    }

    // ==================== 非法枚举 → 40001（契约 §2.7 L591） ====================

    @Test
    void todoListRejectsIllegalEnumCodesWith40001AndWritesNothing() throws Exception {
        String auditorToken = loginToken("auditor01", "Auditor@123");
        int auditBefore = auditTotalCount();
        long rowsBefore = physicalActiveCount();

        // 小写、生造码、跨域串码（PENDING_REVIEW 属供应商/绩效态，不属申请单三态）一律拒绝
        for (Map<String, String> bad : List.of(
                params("status", "FOO"),
                params("status", "pending"),
                params("status", "PENDING_REVIEW"),
                params("type", "suspend"),
                params("type", "BLACKLIST"),
                params("status", "PENDING", "type", "STOP"))) {
            String json = todoViaApi(auditorToken, bad);
            assertThat(codeOf(json)).as("非法码值参数组 %s 必须 40001", bad).isEqualTo(40001);
            assertThat(messageOf(json)).isEqualTo(MSG_PARAM_INVALID);
        }

        // 空白=未提供（不过滤放行），trim 后合法码值放行
        assertThat(codeOf(todoViaApi(auditorToken, params("status", "   ", "type", "")))).isZero();
        assertThat(codeOf(todoViaApi(auditorToken, params("status", " PENDING ")))).isZero();

        assertThat(auditTotalCount()).isEqualTo(auditBefore);   // 失败路径零审计
        assertThat(physicalActiveCount()).isEqualTo(rowsBefore); // 失败路径零写库
    }

    // ==================== 分页：切片、越尾、同刻稳定序、收敛口径 ====================

    @Test
    void todoListPagesWithTotalStableTieBreakAndExistingClampConvention() throws Exception {
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplier = insertSupplier("分页切片场", "NORMAL", 0);
        LocalDateTime same = LocalDateTime.of(2037, 9, 10, 9, 0);
        // applied_at 倒序 + 同刻主键倒序的完整期望序：newest, tieOlder(id desc), tieNewer(id desc), oldest
        long newest = insertRequest(supplier, "SUSPEND", "PENDING", 1, same.plusDays(3), null, 0);
        long tieLow = insertRequest(supplier, "RESUME", "PENDING", 1, same, null, 0);
        long tieHigh = insertRequest(supplier, "ELIMINATE", "PENDING", 1, same, null, 0);
        long oldest = insertRequest(supplier, "SUSPEND", "APPROVED", 2, same.minusDays(3),
                userId("auditor01"), 0);
        List<Long> expectedOrder = List.of(newest, tieHigh, tieLow, oldest);

        // page_size=2 两页切片：并集=全集、互斥、每页内部次序正确、total 恒 4
        JsonNode page1 = dataOf(todoViaApi(auditorToken,
                params("supplier_id", String.valueOf(supplier), "page", "1", "page_size", "2")));
        JsonNode page2 = dataOf(todoViaApi(auditorToken,
                params("supplier_id", String.valueOf(supplier), "page", "2", "page_size", "2")));
        assertThat(page1.get("total").asLong()).isEqualTo(4);
        assertThat(page2.get("total").asLong()).isEqualTo(4);
        assertThat(page1.get("list")).hasSize(2);
        assertThat(page2.get("list")).hasSize(2);
        assertThat(page1.get("list").get(0).get("id").asLong()).isEqualTo(expectedOrder.get(0));
        assertThat(page1.get("list").get(1).get("id").asLong()).isEqualTo(expectedOrder.get(1));
        assertThat(page2.get("list").get(0).get("id").asLong()).isEqualTo(expectedOrder.get(2));
        assertThat(page2.get("list").get(1).get("id").asLong()).isEqualTo(expectedOrder.get(3));

        // 越尾页：空 list 但 total 真值（COUNT 与 LIMIT 各管各的）
        JsonNode beyond = dataOf(todoViaApi(auditorToken,
                params("supplier_id", String.valueOf(supplier), "page", "9", "page_size", "2")));
        assertThat(beyond.get("list")).isEmpty();
        assertThat(beyond.get("total").asLong()).isEqualTo(4);

        // 分页参数非法 → 按现有 §1.3 范式收敛（任务书口径），绝不 5xx、绝不放大成全表：
        // page=0 → 第 1 页；page_size=1000 → 收敛 100；page_size=0 → 默认 20。回显即收敛值。
        JsonNode clampedPage = dataOf(todoViaApi(auditorToken,
                params("supplier_id", String.valueOf(supplier), "page", "0", "page_size", "2")));
        assertThat(clampedPage.get("page").asInt()).isEqualTo(1);
        assertThat(clampedPage.get("list").get(0).get("id").asLong()).isEqualTo(newest);
        JsonNode clampedSize = dataOf(todoViaApi(auditorToken,
                params("supplier_id", String.valueOf(supplier), "page_size", "1000")));
        assertThat(clampedSize.get("page_size").asInt()).isEqualTo(100);
        assertThat(clampedSize.get("list")).hasSize(4);
        JsonNode clampedDefault = dataOf(todoViaApi(auditorToken,
                params("supplier_id", String.valueOf(supplier), "page_size", "0")));
        assertThat(clampedDefault.get("page_size").asInt()).isEqualTo(20);
    }

    // ==================== 只读铁律（契约 §2.7 L570：不写审计、不改任何状态） ====================

    @Test
    void todoListIsReadOnlyAcrossAllThreeTables() throws Exception {
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplier = insertSupplier("只读铁律场", "NORMAL", 0);
        long pending = insertRequest(supplier, "SUSPEND", "PENDING", 1,
                LocalDateTime.of(2037, 10, 5, 9, 0), null, 0);
        long approved = insertRequest(supplier, "ELIMINATE", "APPROVED", 2,
                LocalDateTime.of(2037, 10, 4, 9, 0), userId("auditor01"), 0);

        List<Map<String, Object>> requestsBefore = snapshotRequests(supplier);
        Map<String, Object> supplierBefore = snapshotSupplier(supplier);
        int auditBefore = auditTotalCount();

        // 多轮不同过滤组合反复查询（含空结果过滤，覆盖 early-return 分支）
        todoViaApi(auditorToken, null);
        todoViaApi(auditorToken, params("supplier_id", String.valueOf(supplier), "status", "PENDING"));
        todoViaApi(auditorToken, params("supplier_id", String.valueOf(supplier),
                "status", "REJECTED", "type", "SUSPEND"));
        todoViaApi(auditorToken, params("page", "2", "page_size", "1"));

        assertThat(snapshotRequests(supplier)).isEqualTo(requestsBefore);   // 三态/version/决策痕迹全冻结
        assertThat(pending).isNotEqualTo(approved);                         // 防呆：两次造数确为不同行
        assertThat(snapshotSupplier(supplier)).isEqualTo(supplierBefore);   // 供应商状态与 version 分毫不动
        assertThat(auditTotalCount()).isEqualTo(auditBefore);               // 零审计（含跨请求缓存/懒写路径）

        // 结果本身也印证队列语义：PENDING 与已决单同时可见
        JsonNode list = dataOf(todoViaApi(auditorToken,
                params("supplier_id", String.valueOf(supplier)))).get("list");
        assertThat(findItem(list, pending)).isNotNull();
        assertThat(findItem(list, approved)).isNotNull();
    }

    // ==================== version 取值链：§2.7 → §2.6 端到端（契约 §2.7 L571） ====================

    @Test
    void versionExposedByTodoListIsLegalInputForDecisionEndpoint() throws Exception {
        String auditorToken = loginToken("auditor01", "Auditor@123");
        long supplier = insertSupplier("取值链场", "NORMAL", 0);
        // version 刻意取 3（非默认 1）：证明回传的是库内真值而非写死占位
        long requestId = insertRequest(supplier, "SUSPEND", "PENDING", 3,
                LocalDateTime.of(2037, 11, 1, 9, 0), null, 0);

        JsonNode item = dataOf(todoViaApi(auditorToken,
                params("supplier_id", String.valueOf(supplier), "status", "PENDING")))
                .get("list").get(0);
        assertThat(item.get("id").asLong()).isEqualTo(requestId);
        int versionFromList = item.get("version").asInt();
        assertThat(versionFromList).isEqualTo(3);

        String decision = mockMvc.perform(post("/api/v1/lifecycle-requests/" + requestId + "/decision")
                        .header("Authorization", "Bearer " + auditorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"comment\":\"经§2.7队列取版本终审。\","
                                + "\"version\":" + versionFromList + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(codeOf(decision)).isZero();

        // 终审落地后本队列即时反映：单态 APPROVED + version 自增 + 供应商当前态已翻为 SUSPENDED
        JsonNode after = dataOf(todoViaApi(auditorToken,
                params("supplier_id", String.valueOf(supplier)))).get("list").get(0);
        assertThat(after.get("status").asText()).isEqualTo("APPROVED");
        assertThat(after.get("version").asInt()).isEqualTo(4);
        assertThat(after.get("supplier_status").asText()).isEqualTo("SUSPENDED");

        // 用已消费的旧版本再决策 → 40901（取值链的并发语义同样闭环，与 §2.6 既有裁决同口径）
        String stale = mockMvc.perform(post("/api/v1/lifecycle-requests/" + requestId + "/decision")
                        .header("Authorization", "Bearer " + auditorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\",\"version\":3}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(codeOf(stale)).isEqualTo(40302);   // 已决单先闸 40302（§2.6 判序：状态闸先于乐观锁）
    }

    // ==================== 非 AUDITOR 的 Service 二验兜底探针 ====================

    @Test
    void serviceLevelSecondRoleGateKeepsDenialReasonAuditable() throws Exception {
        // 传输口径（用户裁决 2026-10-14）下 @PreAuthorize 已给出 403/40301；本用例锁定
        // STAFF token 走真实 Security 链被拦时零查询副作用——不放行到 Service，也不落任何审计
        String staffToken = loginToken("staff01", "Staff@123");
        int auditBefore = auditTotalCount();
        long rowsBefore = physicalActiveCount();

        mockMvc.perform(get("/api/v1/lifecycle-requests")
                        .header("Authorization", "Bearer " + staffToken)
                        .param("status", "PENDING"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));

        assertThat(auditTotalCount()).isEqualTo(auditBefore);
        assertThat(physicalActiveCount()).isEqualTo(rowsBefore);
    }
}
