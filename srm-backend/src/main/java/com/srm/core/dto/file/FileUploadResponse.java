package com.srm.core.dto.file;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 模块一 §1.6 文件上传响应体（契约：docs/接口契约文档-供应商与绩效-20261013.md §1.6 成功响应样例
 * + docs/api-spec.md「文件上传」{@code FileUploadResponse}）。
 *
 * 出参<b>只有三枚字段</b>：{@code file_url}（形如 {@code /uploads/2026/10/xxx.pdf}，直接填入
 * {@code QualificationCreateRequest.file_url}）、{@code file_name}（原始文件名，仅作回显，
 * <b>不参与落盘路径</b>）、{@code size}（字节数）。
 * 本 DTO 刻意不含任何 id/表字段/分数评级字段——本接口不写业务表，绩效防篡改铁律同样要求
 * 响应体不得出现可被前端写入的分数字段。
 *
 * JSON 字段物理 snake_case：逐字段显式 {@code @JsonProperty}，不依赖全局 SNAKE_CASE 配置
 * （standalone MockMvc 用的是默认 ObjectMapper，正是靠这些注解保证契约形态）。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class FileUploadResponse {

    @JsonProperty("file_url")
    private String fileUrl;

    @JsonProperty("file_name")
    private String fileName;

    /** 文件字节数，取自 {@code MultipartFile.getSize()}，与业务上限 10*1024*1024 同一判据 */
    @JsonProperty("size")
    private long size;
}
