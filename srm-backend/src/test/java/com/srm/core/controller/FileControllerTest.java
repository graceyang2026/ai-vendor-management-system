package com.srm.core.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.srm.core.common.BusinessException;
import com.srm.core.common.ErrorCode;
import com.srm.core.common.GlobalExceptionHandler;
import com.srm.core.dto.file.FileUploadResponse;
import com.srm.core.service.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 模块一 §1.6 文件上传 FileController 入参层契约测试（standalone MockMvc + 真实 GlobalExceptionHandler）。
 * 锁定行为：
 * 1. multipart 字段名必须是 {@code file}（契约 §1.6 请求参数表），透传给 Service 的正是该 part；
 * 2. 成功响应由 {@code ApiResponse} 包裹（code=0 / message=success），data 只有 file_url / file_name / size
 *    三枚字段且<b>物理 snake_case</b>——standalone MockMvc 用的是默认 ObjectMapper（无全局 SNAKE_CASE 策略），
 *    因此本用例真正校验的是 DTO 上逐字段的显式 @JsonProperty，而不是配置兜底；
 * 3. 40001（类型/超限）、40301（角色）等业务码原样经 advice 回传，HTTP 状态沿用底座既有口径；
 * 4. 容器层 {@code MaxUploadSizeExceededException} 也必须折成 40001 + 与 Service 层同一条超限文案，
 *    严禁被兜底伪装成 50001；
 * 5. 磁盘写失败（IOException）不得被悄悄吞掉，走 advice 兜底 50001。
 */
@ExtendWith(MockitoExtension.class)
class FileControllerTest {

    private static final String MSG_TYPE = "文件类型不支持，仅支持 pdf/jpg/jpeg/png 格式";
    private static final String MSG_SIZE = "文件大小不能超过10MB";

    @Mock
    private FileStorageService fileStorageService;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FileController(fileStorageService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static MockMultipartFile pdf() {
        return new MockMultipartFile("file", "营业执照.pdf", "application/pdf",
                "%PDF-1.4".getBytes(StandardCharsets.UTF_8));
    }

    private static FileUploadResponse contractResponse() {
        return FileUploadResponse.builder()
                .fileUrl("/uploads/2026/10/3f2a9c1b7d5e4a6f8b0c1d2e3f405162.pdf")
                .fileName("营业执照.pdf")
                .size(204800L)
                .build();
    }

    // ==================== 成功路径 ====================

    @Test
    void uploadMultipartPartNamedFileIsHandedToServiceAndWrappedByApiResponse() throws Exception {
        when(fileStorageService.upload(any(MultipartFile.class))).thenReturn(contractResponse());

        mockMvc.perform(multipart("/api/v1/files/upload").file(pdf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.file_url").value("/uploads/2026/10/3f2a9c1b7d5e4a6f8b0c1d2e3f405162.pdf"))
                .andExpect(jsonPath("$.data.file_name").value("营业执照.pdf"))
                .andExpect(jsonPath("$.data.size").value(204800));

        ArgumentCaptor<MultipartFile> captor = ArgumentCaptor.forClass(MultipartFile.class);
        verify(fileStorageService).upload(captor.capture());
        MultipartFile passed = captor.getValue();
        assertThat(passed.getName()).isEqualTo("file");                       // 契约字段名
        assertThat(passed.getOriginalFilename()).isEqualTo("营业执照.pdf");
        assertThat(passed.getSize()).isEqualTo(8);   // 原样透传："%PDF-1.4" 的 8 个字节
    }

    @Test
    void responseDataCarriesExactlyThreeContractFieldsInSnakeCase() throws Exception {
        when(fileStorageService.upload(any(MultipartFile.class))).thenReturn(contractResponse());

        MvcResult result = mockMvc.perform(multipart("/api/v1/files/upload").file(pdf()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = MAPPER.readTree(result.getResponse().getContentAsString()).get("data");
        List<String> names = new ArrayList<>();
        data.fieldNames().forEachRemaining(names::add);
        // 契约 §1.6 出参三枚一个不多一个不少；驼峰别名出现即为 @JsonProperty 缺失
        assertThat(names).containsExactlyInAnyOrder("file_url", "file_name", "size");
        assertThat(data.get("size").isNumber()).isTrue();
    }

    // ==================== 40001 分支 ====================

    @Test
    void illegalExtensionFromServiceReturns40001WithContractMessage() throws Exception {
        when(fileStorageService.upload(any(MultipartFile.class)))
                .thenThrow(new BusinessException(ErrorCode.VALIDATION_FAILED, MSG_TYPE));

        mockMvc.perform(multipart("/api/v1/files/upload")
                        .file(new MockMultipartFile("file", "payload.exe", "application/octet-stream", new byte[]{1})))
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(MSG_TYPE))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void oversizeFromServiceReturns40001WithContractMessage() throws Exception {
        when(fileStorageService.upload(any(MultipartFile.class)))
                .thenThrow(new BusinessException(ErrorCode.VALIDATION_FAILED, MSG_SIZE));

        mockMvc.perform(multipart("/api/v1/files/upload").file(pdf()))
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(MSG_SIZE));
    }

    @Test
    void containerLevelMaxUploadSizeExceededIsMappedTo40001Not50001() throws Exception {
        // 容器（Tomcat max-file-size）在解析阶段就抛 MaxUploadSizeExceededException，
        // 根本到不了 Service：契约要求"超限→40001"，advice 必须给出与 Service 层同一条文案。
        when(fileStorageService.upload(any(MultipartFile.class)))
                .thenThrow(new MaxUploadSizeExceededException(11L * 1024 * 1024));

        mockMvc.perform(multipart("/api/v1/files/upload").file(pdf()))
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(MSG_SIZE))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    // ==================== 鉴权与异常兜底 ====================

    @Test
    void nonStaffRoleGuardRejectionIsSurfacedAs40301() throws Exception {
        when(fileStorageService.upload(any(MultipartFile.class)))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "无权限"));

        mockMvc.perform(multipart("/api/v1/files/upload").file(pdf()))
                .andExpect(jsonPath("$.code").value(40301))
                .andExpect(jsonPath("$.message").value("无权限"));
    }

    @Test
    void storageIoFailureIsNotSwallowedAndFallsBackTo50001() throws Exception {
        when(fileStorageService.upload(any(MultipartFile.class)))
                .thenThrow(new IOException("disk full"));

        mockMvc.perform(multipart("/api/v1/files/upload").file(pdf()))
                .andExpect(jsonPath("$.code").value(50001))
                .andExpect(jsonPath("$.message").value("服务器内部错误"));
    }

    @Test
    void missingFilePartIsPassedAsNullSoServiceCanReturn40001InsteadOfFrameworkError() throws Exception {
        // @RequestPart(required=false)：缺 part 必须由 Service 统一按"文件为空"给 40001，
        // 不得让 MissingServletRequestPartException 走 advice 兜底变成 50001。
        when(fileStorageService.upload(null)).thenReturn(null);

        mockMvc.perform(multipart("/api/v1/files/upload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(fileStorageService).upload(null);
    }
}
