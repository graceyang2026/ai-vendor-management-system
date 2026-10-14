package com.srm.core.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.srm.core.common.BusinessException;
import com.srm.core.common.ErrorCode;
import com.srm.core.common.GlobalExceptionHandler;
import com.srm.core.dto.qualification.QualificationCreateRequest;
import com.srm.core.dto.qualification.QualificationResponse;
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
}
