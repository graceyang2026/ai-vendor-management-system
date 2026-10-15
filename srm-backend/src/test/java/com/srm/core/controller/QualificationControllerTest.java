package com.srm.core.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.srm.core.common.BusinessException;
import com.srm.core.common.ErrorCode;
import com.srm.core.common.GlobalExceptionHandler;
import com.srm.core.dto.qualification.QualificationCreateRequest;
import com.srm.core.dto.qualification.QualificationResponse;
import com.srm.core.dto.qualification.QualificationReviewRequest;
import com.srm.core.service.QualificationService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 模块一 §1.7/§1.8 QualificationController 入参层契约测试
 * （standalone MockMvc + 真实 GlobalExceptionHandler，照 B1 SupplierControllerTest 范式）。
 *
 * 锁定行为：
 * 1. Bean Validation：doc_type 枚举、file_url 必填、日期语法与"expiry 晚于 effective"语义
 *    全部在 Controller 入参层一次拦下（40001 固定文案），非法入参不得触达 Service；
 * 2. QualificationResponse 13 字段物理 snake_case（附录 A2），expired/version 在位；
 * 3. 传输口径（用户裁决 2026-10-14）：业务错误维持 HTTP 200 + 响应体 code。
 * 4.（模块二 §2.3 新增）PUT /qualifications/{id}/review：请求体三字段 action/review_comment/version
 *    的物理 snake_case 绑定与入参层拦截边界——值域与版本号格式在入参层折 40001，
 *    <b>version 缺失与 review_comment 条件必填必须放行到 Service</b>（并发语义/跨字段语义），
 *    40302/40401/40901/40902 一律 HTTP 200 + body.code。
 *
 * 合法基线 body：effective_date=2026-01-01、expiry_date=2028-12-31。
 */
@ExtendWith(MockitoExtension.class)
class QualificationControllerTest {

    @Mock
    private QualificationService qualificationService;

    private MockMvc mockMvc;

    private static final String DOC_TYPE_MSG = "资质类型不合法（仅支持 BUSINESS_LICENSE/OTHER）";
    private static final String FILE_URL_MSG = "资质文件地址不能为空";
    private static final String DATE_MSG = "日期格式不合法或失效日期早于生效日期";
    /** §2.3 固定文案：40001 泛义 / 版本号格式 / 乐观锁冲突 / 驳回条件必填（契约 §2.3 L425-428 + §0 L36 注记） */
    private static final String PARAM_INVALID_MSG = "参数校验失败";
    private static final String VERSION_MSG = "版本号不合法（必须为正整数）";
    private static final String VERSION_CONFLICT_MSG = "数据已被修改，请刷新后重试";
    private static final String REJECT_COMMENT_MSG = "驳回时必须填写意见";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new QualificationController(qualificationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** 合法上传 JSON（snake_case 物理字段名，与契约 §1.7 Body 样例一致） */
    private static String validBody() {
        return """
                {
                  "doc_type": "BUSINESS_LICENSE",
                  "file_url": "/uploads/2026/10/license-abc.pdf",
                  "effective_date": "2026-01-01",
                  "expiry_date": "2028-12-31"
                }
                """;
    }

    /** 把指定字段整体替换为 value（其余保持合法）；用占位符避免正则特殊字符干扰 */
    private static String withField(String jsonField, String value) {
        return validBody().replaceFirst(
                "(\"" + jsonField + "\":\\s*)\"[^\"]*\"", "$1\"" + value.replace("\\", "\\\\") + "\"");
    }

    /**
     * 删除指定字段：走 Jackson 结构化删除而非正则拼接——正则会丢掉末字段后面的逗号处理，
     * 生成 {"a":1,} 这类非法 JSON，从而把"@NotBlank 应拦下必填缺失"测成 50001 解析异常（假阴性）。
     */
    private static String withoutField(String jsonField) {
        try {
            ObjectNode node = (ObjectNode) new ObjectMapper().readTree(validBody());
            node.remove(jsonField);
            return new ObjectMapper().writeValueAsString(node);
        } catch (Exception ex) {
            throw new IllegalStateException("构造缺失字段的 body 失败: " + jsonField, ex);
        }
    }

    private static QualificationResponse pendingSample() {
        return QualificationResponse.builder()
                .id(501L)
                .supplierId(1001L)
                .docType("BUSINESS_LICENSE")
                .fileUrl("/uploads/2026/10/license-abc.pdf")
                .effectiveDate("2026-01-01")
                .expiryDate("2028-12-31")
                .status("PENDING")
                .version(1)
                .expired(false)
                .createdAt("2026-10-14T18:00:00")
                .build();
    }

    private void expectValidationError(String bodyJson, String expectedMessage) throws Exception {
        mockMvc.perform(post("/api/v1/suppliers/1001/qualifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(expectedMessage));
        verify(qualificationService, never()).create(any(), any(QualificationCreateRequest.class));
    }

    // ==================== §1.7 POST /suppliers/{id}/qualifications ====================

    @Test
    void uploadValidBodyReturnsThirteenSnakeCaseFieldsCodeZero() throws Exception {
        when(qualificationService.create(eq(1001L), any(QualificationCreateRequest.class)))
                .thenReturn(pendingSample());

        mockMvc.perform(post("/api/v1/suppliers/1001/qualifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.id").value(501))
                .andExpect(jsonPath("$.data.supplier_id").value(1001))
                .andExpect(jsonPath("$.data.doc_type").value("BUSINESS_LICENSE"))
                .andExpect(jsonPath("$.data.file_url").value("/uploads/2026/10/license-abc.pdf"))
                .andExpect(jsonPath("$.data.effective_date").value("2026-01-01"))
                .andExpect(jsonPath("$.data.expiry_date").value("2028-12-31"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.expired").value(false))
                .andExpect(jsonPath("$.data.created_at").value("2026-10-14T18:00:00"))
                .andExpect(jsonPath("$.data.reviewed_by").value(nullValue()))
                .andExpect(jsonPath("$.data.reviewed_at").value(nullValue()))
                .andExpect(jsonPath("$.data.review_comment").value(nullValue()));

        ArgumentCaptor<QualificationCreateRequest> captor =
                ArgumentCaptor.forClass(QualificationCreateRequest.class);
        verify(qualificationService).create(eq(1001L), captor.capture());
        assertThat(captor.getValue().getDocType()).isEqualTo("BUSINESS_LICENSE");
        assertThat(captor.getValue().getFileUrl()).isEqualTo("/uploads/2026/10/license-abc.pdf");
        assertThat(captor.getValue().getEffectiveDate()).isEqualTo("2026-01-01");
    }

    @Test
    void uploadRejectsDocTypeChineseLowercaseForeignAndMissing() throws Exception {
        expectValidationError(withField("doc_type", "营业执照"), DOC_TYPE_MSG);
        expectValidationError(withField("doc_type", "business_license"), DOC_TYPE_MSG);
        expectValidationError(withField("doc_type", "ID_CARD"), DOC_TYPE_MSG);
        expectValidationError(withField("doc_type", "   "), DOC_TYPE_MSG);
        expectValidationError(withoutField("doc_type"), DOC_TYPE_MSG);
    }

    @Test
    void uploadRejectsMissingOrBlankFileUrl() throws Exception {
        expectValidationError(withoutField("file_url"), FILE_URL_MSG);
        expectValidationError(withField("file_url", "   "), FILE_URL_MSG);
    }

    @Test
    void uploadRejectsDateSyntaxFailures() throws Exception {
        // 语法非法（非 yyyy-MM-dd），@Pattern 直接拦下
        expectValidationError(withField("expiry_date", "20281231"), DATE_MSG);
    }

    @Test
    void uploadRejectsSemanticallyInvalidDateAndMissingDate() throws Exception {
        // 2026-02-30 过正则但 LocalDate.parse 失败（跨字段校验兜住）→ 40001，不得冒 50001
        expectValidationError(withField("effective_date", "2026-02-30"), DATE_MSG);
        // 缺失日期字段 → @NotBlank 拦下
        expectValidationError(withoutField("expiry_date"), DATE_MSG);
        expectValidationError(withoutField("effective_date"), DATE_MSG);
    }

    @Test
    void uploadRejectsExpiryNotStrictlyAfterEffective() throws Exception {
        // 相等与倒挂两种非法序都归 DATE_MSG（契约"expiry 晚于 effective"）
        expectValidationError(withField("effective_date", "2028-12-31"), DATE_MSG); // == expiry
        expectValidationError(withField("effective_date", "2029-01-01"), DATE_MSG); // > expiry
    }

    @Test
    void uploadBusinessErrorsKeepHttp200WithBodyCodePerTransportRuling() throws Exception {
        when(qualificationService.create(eq(1001L), any(QualificationCreateRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "供应商不存在"));
        mockMvc.perform(post("/api/v1/suppliers/1001/qualifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40401))
                .andExpect(jsonPath("$.message").value("供应商不存在"));

        when(qualificationService.create(eq(1001L), any(QualificationCreateRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "当前状态不允许该操作"));
        mockMvc.perform(post("/api/v1/suppliers/1001/qualifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40302))
                .andExpect(jsonPath("$.message").value("当前状态不允许该操作"));
    }

    // ==================== §1.8 GET /suppliers/{id}/qualifications ====================

    @Test
    void listReturnsArrayRowsCarryingVersionAndExpired() throws Exception {
        QualificationResponse approved = QualificationResponse.builder()
                .id(501L).supplierId(1001L).docType("BUSINESS_LICENSE")
                .fileUrl("/uploads/2026/10/a.pdf")
                .effectiveDate("2024-01-01").expiryDate("2025-06-30")
                .status("APPROVED").version(2).expired(true)
                .createdAt("2026-10-14T09:00:00")
                .reviewedBy(3L).reviewedAt("2026-10-02T15:00:00").reviewComment("已通过")
                .build();
        when(qualificationService.list(1001L)).thenReturn(List.of(approved, pendingSample()));

        mockMvc.perform(get("/api/v1/suppliers/1001/qualifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.data[0].version").value(2))
                .andExpect(jsonPath("$.data[0].expired").value(true))
                .andExpect(jsonPath("$.data[0].reviewed_by").value(3))
                .andExpect(jsonPath("$.data[0].reviewed_at").value("2026-10-02T15:00:00"))
                .andExpect(jsonPath("$.data[0].review_comment").value("已通过"))
                .andExpect(jsonPath("$.data[1].status").value("PENDING"))
                .andExpect(jsonPath("$.data[1].expired").value(false));

        verify(qualificationService).list(1001L);
    }

    @Test
    void listBusinessErrorsKeepHttp200WithBodyCode() throws Exception {
        when(qualificationService.list(1001L))
                .thenThrow(new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "仅限本人操作"));
        mockMvc.perform(get("/api/v1/suppliers/1001/qualifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40302));

        when(qualificationService.list(2002L))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "供应商不存在"));
        mockMvc.perform(get("/api/v1/suppliers/2002/qualifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40401));
    }

    // ==================== §2.3 PUT /qualifications/{id}/review ====================

    /** §2.3 合法请求体：字段名严格 action / review_comment / version（契约 §2.3 L393-400 样例） */
    private static String reviewBody(String action, String reviewCommentJson, String versionJson) {
        return "{"
                + "\"action\":\"" + action + "\","
                + "\"review_comment\":" + reviewCommentJson + ","
                + "\"version\":" + versionJson
                + "}";
    }

    private static QualificationResponse reviewedSample() {
        return QualificationResponse.builder()
                .id(501L)
                .supplierId(1001L)
                .docType("BUSINESS_LICENSE")
                .fileUrl("/uploads/2026/10/license-abc.pdf")
                .effectiveDate("2026-01-01")
                .expiryDate("2028-12-31")
                .status("REJECTED")
                .version(2)
                .expired(false)
                .createdAt("2026-10-14T18:00:00")
                .reviewedBy(3L)
                .reviewedAt("2026-10-15T10:00:00")
                .reviewComment("证照模糊无法辨识有效期，请重新上传。")
                .build();
    }

    private void expectReviewValidationError(String bodyJson, String expectedMessage) throws Exception {
        mockMvc.perform(put("/api/v1/qualifications/501/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(expectedMessage));
        verify(qualificationService, never()).review(any(), any(QualificationReviewRequest.class));
    }

    @Test
    void reviewValidBodyBindsSnakeCaseFieldsAndReturnsThirteenResponseFields() throws Exception {
        when(qualificationService.review(eq(501L), any(QualificationReviewRequest.class)))
                .thenReturn(reviewedSample());

        mockMvc.perform(put("/api/v1/qualifications/501/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody("REJECT", "\"证照模糊无法辨识有效期，请重新上传。\"", "1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(501))
                .andExpect(jsonPath("$.data.supplier_id").value(1001))
                .andExpect(jsonPath("$.data.doc_type").value("BUSINESS_LICENSE"))
                .andExpect(jsonPath("$.data.file_url").value("/uploads/2026/10/license-abc.pdf"))
                .andExpect(jsonPath("$.data.effective_date").value("2026-01-01"))
                .andExpect(jsonPath("$.data.expiry_date").value("2028-12-31"))
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.expired").value(false))
                .andExpect(jsonPath("$.data.created_at").value("2026-10-14T18:00:00"))
                .andExpect(jsonPath("$.data.reviewed_by").value(3))
                .andExpect(jsonPath("$.data.reviewed_at").value("2026-10-15T10:00:00"))
                .andExpect(jsonPath("$.data.review_comment")
                        .value("证照模糊无法辨识有效期，请重新上传。"));

        ArgumentCaptor<QualificationReviewRequest> captor =
                ArgumentCaptor.forClass(QualificationReviewRequest.class);
        verify(qualificationService).review(eq(501L), captor.capture());
        // 入参叫 action（不是 decision）、review_comment（无复数 s）——契约裁决 2026-10-12 定稿
        assertThat(captor.getValue().getAction()).isEqualTo("REJECT");
        assertThat(captor.getValue().getReviewComment()).isEqualTo("证照模糊无法辨识有效期，请重新上传。");
        assertThat(captor.getValue().getVersion()).isEqualTo(1);
    }

    @Test
    void reviewApproveWithoutCommentPassesInputLayerBecauseCommentIsConditionallyRequired() throws Exception {
        // APPROVE 分支 review_comment 可省：条件必填只能由 Service 结合 action 判定，入参层不得一刀切 @NotBlank
        when(qualificationService.review(eq(501L), any(QualificationReviewRequest.class)))
                .thenReturn(reviewedSample());

        mockMvc.perform(put("/api/v1/qualifications/501/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody("APPROVE", "null", "1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<QualificationReviewRequest> captor =
                ArgumentCaptor.forClass(QualificationReviewRequest.class);
        verify(qualificationService).review(eq(501L), captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("APPROVE");
        assertThat(captor.getValue().getReviewComment()).isNull();
    }

    @Test
    void reviewActionOutsideEnumIsRejectedAtInputLayerWith40001() throws Exception {
        expectReviewValidationError(reviewBody("PASS", "\"意见\"", "1"), PARAM_INVALID_MSG);
        expectReviewValidationError(reviewBody("approve", null, "1"), PARAM_INVALID_MSG);
        expectReviewValidationError(reviewBody("APPROVED", null, "1"), PARAM_INVALID_MSG);
        expectReviewValidationError(reviewBody("", null, "1"), PARAM_INVALID_MSG);
    }

    @Test
    void reviewMissingActionIsRejectedAtInputLayerWith40001() throws Exception {
        expectReviewValidationError("{\"review_comment\":\"意见\",\"version\":1}", PARAM_INVALID_MSG);
    }

    @Test
    void reviewNonPositiveVersionIsRejectedAtInputLayerWith40001() throws Exception {
        // 0/负数属格式非法（入参层）；缺失则属并发语义，见下一用例 → 两码必须分层
        expectReviewValidationError(reviewBody("APPROVE", null, "0"), VERSION_MSG);
        expectReviewValidationError(reviewBody("APPROVE", null, "-3"), VERSION_MSG);
    }

    @Test
    void reviewMissingVersionIsPassedThroughToServiceBecauseItIsConcurrencySemantics() throws Exception {
        when(qualificationService.review(eq(501L), any(QualificationReviewRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, VERSION_CONFLICT_MSG));

        mockMvc.perform(put("/api/v1/qualifications/501/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\",\"review_comment\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40901));

        ArgumentCaptor<QualificationReviewRequest> captor =
                ArgumentCaptor.forClass(QualificationReviewRequest.class);
        verify(qualificationService).review(eq(501L), captor.capture());
        assertThat(captor.getValue().getVersion()).isNull();
    }

    @Test
    void reviewRejectWithoutCommentKeepsContractMessageThroughServiceLayer() throws Exception {
        // 条件必填属 Service 判定：入参层放行、Service 折 40001，文案逐字照契约 §2.3 L425
        when(qualificationService.review(eq(501L), any(QualificationReviewRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.VALIDATION_FAILED, REJECT_COMMENT_MSG));

        mockMvc.perform(put("/api/v1/qualifications/501/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody("REJECT", "null", "1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(REJECT_COMMENT_MSG));
    }

    @Test
    void reviewBusinessErrorsKeepHttp200WithBodyCode() throws Exception {
        when(qualificationService.review(eq(777L), any(QualificationReviewRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "当前状态不允许该操作"));
        mockMvc.perform(put("/api/v1/qualifications/777/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody("APPROVE", null, "1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40302));

        when(qualificationService.review(eq(888L), any(QualificationReviewRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "资源不存在"));
        mockMvc.perform(put("/api/v1/qualifications/888/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody("APPROVE", null, "1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40401));

        when(qualificationService.review(eq(999L), any(QualificationReviewRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.DUPLICATE_IN_PROGRESS, "业务冲突"));
        mockMvc.perform(put("/api/v1/qualifications/999/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody("APPROVE", null, "1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40902))
                .andExpect(jsonPath("$.message").value("业务冲突"));
    }
}
