package com.srm.core.controller;

import com.alibaba.excel.EasyExcel;
import com.srm.core.common.BusinessException;
import com.srm.core.common.ErrorCode;
import com.srm.core.common.GlobalExceptionHandler;
import com.srm.core.dto.supplier.SupplierExportRow;
import com.srm.core.dto.supplier.SupplierResponse;
import com.srm.core.service.SupplierService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/v1/suppliers/export 出参层契约测试（standalone MockMvc + 真实 GlobalExceptionHandler）。
 *
 * 本端点是<b>唯一绕过「HTTP 200 + JSON ApiResponse」全局约定</b>的端点（需求定稿 2026-10-16）：
 * 1. 成功路径直接写 xlsx 二进制流：Content-Type 为 OOXML 工作簿 MIME，
 *    Content-Disposition 为 attachment; filename=suppliers_export.xlsx，响应体<b>不包 ApiResponse</b>；
 * 2. 入参 status/keyword 原样透传 Service（无 page/page_size——导出不是分页接口）；
 * 3. 二进制可被 EasyExcel 原样读回：中文列头 + 数据单元格逐格与行模型一致；
 * 4. 角色闸与 §1.3 列表端点<b>逐字一致</b>（反射断言 @PreAuthorize 表达式相等）——
 *    系统内仅 ADMIN/STAFF/AUDITOR 三态且本端点全部放行，越权 40301 分支在现有角色体系下
 *    无法构造真实主体，故锁"闸口表达式与 list 同源"这一等价断言（详见会话留痕待裁决项）；
 * 5. 失败路径（Service 抛 BusinessException）绝不写出半截 xlsx，仍由全局兜底折回 JSON。
 */
@ExtendWith(MockitoExtension.class)
class SupplierControllerExportTest {

    @Mock
    private SupplierService supplierService;

    private MockMvc mockMvc;

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SupplierController(supplierService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** 行模型夹具：与生产导出口径一致，码值→中文已由服务层完成，这里直接落中文单元格 */
    private static SupplierExportRow row(Long id, String name, String statusLabel, String typeLabel,
                                         String riskLabel) {
        SupplierExportRow row = new SupplierExportRow();
        row.setId(id);
        row.setName(name);
        row.setTaxNo("91330100MA27XXXX8X");
        row.setType(typeLabel);
        row.setContactName("王强");
        row.setContactPhone("13800138000");
        row.setContactEmail("wang@example.com");
        row.setAddress("杭州市西湖区");
        row.setRemark("备注");
        row.setEffectiveDate("2026-01-01");
        row.setExpiryDate("2028-12-31");
        row.setStatus(statusLabel);
        row.setLatestPerformanceGrade("B");
        row.setRiskWarning(riskLabel);
        row.setCreatedByName("张三");
        row.setCreatedAt("2026-10-14T10:00:00");
        row.setUpdatedAt("2026-10-15T10:00:00");
        return row;
    }

    private static List<Map<Integer, String>> readAllSheetRows(byte[] xlsxBytes) {
        return EasyExcel.read(new ByteArrayInputStream(xlsxBytes))
                .sheet()
                .headRowNumber(0)
                .doReadSync();
    }

    // ==================== 成功路径：二进制 xlsx 流 ====================

    @Test
    void exportWritesXlsxStreamWithAttachmentDispositionAndBypassesApiResponse() throws Exception {
        when(supplierService.exportRows("NORMAL", "杭州")).thenReturn(List.of(
                row(100L, "杭州之江电镀有限公司", "正常/合作中", "生产制造业", "是"),
                row(101L, "杭州(second)有限公司", "正常/合作中", "服务外包商", "否")));

        MvcResult result = mockMvc.perform(get("/api/v1/suppliers/export")
                        .param("status", "NORMAL")
                        .param("keyword", "杭州"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(XLSX_CONTENT_TYPE))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=suppliers_export.xlsx"))
                .andReturn();

        byte[] bytes = result.getResponse().getContentAsByteArray();
        // xlsx 本质是 zip 包：魔数必须是 PK，绝不能是 JSON 的 "{"
        assertThat(new String(bytes, 0, 2, StandardCharsets.US_ASCII)).isEqualTo("PK");

        List<Map<Integer, String>> sheetRows = readAllSheetRows(bytes);
        // 表头 1 行 + 数据 2 行
        assertThat(sheetRows).hasSize(3);
        Map<Integer, String> header = sheetRows.get(0);
        assertThat(header.values())
                .contains("供应商名称", "统一社会信用代码", "供应商类型", "状态", "风险预警", "创建人");
        assertThat(sheetRows.get(1).values())
                .contains("杭州之江电镀有限公司", "91330100MA27XXXX8X", "生产制造业",
                        "正常/合作中", "是", "张三");
        assertThat(sheetRows.get(2).values())
                .contains("杭州(second)有限公司", "服务外包商", "正常/合作中", "否");

        verify(supplierService).exportRows("NORMAL", "杭州");
    }

    @Test
    void exportWithoutFiltersPassesNullParamsAndStillReturnsValidWorkbook() throws Exception {
        when(supplierService.exportRows(null, null)).thenReturn(List.of());

        MvcResult result = mockMvc.perform(get("/api/v1/suppliers/export"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(XLSX_CONTENT_TYPE))
                .andReturn();

        List<Map<Integer, String>> sheetRows =
                readAllSheetRows(result.getResponse().getContentAsByteArray());
        // 空命中也要产出合法工作簿：只有中文表头行，没有数据行
        assertThat(sheetRows).hasSize(1);
        assertThat(sheetRows.get(0).values()).contains("供应商名称", "状态");
        verify(supplierService).exportRows(null, null);
    }

    // ==================== 权限闸：与 §1.3 list 逐字同源 ====================

    /**
     * 需求定稿："角色与既有 §1.3 列表端点完全一致，用
     * {@code @PreAuthorize("hasAnyRole('ADMIN','STAFF','AUDITOR')")}。
     * 系统 Role 枚举恰为这三态且全部放行，无法用真实登录主体构造出 40301 越权样本
     * （匿名 40101 由集成测试覆盖）；因此这里以反射断言两条端点的闸口表达式<b>完全相等</b>，
     * 拒绝表达式漂移。403/40301 拦截机制本身已由既有集成用例锁定
     * （如 SupplierIntegrationTest#nonStaffRolesBlockedWith40301AndAnonymousWith40101）。
     */
    @Test
    void exportCarriesExactlyTheSamePreAuthorizeGateAsListEndpoint() throws Exception {
        Method list = SupplierController.class.getMethod(
                "list", String.class, String.class, int.class, int.class);
        Method export = SupplierController.class.getMethod(
                "export", String.class, String.class, HttpServletResponse.class);

        PreAuthorize listGate = list.getAnnotation(PreAuthorize.class);
        PreAuthorize exportGate = export.getAnnotation(PreAuthorize.class);

        assertThat(listGate).isNotNull();
        assertThat(exportGate).isNotNull();
        assertThat(exportGate.value())
                .isEqualTo(listGate.value())
                .isEqualTo("hasAnyRole('ADMIN', 'STAFF', 'AUDITOR')");
    }

    // ==================== 失败路径：绝不写半截二进制 ====================

    @Test
    void serviceFailureBeforeFirstByteKeepsGlobalJsonContractInsteadOfBrokenXlsx() throws Exception {
        when(supplierService.exportRows(any(), any()))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "供应商不存在"));

        MvcResult result = mockMvc.perform(get("/api/v1/suppliers/export"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40401))
                .andReturn();

        // 异常发生在任何字节写出之前（先查数、后设头、再写流），响应体必须还是完整 JSON
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body.trim()).startsWith("{");
        assertThat(result.getResponse().getContentType()).isNotEqualTo(XLSX_CONTENT_TYPE);
    }

    /** SupplierResponse（附录 A1 20 字段）里业务可见字段仍完整可查：导出旁路不得动 list 的响应形态 */
    @Test
    void listEndpointResponseShapeUntouchedByExportBypass() throws Exception {
        when(supplierService.exportRows("DRAFT", null)).thenReturn(List.of(row(
                1L, "任一", "草稿/待提交", "生产制造业", "")));

        mockMvc.perform(get("/api/v1/suppliers/export").param("status", "DRAFT"))
                .andExpect(status().isOk());

        // 行模型实例字段恰为 17 列业务可见字段（静态字典常量不计入）：
        // version/created_by/business_license_url 等并发/内部/派生占位字段不得进导出
        long instanceFields = java.util.Arrays.stream(SupplierExportRow.class.getDeclaredFields())
                .filter(f -> !java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                .count();
        assertThat(instanceFields).isEqualTo(17);
        SupplierExportRow converted = SupplierExportRow.from(sampleResponse());
        assertThat(converted.getStatus()).isEqualTo("草稿/待提交");
        assertThat(converted.getType()).isEqualTo("生产制造业");
        // 空风险标/未归档评级落空串而非 "null" 字样
        assertThat(converted.getRiskWarning()).isEmpty();
        assertThat(converted.getLatestPerformanceGrade()).isEmpty();
    }

    private static SupplierResponse sampleResponse() {
        return SupplierResponse.builder()
                .id(100L).name("杭州之江电镀有限公司").taxNo("91330100MA27XXXX8X")
                .type("MANUFACTURING").contactName("王强").contactPhone("13800138000")
                .contactEmail("wang@example.com").address("杭州市西湖区").remark("备注")
                .businessLicenseUrl(null).effectiveDate("2026-01-01").expiryDate("2028-12-31")
                .status("DRAFT").version(1).createdBy(2L).createdByName("张三")
                .createdAt("2026-10-14T10:00:00").updatedAt("2026-10-14T10:00:00")
                .latestPerformanceGrade(null).riskWarning(null).build();
    }
}
