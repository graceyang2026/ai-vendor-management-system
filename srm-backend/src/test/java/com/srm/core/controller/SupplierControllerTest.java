package com.srm.core.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.srm.core.common.BusinessException;
import com.srm.core.common.ErrorCode;
import com.srm.core.common.GlobalExceptionHandler;
import com.srm.core.common.PageResult;
import com.srm.core.dto.supplier.SupplierCreateRequest;
import com.srm.core.dto.supplier.SupplierResponse;
import com.srm.core.dto.supplier.SupplierUpdateRequest;
import com.srm.core.service.SupplierService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 模块一 §1.1–§1.5 SupplierController 入参层契约测试（standalone MockMvc + 真实 GlobalExceptionHandler）：
 * 1. Bean Validation 正则与固定中文 message 与契约逐字同源（api-spec §2 校验表 L158-172）
 * 2. 校验顺序：格式 40001 先行——非法入参不得触达 Service（唯一性 40902 在 Service，无先后冲突）
 * 3. SupplierResponse 20 字段物理 snake_case；三枚派生字段本批恒 null（裁决 2026-10-14 16:15）
 * 4. PUT Partial：version 必带 >= 1；只对提交字段校验
 * 5. 列表 query 参数 status/keyword/page/page_size 透传与默认值；删除成功 data=null
 */
@ExtendWith(MockitoExtension.class)
class SupplierControllerTest {

    @Mock
    private SupplierService supplierService;

    private MockMvc mockMvc;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String NAME_MSG = "供应商名称格式不合法（不得为空格且长度需为2~64字符）";
    private static final String TAX_MSG = "统一社会信用代码格式不合法（必须为18位有效字符）";
    private static final String TYPE_MSG = "供应商类型不合法";
    private static final String CONTACT_NAME_MSG = "联系人姓名格式不合法（不得为空格且长度需为2~64字符）";
    private static final String PHONE_MSG = "联系电话格式不合法（必须为11位有效手机号）";
    private static final String EMAIL_MSG = "联系邮箱格式不合法";
    private static final String DATE_MSG = "日期格式不合法或失效日期早于生效日期";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SupplierController(supplierService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** 合法建档 JSON（snake_case 物理字段名，与 §1.1 请求样例一致） */
    private static String validCreateBody() {
        return """
                {
                  "name": "杭州之江电镀有限公司",
                  "tax_no": "91330100MA27XXXX8X",
                  "type": "MANUFACTURING",
                  "contact_name": "王强",
                  "contact_phone": "13800138000",
                  "contact_email": "wang@example.com",
                  "address": "杭州市西湖区",
                  "remark": "备注",
                  "effective_date": "2026-01-01",
                  "expiry_date": "2028-12-31"
                }
                """;
    }

    private static SupplierResponse sampleResponse() {
        return SupplierResponse.builder()
                .id(100L)
                .name("杭州之江电镀有限公司")
                .taxNo("91330100MA27XXXX8X")
                .type("MANUFACTURING")
                .contactName("王强")
                .contactPhone("13800138000")
                .contactEmail("wang@example.com")
                .address("杭州市西湖区")
                .remark("备注")
                .businessLicenseUrl(null)          // 派生归二期
                .effectiveDate("2026-01-01")
                .expiryDate("2028-12-31")
                .status("DRAFT")
                .version(1)
                .createdBy(2L)
                .createdByName("张三")
                .createdAt("2026-10-14T10:00:00")
                .updatedAt("2026-10-14T10:00:00")
                .latestPerformanceGrade(null)      // 派生归二期
                .riskWarning(null)                 // 派生归二期
                .build();
    }

    private void expectValidationError(String bodyJson, String expectedMessage) throws Exception {
        mockMvc.perform(post("/api/v1/suppliers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(expectedMessage));
        verify(supplierService, never()).create(any(SupplierCreateRequest.class));
    }

    private static String withField(String value) {
        return validCreateBody().replace("\"杭州之江电镀有限公司\"", "\"" + value + "\"");
    }

    // ==================== §1.1 POST /suppliers ====================

    @Test
    void createValidBodyReturnsContractResponseWithSnakeCaseFields() throws Exception {
        when(supplierService.create(any(SupplierCreateRequest.class))).thenReturn(sampleResponse());

        mockMvc.perform(post("/api/v1/suppliers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.tax_no").value("91330100MA27XXXX8X"))
                .andExpect(jsonPath("$.data.contact_name").value("王强"))
                .andExpect(jsonPath("$.data.contact_phone").value("13800138000"))
                .andExpect(jsonPath("$.data.contact_email").value("wang@example.com"))
                .andExpect(jsonPath("$.data.effective_date").value("2026-01-01"))
                .andExpect(jsonPath("$.data.expiry_date").value("2028-12-31"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.created_by").value(2))
                .andExpect(jsonPath("$.data.created_by_name").value("张三"))
                .andExpect(jsonPath("$.data.created_at").value("2026-10-14T10:00:00"))
                .andExpect(jsonPath("$.data.updated_at").value("2026-10-14T10:00:00"))
                // 20 字段一个不能少，派生三枚在位但为 null
                .andExpect(jsonPath("$.data.business_license_url").value(nullValue()))
                .andExpect(jsonPath("$.data.latest_performance_grade").value(nullValue()))
                .andExpect(jsonPath("$.data.risk_warning").value(nullValue()));

        ArgumentCaptor<SupplierCreateRequest> captor =
                ArgumentCaptor.forClass(SupplierCreateRequest.class);
        verify(supplierService).create(captor.capture());
        assertThat(captor.getValue().getTaxNo()).isEqualTo("91330100MA27XXXX8X");
        assertThat(captor.getValue().getContactName()).isEqualTo("王强");
        assertThat(captor.getValue().getEffectiveDate()).isEqualTo("2026-01-01");
    }

    @Test
    void createNameTooShortOrTooLongOrSpacesRejected() throws Exception {
        expectValidationError(withField("杭"), NAME_MSG);           // 1 字符
        expectValidationError(withField(" 杭州 之江 "), NAME_MSG);   // 首尾空白
        expectValidationError(withField("名".repeat(65)), NAME_MSG); // 65 字符超上限
    }

    @Test
    void createTaxNoIllegalCharsRejected() throws Exception {
        expectValidationError(validCreateBody().replace("91330100MA27XXXX8X", "91330100MA27XXXX8I"), TAX_MSG);
        expectValidationError(validCreateBody().replace("91330100MA27XXXX8X", "91330100MA27XXXX8"), TAX_MSG);
        expectValidationError(validCreateBody().replace("91330100MA27XXXX8X", "91330100ma27xxxx8x"), TAX_MSG);
        expectValidationError(validCreateBody().replace("91330100MA27XXXX8X", "91330100MA27XXXX8O"), TAX_MSG);
    }

    @Test
    void createChineseTypeRejected() throws Exception {
        expectValidationError(validCreateBody().replace("\"MANUFACTURING\"", "\"制造业\""), TYPE_MSG);
    }

    @Test
    void createContactNameTooLongRejected() throws Exception {
        expectValidationError(validCreateBody().replace("\"王强\"", "\"" + "联".repeat(65) + "\""), CONTACT_NAME_MSG);
    }

    @Test
    void createLandlinePhoneRejected() throws Exception {
        expectValidationError(validCreateBody().replace("13800138000", "01088886666"), PHONE_MSG);
        expectValidationError(validCreateBody().replace("13800138000", "12800138000"), PHONE_MSG);
    }

    @Test
    void createBadEmailRejectedOptionalFieldValidatedWhenPresent() throws Exception {
        expectValidationError(validCreateBody().replace("wang@example.com", "not-an-email"), EMAIL_MSG);
    }

    @Test
    void createDateRulesEnforced() throws Exception {
        // 到期日等于生效日（契约要求"晚于"）
        expectValidationError(validCreateBody().replace("2028-12-31", "2026-01-01"), DATE_MSG);
        // 到期日早于生效日
        expectValidationError(validCreateBody().replace("2028-12-31", "2025-12-31"), DATE_MSG);
        // 语法合法但语义非法日期（2 月 30 日）
        expectValidationError(validCreateBody().replace("2026-01-01", "2026-02-30"), DATE_MSG);
    }

    @Test
    void createMissingRequiredFieldsRejected40001() throws Exception {
        mockMvc.perform(post("/api/v1/suppliers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(jsonPath("$.code").value(40001));
        verify(supplierService, never()).create(any(SupplierCreateRequest.class));
    }

    // ==================== §1.2 PUT /suppliers/{id} ====================

    @Test
    void putWithoutVersionRejected40001() throws Exception {
        mockMvc.perform(put("/api/v1/suppliers/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新名称\"}"))
                .andExpect(jsonPath("$.code").value(40001));
        verify(supplierService, never()).update(anyLong(), any(SupplierUpdateRequest.class));
    }

    @Test
    void putVersionBelowOneRejected40001() throws Exception {
        mockMvc.perform(put("/api/v1/suppliers/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新名称\",\"version\":0}"))
                .andExpect(jsonPath("$.code").value(40001));
        verify(supplierService, never()).update(anyLong(), any(SupplierUpdateRequest.class));
    }

    /** Partial：只提交 name+version，其余字段保持 null 入参（不触发必填拒绝） */
    @Test
    void putPartialOnlySubmittedFieldsValidated() throws Exception {
        when(supplierService.update(eq(1L), any(SupplierUpdateRequest.class))).thenReturn(sampleResponse());

        mockMvc.perform(put("/api/v1/suppliers/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"整改后名称\",\"version\":2}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        ArgumentCaptor<SupplierUpdateRequest> captor = ArgumentCaptor.forClass(SupplierUpdateRequest.class);
        verify(supplierService).update(eq(1L), captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("整改后名称");
        assertThat(captor.getValue().getTaxNo()).isNull();
        assertThat(captor.getValue().getVersion()).isEqualTo(2);
    }

    @Test
    void putSubmittedBadFieldRejectedBeforeService() throws Exception {
        mockMvc.perform(put("/api/v1/suppliers/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tax_no\":\"short\",\"version\":2}"))
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(TAX_MSG));
        verify(supplierService, never()).update(anyLong(), any(SupplierUpdateRequest.class));
    }

    // ==================== §1.3 GET /suppliers ====================

    @Test
    void listDefaultParamsPage1PageSize20() throws Exception {
        when(supplierService.list(null, null, 1, 20))
                .thenReturn(new PageResult<>(List.of(sampleResponse()), 1, 1, 20));

        mockMvc.perform(get("/api/v1/suppliers"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list[0].tax_no").value("91330100MA27XXXX8X"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.page_size").value(20));

        verify(supplierService).list(null, null, 1, 20);
    }

    @Test
    void listPassesStatusKeywordAndPagingParams() throws Exception {
        when(supplierService.list("DRAFT", "杭州", 2, 50))
                .thenReturn(new PageResult<>(List.of(), 0, 2, 50));

        mockMvc.perform(get("/api/v1/suppliers")
                        .param("status", "DRAFT")
                        .param("keyword", "杭州")
                        .param("page", "2")
                        .param("page_size", "50"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list").isArray());

        verify(supplierService).list("DRAFT", "杭州", 2, 50);
    }

    // ==================== §1.4 GET /suppliers/{id} ====================

    @Test
    void detailReturnsSupplierResponseShape() throws Exception {
        when(supplierService.get(100L)).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/suppliers/100"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.created_by_name").value("张三"))
                .andExpect(jsonPath("$.data.business_license_url").value(nullValue()));
    }

    @Test
    void detailPropagatesNotFound40401() throws Exception {
        when(supplierService.get(anyLong()))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "供应商不存在"));

        mockMvc.perform(get("/api/v1/suppliers/999"))
                .andExpect(jsonPath("$.code").value(40401));
    }

    // ==================== §1.5 DELETE /suppliers/{id} ====================

    @Test
    void deleteSuccessReturnsNullData() throws Exception {
        mockMvc.perform(delete("/api/v1/suppliers/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verify(supplierService).delete(100L);
    }

    @Test
    void deleteStatusNotAllowed40302Propagates() throws Exception {
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "仅草稿状态可删除"))
                .when(supplierService).delete(1L);

        mockMvc.perform(delete("/api/v1/suppliers/1"))
                .andExpect(jsonPath("$.code").value(40302));
    }

    /** 只提交 effective_date 而漏掉 expiry_date：跨字段校验放行，由必填约束给出唯一一条 DATE_MSG 文案 */
    @Test
    void createWithOnlyExpiryDateMissingReportsSingleContractDateMessage() throws Exception {
        mockMvc.perform(post("/api/v1/suppliers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "杭州之江电镀有限公司",
                                  "tax_no": "91330100MA27XXXX8X",
                                  "type": "MANUFACTURING",
                                  "contact_name": "王强",
                                  "contact_phone": "13800138000",
                                  "effective_date": "2026-01-01"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(DATE_MSG));
        verify(supplierService, never()).create(any(SupplierCreateRequest.class));
    }
}
