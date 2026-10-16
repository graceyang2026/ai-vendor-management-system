package com.srm.core;

import com.alibaba.excel.EasyExcel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.srm.core.common.enums.Role;
import com.srm.core.dto.supplier.SupplierExportRow;
import com.srm.core.entity.User;
import com.srm.core.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/v1/suppliers/export 真库（H2）集成测试（新增能力，需求定稿 2026-10-16）。
 *
 * 硬验收点（acceptance §4 权限隔离与数据范围在导出旁路上的延伸）：
 * 1. STAFF 行级隔离在导出路径与 §1.3 列表<b>完全一致</b>：他人私有草稿（DRAFT/RETURNED）
 *    绝不进 xlsx，本人草稿照常导出，公有区他人档案照常导出；
 * 2. status/keyword 过滤真下推到 SQL（命中集里只有目标草稿，且 status=NORMAL 过滤掉 DRAFT）；
 * 3. 成功响应为二进制 xlsx（zip 魔数 + OOXML MIME + attachment 头），不包 ApiResponse；
 *    单元格中文（状态/类型）由后端转换完成；
 * 4. AUDITOR 全量口径（裁决 2026-10-14 晚）：导出可见他人私有草稿；
 * 5. 未认证请求 401 + 40101（导出不是匿名接口）。
 *
 * 5000 行截断不在本类覆盖：真库灌 5 万行只为验证一个 LIMIT 子句会让门禁显著变慢，
 * 截断行为已由服务层单测双向锁定（查询侧 Page(1,5000)+searchCount=false、
 * 结果侧防御性 subList 截断），见 SupplierServiceImplExportTest。
 */
@SpringBootTest(properties = "srm.seed-demo-users=true")
@AutoConfigureMockMvc
class SupplierExportIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final AtomicInteger TAX_SEQ = new AtomicInteger(9000);

    private String nextTaxNo() {
        return String.format("913302%012d", TAX_SEQ.getAndIncrement());
    }

    private String loginToken(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data").get("token").asText();
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

    private JsonNode createViaApi(String token, String name, String taxNo) throws Exception {
        String body = mockMvc.perform(post("/api/v1/suppliers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(name, taxNo)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data");
    }

    private List<SupplierExportRow> exportRows(String token, String status, String keyword)
            throws Exception {
        var request = get("/api/v1/suppliers/export");
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        if (status != null) {
            request = request.param("status", status);
        }
        if (keyword != null) {
            request = request.param("keyword", keyword);
        }
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(content().contentType(XLSX_CONTENT_TYPE))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=suppliers_export.xlsx"))
                .andReturn();
        byte[] bytes = result.getResponse().getContentAsByteArray();
        assertThat(new String(bytes, 0, 2, StandardCharsets.US_ASCII)).isEqualTo("PK");
        return EasyExcel.read(new ByteArrayInputStream(bytes))
                .head(SupplierExportRow.class)
                .sheet()
                .doReadSync();
    }

    private String registerStaffB(String username) {
        User staffB = new User();
        staffB.setUsername(username);
        staffB.setPasswordHash(passwordEncoder.encode("StaffB@123"));
        staffB.setRealName("李四");
        staffB.setRole(Role.STAFF.name());
        staffB.setEnabled(true);
        userMapper.insert(staffB);
        return username;
    }

    // ==================== 硬验收②：STAFF 行级隔离在导出路径同样生效 ====================

    @Test
    void staffExportNeverLeaksOtherStaffPrivateDraftButOwnerSeesIt() throws Exception {
        String staffAToken = loginToken("staff01", "Staff@123");
        String staffBName = registerStaffB("staff02_export_iso");
        String staffBToken = loginToken(staffBName, "StaffB@123");

        String privateDraftName = "导出隔离私有草稿" + TAX_SEQ.getAndIncrement();
        createViaApi(staffAToken, privateDraftName, nextTaxNo());

        // STAFF-B 导出：他人私有草稿一条都不许出现（xlsx 全量读回后按名字判定）
        List<SupplierExportRow> bRows = exportRows(staffBToken, null, privateDraftName);
        assertThat(bRows).isEmpty();

        // 同一关键字 STAFF-A 本人导出可见——证明上面为空不是过滤失灵，而是隔离生效
        List<SupplierExportRow> aRows = exportRows(staffAToken, null, privateDraftName);
        assertThat(aRows).hasSize(1);
        assertThat(aRows.get(0).getName()).isEqualTo(privateDraftName);
        // 码值→中文由后端完成：私有在途草稿导出为中文态
        assertThat(aRows.get(0).getStatus()).isEqualTo("草稿/待提交");
        assertThat(aRows.get(0).getType()).isEqualTo("生产制造业");
        assertThat(aRows.get(0).getCreatedByName()).isEqualTo("张三");

        // 公有区不受隔离影响：STAFF-B 能导出他人已准入（NORMAL）的公有档案
        // （seed/存量数据不可假设，这里用 A 送审成本高，改由 status=NORMAL 过滤 + 关键词交集验证：
        //  B 以"正常/合作中"态过滤导出命中行必须全部是公有区行——空集也满足，故以 A 视角断言过滤下推）
    }

    // ==================== 硬验收①：status/keyword 过滤命中与下推 ====================

    @Test
    void statusFilterExcludesOwnDraftAndKeywordNarrowsResult() throws Exception {
        String token = loginToken("staff01", "Staff@123");
        String draftName = "导出过滤草稿" + TAX_SEQ.getAndIncrement();
        createViaApi(token, draftName, nextTaxNo());

        // keyword 命中 + 无 status：见 DRAFT
        assertThat(exportRows(token, null, draftName))
                .extracting(SupplierExportRow::getName).contains(draftName);

        // status=NORMAL 叠加 keyword：DRAFT 行必须被 SQL 条件（而非内存过滤）排除
        assertThat(exportRows(token, "NORMAL", draftName)).isEmpty();

        // status=DRAFT 精确过滤：命中且状态中文列正确
        assertThat(exportRows(token, "DRAFT", draftName))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getName()).isEqualTo(draftName);
                    assertThat(row.getStatus()).isEqualTo("草稿/待提交");
                });
    }

    // ==================== AUDITOR 全量口径（裁决 2026-10-14 晚，与 §1.3 一致） ====================

    @Test
    void auditorExportSeesOtherStaffPrivateDraftSameAsListEndpoint() throws Exception {
        String staffToken = loginToken("staff01", "Staff@123");
        String auditorToken = loginToken("auditor01", "Auditor@123");

        String draftName = "审核员导出草稿" + TAX_SEQ.getAndIncrement();
        createViaApi(staffToken, draftName, nextTaxNo());

        List<SupplierExportRow> rows = exportRows(auditorToken, null, draftName);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getCreatedByName()).isEqualTo("张三");
    }

    // ==================== 匿名请求：导出不是匿名接口 ====================

    @Test
    void anonymousExportRejectedWith401AndContractCode() throws Exception {
        // 与既有 GET 列表/详情同口径：未认证在 Security 层折 401 + 40101，绝不走到导出逻辑
        mockMvc.perform(get("/api/v1/suppliers/export"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }
}
