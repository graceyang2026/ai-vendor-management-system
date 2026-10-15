package com.srm.core.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.srm.core.common.BusinessException;
import com.srm.core.common.ErrorCode;
import com.srm.core.common.GlobalExceptionHandler;
import com.srm.core.common.PageResult;
import com.srm.core.dto.supplier.SupplierCreateRequest;
import com.srm.core.dto.supplier.SupplierAuditDecisionRequest;
import com.srm.core.dto.supplier.SupplierResponse;
import com.srm.core.dto.supplier.SupplierSubmitRequest;
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
 *
 * 模块二追加（§2.1 submit / §2.2 audit 入参层边界）：
 * 6. 入参层只拦<b>格式</b>：decision 值域、version 的 0/负数 → 40001 固定文案，非法入参不得触达 Service；
 * 7. <b>version 缺失必须放行到 Service</b>（契约 §2.1 L312 把缺失与不匹配同归 40901，属并发语义），
 *    <b>REJECT 缺 comment 同样放行到 Service</b>（跨字段条件必填，注解表达不了）——两条都靠
 *    "Service 被调用 + 捕获到 null" 正向锁定，而不是只断言错误码；
 * 8. 40302/40401/40901/40902 一律 HTTP 200 + body.code（用户裁决 2026-10-14 传输口径）。
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
    // 模块二固定文案（契约 §2.1/§2.2 矩阵 + §0 L36 乐观锁文案权威注记）
    private static final String PARAM_INVALID_MSG = "参数校验失败";
    private static final String VERSION_MSG = "版本号不合法（必须为正整数）";
    private static final String VERSION_CONFLICT_MSG = "数据已被修改，请刷新后重试";
    private static final String REJECT_COMMENT_MSG = "驳回时必须填写意见";

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

    // ==================== 模块二 §2.1 POST /suppliers/{id}/submit ====================

    private static SupplierResponse statusResponse(String status, int version) {
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
                .businessLicenseUrl(null)
                .effectiveDate("2026-01-01")
                .expiryDate("2028-12-31")
                .status(status)
                .version(version)
                .createdBy(2L)
                .createdByName("张三")
                .createdAt("2026-10-14T10:00:00")
                .updatedAt("2026-10-15T10:00:00")
                .latestPerformanceGrade(null)
                .riskWarning(null)
                .build();
    }

    private void expectSubmitValidationError(String bodyJson, String expectedMessage) throws Exception {
        mockMvc.perform(post("/api/v1/suppliers/100/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(expectedMessage));
        verify(supplierService, never()).submit(anyLong(), any(SupplierSubmitRequest.class));
    }

    @Test
    void submitBindsVersionOnlyBodyAndReturnsPendingReviewShape() throws Exception {
        when(supplierService.submit(eq(100L), any(SupplierSubmitRequest.class)))
                .thenReturn(statusResponse("PENDING_REVIEW", 2));

        mockMvc.perform(post("/api/v1/suppliers/100/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.created_by_name").value("张三"))
                // 派生三枚仍归二期：submit 出参与 §1.4 同一 SupplierResponse 形态，不得偷跑计算
                .andExpect(jsonPath("$.data.risk_warning").value(nullValue()))
                .andExpect(jsonPath("$.data.business_license_url").value(nullValue()));

        ArgumentCaptor<SupplierSubmitRequest> captor = ArgumentCaptor.forClass(SupplierSubmitRequest.class);
        verify(supplierService).submit(eq(100L), captor.capture());
        assertThat(captor.getValue().getVersion()).isEqualTo(1);
    }

    @Test
    void submitMissingVersionIsPassedThroughToServiceBecauseItIsConcurrencySemantics() throws Exception {
        // 契约 §2.1 L312：version 缺失或不匹配 → 40901，属并发语义，入参层不得抢先折 40001
        when(supplierService.submit(eq(100L), any(SupplierSubmitRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, VERSION_CONFLICT_MSG));

        mockMvc.perform(post("/api/v1/suppliers/100/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40901));

        ArgumentCaptor<SupplierSubmitRequest> captor = ArgumentCaptor.forClass(SupplierSubmitRequest.class);
        verify(supplierService).submit(eq(100L), captor.capture());
        assertThat(captor.getValue().getVersion()).isNull();
    }

    @Test
    void submitNonPositiveVersionRejectedAtInputLayer() throws Exception {
        expectSubmitValidationError("{\"version\":0}", VERSION_MSG);
        expectSubmitValidationError("{\"version\":-1}", VERSION_MSG);
    }

    @Test
    void submitBusinessErrorsKeepHttp200WithBodyCode() throws Exception {
        when(supplierService.submit(eq(100L), any(SupplierSubmitRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.VALIDATION_FAILED, "请先上传有效期内的营业执照"));
        mockMvc.perform(post("/api/v1/suppliers/100/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("请先上传有效期内的营业执照"));

        when(supplierService.submit(eq(200L), any(SupplierSubmitRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "当前状态不允许该操作"));
        mockMvc.perform(post("/api/v1/suppliers/200/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40302));

        when(supplierService.submit(eq(300L), any(SupplierSubmitRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "供应商不存在"));
        mockMvc.perform(post("/api/v1/suppliers/300/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40401));
    }

    // ==================== 模块二 §2.2 POST /suppliers/{id}/audit ====================

    private static String auditBody(String decisionJson, String commentJson, String versionJson) {
        return "{\"version\":" + versionJson + ",\"decision\":" + decisionJson
                + ",\"comment\":" + commentJson + "}";
    }

    private void expectAuditValidationError(String bodyJson, String expectedMessage) throws Exception {
        mockMvc.perform(post("/api/v1/suppliers/100/audit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(expectedMessage));
        verify(supplierService, never()).audit(anyLong(), any(SupplierAuditDecisionRequest.class));
    }

    @Test
    void auditBindsThreeFieldsAndReturnsNormalShapeOnApprove() throws Exception {
        when(supplierService.audit(eq(100L), any(SupplierAuditDecisionRequest.class)))
                .thenReturn(statusResponse("NORMAL", 3));

        mockMvc.perform(post("/api/v1/suppliers/100/audit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(auditBody("\"APPROVE\"", "null", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("NORMAL"))
                .andExpect(jsonPath("$.data.version").value(3));

        ArgumentCaptor<SupplierAuditDecisionRequest> captor =
                ArgumentCaptor.forClass(SupplierAuditDecisionRequest.class);
        verify(supplierService).audit(eq(100L), captor.capture());
        assertThat(captor.getValue().getDecision()).isEqualTo("APPROVE");
        assertThat(captor.getValue().getVersion()).isEqualTo(2);
        assertThat(captor.getValue().getComment()).isNull();
    }

    @Test
    void auditDecisionOutsideEnumRejectedAtInputLayer() throws Exception {
        expectAuditValidationError(auditBody("\"PASS\"", "\"意见\"", "2"), PARAM_INVALID_MSG);
        expectAuditValidationError(auditBody("\"approve\"", null, "2"), PARAM_INVALID_MSG);
        expectAuditValidationError(auditBody("\"APPROVED\"", null, "2"), PARAM_INVALID_MSG);
        expectAuditValidationError(auditBody("\"\"", null, "2"), PARAM_INVALID_MSG);
        expectAuditValidationError("{\"version\":2,\"comment\":\"缺 decision\"}", PARAM_INVALID_MSG);
    }

    @Test
    void auditNonPositiveVersionRejectedAtInputLayer() throws Exception {
        expectAuditValidationError(auditBody("\"REJECT\"", "\"意见\"", "0"), VERSION_MSG);
        expectAuditValidationError(auditBody("\"REJECT\"", "\"意见\"", "-2"), VERSION_MSG);
    }

    @Test
    void auditRejectWithoutCommentIsJudgedByServiceNotInputLayer() throws Exception {
        // 条件必填（decision=REJECT 才必填）无法由单字段注解表达：入参层必须放行，Service 折 40001
        when(supplierService.audit(eq(100L), any(SupplierAuditDecisionRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.VALIDATION_FAILED, REJECT_COMMENT_MSG));

        mockMvc.perform(post("/api/v1/suppliers/100/audit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(auditBody("\"REJECT\"", "null", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(REJECT_COMMENT_MSG));

        ArgumentCaptor<SupplierAuditDecisionRequest> captor =
                ArgumentCaptor.forClass(SupplierAuditDecisionRequest.class);
        verify(supplierService).audit(eq(100L), captor.capture());
        assertThat(captor.getValue().getComment()).isNull();
    }

    @Test
    void auditMissingVersionIsPassedThroughToServiceAndKeeps40901() throws Exception {
        when(supplierService.audit(eq(100L), any(SupplierAuditDecisionRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, VERSION_CONFLICT_MSG));

        mockMvc.perform(post("/api/v1/suppliers/100/audit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40901));
        verify(supplierService).audit(eq(100L), any(SupplierAuditDecisionRequest.class));
    }

    @Test
    void auditBusinessErrorsKeepHttp200WithBodyCode() throws Exception {
        when(supplierService.audit(eq(700L), any(SupplierAuditDecisionRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "当前状态不允许该操作"));
        mockMvc.perform(post("/api/v1/suppliers/700/audit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(auditBody("\"APPROVE\"", null, "1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40302));

        when(supplierService.audit(eq(800L), any(SupplierAuditDecisionRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.DUPLICATE_IN_PROGRESS, "业务冲突"));
        mockMvc.perform(post("/api/v1/suppliers/800/audit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(auditBody("\"APPROVE\"", null, "1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40902));
    }
}
