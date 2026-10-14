package com.srm.core.service.impl;

import com.srm.core.common.BusinessException;
import com.srm.core.common.ErrorCode;
import com.srm.core.common.enums.Role;
import com.srm.core.config.FileStorageProperties;
import com.srm.core.dto.file.FileUploadConstraints;
import com.srm.core.dto.file.FileUploadResponse;
import com.srm.core.security.RoleGuard;
import com.srm.core.service.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 模块一 §1.6 文件上传实现（契约：docs/接口契约文档-供应商与绩效-20261013.md §1.6
 * + docs/api-spec.md「文件上传」：MVP 本地磁盘存储，{@code /uploads/**} 静态映射对外回显）。
 *
 * 校验顺序（与 §1.1"先角色后格式"同口径）：角色（RoleGuard 兜底二验）→ 文件在否 → 扩展名白名单
 * → 大小上限 → 落盘。前四步任一失败即抛 40301/40101/40001，<b>一次都不会碰磁盘</b>；
 * 契约"类型不符 / 超限"两条触发场景分别对应第三、第四步。
 *
 * 防路径穿越（本类唯一的安全要点）：原始文件名<b>只取 basename、只用于 {@code file_name} 回显</b>，
 * 落盘文件名一律重新生成为「32 位十六进制随机名 + 白名单内的小写扩展名」。因此调用方构造的
 * {@code ../}、绝对路径、反斜杠串在结构上<b>不可能</b>进入磁盘路径，也不会出现在返回的 URL 里；
 * 年月目录由服务端 {@code LocalDate} 生成，同样不接受任何入参。随机名还顺带避免了同名互相覆盖。
 *
 * 本类刻意<b>不注入任何 Mapper / AuditLogService</b>：契约 §1.6 明文"本接口不写业务表"，
 * 且未给本接口定义留痕码（禁止为此新增 AuditAction）。
 */
@Service
public class FileStorageServiceImpl implements FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageServiceImpl.class);

    /** 扩展名白名单（契约 §1.6）：一律先归一小写再比较，故 {@code PDF}/{@code JpEg} 同样合法 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "jpg", "jpeg", "png");

    /** 落盘子目录按年月分段，与契约样例 {@code /uploads/2026/10/xxx.pdf} 一致（'/' 由各平台 JVM 正确解析） */
    private static final DateTimeFormatter YEAR_MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM");

    private final FileStorageProperties properties;
    private final RoleGuard roleGuard;

    public FileStorageServiceImpl(FileStorageProperties properties, RoleGuard roleGuard) {
        this.properties = properties;
        this.roleGuard = roleGuard;
    }

    @Override
    public FileUploadResponse upload(MultipartFile file) throws IOException {
        roleGuard.requireRole(Role.STAFF);

        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, FileUploadConstraints.MESSAGE_FILE_MISSING);
        }
        String basename = basenameOf(file.getOriginalFilename());
        String extension = extensionOf(basename);
        if (extension == null || !ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, FileUploadConstraints.MESSAGE_TYPE_NOT_ALLOWED);
        }
        long size = file.getSize();
        if (size > FileUploadConstraints.MAX_FILE_SIZE_BYTES) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, FileUploadConstraints.MESSAGE_SIZE_EXCEEDED);
        }

        String yearMonth = LocalDate.now().format(YEAR_MONTH_FORMAT);
        Path directory = storageRoot().resolve(yearMonth);
        Files.createDirectories(directory);
        String storedName = UUID.randomUUID().toString().replace("-", "") + "." + extension;
        file.transferTo(directory.resolve(storedName));

        String fileUrl = properties.normalizedPublicUrlPrefix() + "/" + yearMonth + "/" + storedName;
        log.info("文件上传成功：file_name={} file_url={} size={}", basename, fileUrl, size);
        return FileUploadResponse.builder()
                .fileUrl(fileUrl)
                .fileName(basename)
                .size(size)
                .build();
    }

    /** 存储根：相对路径（配置默认值 {@code ./uploads}）按进程工作目录解析，并规范化掉 {@code .}/{@code ..} */
    private Path storageRoot() {
        return Path.of(properties.getStorageRoot()).toAbsolutePath().normalize();
    }

    /**
     * 只取文件名部分：正斜杠与反斜杠都算分隔符（Windows 客户端可能上传 {@code C:\dir\a.pdf}），
     * 结果再 trim 掉首尾空白。返回值为空串时后续扩展名判定必然失败 → 40001，不会走到拼接路径。
     */
    private static String basenameOf(String originalFilename) {
        if (originalFilename == null) {
            return "";
        }
        int lastSeparator = Math.max(originalFilename.lastIndexOf('/'), originalFilename.lastIndexOf('\\'));
        return (lastSeparator < 0 ? originalFilename : originalFilename.substring(lastSeparator + 1)).trim();
    }

    /** 取最后一个 {@code '.'} 之后的扩展名并归一小写；无 {@code '.'} 或以 {@code '.'} 结尾（等价于无扩展名）返回 null */
    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
