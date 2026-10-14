package com.srm.core.service;

import com.srm.core.dto.file.FileUploadResponse;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 模块一 §1.6 文件上传服务（契约：docs/接口契约文档-供应商与绩效-20261013.md §1.6）。
 *
 * 职责边界（契约明文）：只做"校验 + 落本地磁盘 + 换回 file_url"，
 * <b>不写任何业务表、不落审计</b>（§1.6 未给本接口定义留痕码，禁止新增 AuditAction），
 * 也不做资质归属校验——归属校验属于 §1.7 上传资质接口。
 */
public interface FileStorageService {

    /**
     * §1.6 上传：STAFF 专属（Service 层 RoleGuard 兜底二验，非 STAFF → 40301，未登录 → 40101）；
     * 扩展名白名单 pdf/jpg/jpeg/png（大小写不敏感）与 ≤10MB 任一不符 → 40001；
     * 成功返回 {@code file_url / file_name / size} 三枚字段。
     *
     * @param file multipart 字段名为 {@code file} 的上传件；允许为 null（缺 part 时按"文件为空"给 40001，
     *             而不是让框架异常兜底成 50001）
     * @throws IOException 磁盘写入失败（目录创建/落盘）——原样冒泡，由全局 advice 记 error 日志并回 50001，
     *                     绝不伪装成上传成功
     */
    FileUploadResponse upload(MultipartFile file) throws IOException;
}
