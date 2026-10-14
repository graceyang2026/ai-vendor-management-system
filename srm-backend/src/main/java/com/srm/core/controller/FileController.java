package com.srm.core.controller;

import com.srm.core.common.ApiResponse;
import com.srm.core.dto.file.FileUploadConstraints;
import com.srm.core.dto.file.FileUploadResponse;
import com.srm.core.service.FileStorageService;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 模块一 §1.6 文件上传接口（契约：docs/接口契约文档-供应商与绩效-20261013.md §1.6
 * + docs/api-spec.md「文件上传」）：建档/资质文件先传这里换 {@code file_url}，再把 URL 填进 §1.7 资质请求体。
 *
 * 职责边界：本类只做 multipart 绑定与统一响应包裹，校验与落盘全在 {@link FileStorageService}；
 * 不提供任何列表/删除/下载接口（下载走静态资源映射 {@code /uploads/**}），也不写任何业务表。
 *
 * 鉴权口径（沿用既有底座模式，两验都保留）：
 * <ul>
 *   <li>{@code @PreAuthorize} 限定 STAFF：角色粗筛，非 STAFF 由 Security 层
 *       {@code RestAccessDeniedHandler} 出 HTTP 403 + 40301，未登录出 HTTP 401 + 40101；</li>
 *   <li>Service 层 {@code RoleGuard.requireRole(STAFF)}：兜底二验（rules.md §4.1 双重校验铁律，
 *       {@code @PreAuthorize} 不是唯一防线）。</li>
 * </ul>
 *
 * {@code required = false} 是有意为之：缺 {@code file} part 时交由 Service 统一给 40001 契约文案，
 * 而不是让框架的 MissingServletRequestPartException 被兜底折成 50001。
 */
@RestController
@RequestMapping("/api/v1/files")
public class FileController {

    private final FileStorageService fileStorageService;

    public FileController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    /** §1.6 POST /files/upload — multipart/form-data，字段名 file；成功 data={file_url,file_name,size} */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('STAFF')")
    public ApiResponse<FileUploadResponse> upload(
            @RequestPart(name = FileUploadConstraints.PART_NAME, required = false) MultipartFile file) throws IOException {
        return ApiResponse.success(fileStorageService.upload(file));
    }
}
