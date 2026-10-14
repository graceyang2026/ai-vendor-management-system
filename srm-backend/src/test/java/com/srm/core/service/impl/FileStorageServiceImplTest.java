package com.srm.core.service.impl;

import com.srm.core.common.BusinessException;
import com.srm.core.common.ErrorCode;
import com.srm.core.common.enums.Role;
import com.srm.core.config.FileStorageProperties;
import com.srm.core.dto.file.FileUploadResponse;
import com.srm.core.security.CurrentUserProvider;
import com.srm.core.security.RoleGuard;
import com.srm.core.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模块一 §1.6 文件上传 FileStorageService 单元测试。
 * 契约：docs/接口契约文档-供应商与绩效-20261013.md §1.6 + docs/api-spec.md「文件上传」小节。
 * 锁定行为：
 * 1. 白名单 pdf/jpg/jpeg/png，扩展名<b>大小写不敏感</b>；落盘用生成的随机存储名，<b>原始名不进路径</b>；
 * 2. 大小以 {@code MultipartFile.getSize()} 为唯一判据，边界为 10*1024*1024：<b>恰好 10MB 合法</b>，
 *    超出 1 字节即 40001；
 * 3. 落盘目录含年月（{@code /uploads/yyyy/MM/}），file_url 形态与契约样例逐字一致；
 * 4. 路径穿越（{@code ../}、反斜杠、绝对路径）只取 basename，穿越串既不进 URL 也不进落盘路径；
 * 5. 角色：Service 层 RoleGuard 兜底 requireRole(STAFF)——非 STAFF → 40301，未登录 → 40101；
 * 6. 本接口不写任何业务表、不落审计（契约未给本接口定义留痕，实现里不得出现 Mapper/AuditLogService）。
 *
 * 磁盘读写全部落在 JUnit {@code @TempDir}（外加一条显式清理的相对路径用例）内，仓库不留垃圾文件。
 */
class FileStorageServiceImplTest {

    /** 契约 §1.6 错误码矩阵只有 HTTP/Code/触发场景三列、<b>未给 message 原文</b>；本批自拟中文文案，待 tech-lead 确认 */
    private static final String MSG_EMPTY = "请选择要上传的文件";
    private static final String MSG_TYPE = "文件类型不支持，仅支持 pdf/jpg/jpeg/png 格式";
    private static final String MSG_SIZE = "文件大小不能超过10MB";
    private static final long MAX_BYTES = 10L * 1024 * 1024;

    @TempDir
    Path root;

    private CurrentUserProvider currentUserProvider;
    private FileStorageProperties properties;
    private FileStorageServiceImpl service;

    @BeforeEach
    void setUp() {
        currentUserProvider = mock(CurrentUserProvider.class);
        properties = new FileStorageProperties();
        properties.setStorageRoot(root.toString());
        properties.setPublicUrlPrefix("/uploads");
        // RoleGuard 用真实实例包裹 mock provider：40301/40101 语义走生产同一实现
        service = new FileStorageServiceImpl(properties, new RoleGuard(currentUserProvider));
    }

    @AfterEach
    void cleanUpRelativeRoot() throws IOException {
        // 相对路径用例的工作目录产物（srm-backend/target/…，已被 .gitignore 覆盖）显式清掉
        deleteRecursively(Path.of("target", "file-storage-relative-root-test"));
    }

    // ==================== 夹具 ====================

    private void loginAs(Role role) {
        when(currentUserProvider.require())
                .thenReturn(new UserPrincipal(2L, "staff01", null, "张三", role, true));
    }

    private static MockMultipartFile file(String originalName, byte[] content) {
        return new MockMultipartFile("file", originalName, "application/octet-stream", content);
    }

    /** 只控制 getSize()/getOriginalFilename() 的轻量替身，避免为边界用例真分配 10MB+ 字节 */
    private static MultipartFile sized(String originalName, long size) {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn(originalName);
        when(file.getSize()).thenReturn(size);
        return file;
    }

    private long countFilesUnderRoot() throws IOException {
        if (!Files.exists(root)) {
            return 0;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile).count();
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static String year() {
        return String.valueOf(LocalDate.now().getYear());
    }

    private static String month() {
        return String.format("%02d", LocalDate.now().getMonthValue());
    }

    /** 契约样例形态：/uploads/yyyy/MM/ + 32 位小写十六进制随机名 + 白名单扩展名 */
    private static String contractUrlRegex() {
        return "^/uploads/" + year() + "/" + month() + "/[0-9a-f]{32}\\.(pdf|jpg|jpeg|png)$";
    }

    private static void assertBiz(Throwable ex, ErrorCode code, String message) {
        assertThat(ex).isInstanceOf(BusinessException.class);
        BusinessException biz = (BusinessException) ex;
        assertThat(biz.getCode()).isEqualTo(code);
        assertThat(biz.getMessage()).isEqualTo(message);
    }

    private Path storedPathOf(FileUploadResponse resp) {
        return root.resolve(resp.getFileUrl().substring("/uploads/".length()));
    }

    // ==================== 成功路径 ====================

    @Test
    void uploadWritesUnderYearMonthDirAndReturnsContractTriple() throws Exception {
        loginAs(Role.STAFF);
        byte[] content = "%PDF-1.4 营业执照扫描件".getBytes(StandardCharsets.UTF_8);

        FileUploadResponse resp = service.upload(file("营业执照.pdf", content));

        assertThat(resp.getFileUrl()).matches(contractUrlRegex());
        assertThat(resp.getFileUrl()).doesNotContain("营业执照");   // 原始名不进路径
        assertThat(resp.getFileName()).isEqualTo("营业执照.pdf");    // 原始名只回显在 file_name
        assertThat(resp.getSize()).isEqualTo(content.length);

        Path stored = storedPathOf(resp);
        assertThat(stored).exists();
        assertThat(Files.readAllBytes(stored)).isEqualTo(content);   // 原字节落盘，可经 /uploads/** 回显
        assertThat(countFilesUnderRoot()).isEqualTo(1);              // 除年月目录文件外无多余产物
    }

    @Test
    void allFourWhitelistedExtensionsAcceptedCaseInsensitively() throws Exception {
        loginAs(Role.STAFF);

        for (String name : new String[]{"a.pdf", "b.JPG", "c.jpeg", "d.PNG", "e.pDf", "f.JpEg"}) {
            FileUploadResponse resp = service.upload(file(name, "x".getBytes(StandardCharsets.UTF_8)));
            String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
            assertThat(resp.getFileUrl()).endsWith("." + ext);       // 存储名扩展名归一为小写
            assertThat(resp.getFileName()).isEqualTo(name);           // 回显保留原始大小写
        }
    }

    @Test
    void exactlyTenMegabytesIsAcceptedBoundary() throws Exception {
        loginAs(Role.STAFF);
        MultipartFile file = sized("scan.pdf", MAX_BYTES);

        FileUploadResponse resp = service.upload(file);

        assertThat(resp.getSize()).isEqualTo(MAX_BYTES);
        assertThat(resp.getFileUrl()).matches(contractUrlRegex());
    }

    @Test
    void sameOriginalNameUploadedTwiceGetsDifferentStoredName() throws Exception {
        loginAs(Role.STAFF);

        FileUploadResponse first = service.upload(file("license.png", new byte[]{1}));
        FileUploadResponse second = service.upload(file("license.png", new byte[]{2}));

        assertThat(first.getFileUrl()).isNotEqualTo(second.getFileUrl());  // 随机名，不覆盖旧件
        assertThat(storedPathOf(first)).exists();
        assertThat(storedPathOf(second)).exists();
        assertThat(countFilesUnderRoot()).isEqualTo(2);
    }

    // ==================== 40001 校验分支 ====================

    @Test
    void nonWhitelistedExtensionRejectedWith40001AndNothingWritten() throws Exception {
        loginAs(Role.STAFF);

        for (String name : new String[]{"payload.exe", "script.bat", "data.xlsx", "page.html", "archive.zip"}) {
            assertThatThrownBy(() -> service.upload(file(name, "junk".getBytes(StandardCharsets.UTF_8))))
                    .satisfies(ex -> assertBiz(ex, ErrorCode.VALIDATION_FAILED, MSG_TYPE));
        }
        assertThat(countFilesUnderRoot()).isZero();   // 被拒文件绝不落盘
    }

    @Test
    void missingOrBlankOrNullFileNameCannotPassExtensionWhitelist() throws Exception {
        loginAs(Role.STAFF);

        assertThatThrownBy(() -> service.upload(file("license", new byte[]{1})))
                .satisfies(ex -> assertBiz(ex, ErrorCode.VALIDATION_FAILED, MSG_TYPE));
        assertThatThrownBy(() -> service.upload(file("", new byte[]{1})))
                .satisfies(ex -> assertBiz(ex, ErrorCode.VALIDATION_FAILED, MSG_TYPE));
        assertThatThrownBy(() -> service.upload(file("trailing-dot.", new byte[]{1})))
                .satisfies(ex -> assertBiz(ex, ErrorCode.VALIDATION_FAILED, MSG_TYPE));   // "name." 等价于无扩展名
        // 容器侧 submittedFileName 可能真的是 null（非 file part），实现必须归一成"无扩展名"而不是 NPE 冒 50001
        MultipartFile nullNamed = mock(MultipartFile.class);
        when(nullNamed.getOriginalFilename()).thenReturn(null);
        assertThatThrownBy(() -> service.upload(nullNamed))
                .satisfies(ex -> assertBiz(ex, ErrorCode.VALIDATION_FAILED, MSG_TYPE));

        // 注：MockMultipartFile 会把 null 原始名归一成空串，因此"真 null"只能用替身造
        assertThatThrownBy(() -> service.upload(file(null, new byte[]{1})))
                .satisfies(ex -> assertBiz(ex, ErrorCode.VALIDATION_FAILED, MSG_TYPE));
        assertThat(countFilesUnderRoot()).isZero();
    }

    @Test
    void doubleExtensionIsJudgedByLastSegment() throws Exception {
        loginAs(Role.STAFF);

        // 末段决定真实类型：不得被伪装成 .pdf 的前段绕过
        assertThatThrownBy(() -> service.upload(file("invoice.pdf.exe", new byte[]{1})))
                .satisfies(ex -> assertBiz(ex, ErrorCode.VALIDATION_FAILED, MSG_TYPE));

        FileUploadResponse ok = service.upload(file("report.final.pdf", new byte[]{1}));
        assertThat(ok.getFileUrl()).endsWith(".pdf");
        assertThat(ok.getFileName()).isEqualTo("report.final.pdf");
    }

    @Test
    void oneByteOverTenMegabytesRejectedWith40001() throws Exception {
        loginAs(Role.STAFF);
        MultipartFile file = sized("big.pdf", MAX_BYTES + 1);

        assertThatThrownBy(() -> service.upload(file))
                .satisfies(ex -> assertBiz(ex, ErrorCode.VALIDATION_FAILED, MSG_SIZE));

        assertThat(countFilesUnderRoot()).isZero();
    }

    @Test
    void wildlyOversizedFileRejectedBeforeAnyDiskWrite() throws Exception {
        loginAs(Role.STAFF);
        MultipartFile file = sized("huge.pdf", 2L * 1024 * 1024 * 1024); // 2GB

        assertThatThrownBy(() -> service.upload(file))
                .satisfies(ex -> assertBiz(ex, ErrorCode.VALIDATION_FAILED, MSG_SIZE));

        verify(file, never()).transferTo(any(Path.class));
        assertThat(countFilesUnderRoot()).isZero();
    }

    @Test
    void nullFileRejectedWith40001() throws Exception {
        loginAs(Role.STAFF);

        assertThatThrownBy(() -> service.upload(null))
                .satisfies(ex -> assertBiz(ex, ErrorCode.VALIDATION_FAILED, MSG_EMPTY));
        assertThat(countFilesUnderRoot()).isZero();
    }

    @Test
    void emptyContentFileRejectedWith40001() throws Exception {
        loginAs(Role.STAFF);

        assertThatThrownBy(() -> service.upload(file("blank.pdf", new byte[0])))
                .satisfies(ex -> assertBiz(ex, ErrorCode.VALIDATION_FAILED, MSG_EMPTY));
        assertThat(countFilesUnderRoot()).isZero();
    }

    // ==================== 路径穿越防护 ====================

    @Test
    void traversalSequencesAreStrippedToBasenameAndNeverReachPath() throws Exception {
        loginAs(Role.STAFF);

        String[] hostile = {"../../../../etc/passwd.pdf", "..\\..\\windows\\system.ini.pdf",
                "/var/tmp/absolute.pdf", "C:\\Users\\someone\\report.png"};
        for (String name : hostile) {
            FileUploadResponse resp = service.upload(file(name, "x".getBytes(StandardCharsets.UTF_8)));
            assertThat(resp.getFileUrl()).matches(contractUrlRegex());
            assertThat(resp.getFileUrl()).doesNotContain("..").doesNotContain("\\");
            assertThat(resp.getFileName()).doesNotContain("/").doesNotContain("\\").doesNotContain("..");
            assertThat(storedPathOf(resp)).exists();
        }
        assertThat(countFilesUnderRoot()).isEqualTo(hostile.length);  // 只在年月目录内新增，别处零写入
    }

    // ==================== 角色兜底（Service 层二验） ====================

    @Test
    void auditorAndAdminRejectedWith40301BeforeAnyFileValidation() {
        loginAs(Role.AUDITOR);
        assertThatThrownBy(() -> service.upload(file("a.pdf", new byte[]{1})))
                .satisfies(ex -> assertBiz(ex, ErrorCode.FORBIDDEN, "无权限"));

        loginAs(Role.ADMIN);
        assertThatThrownBy(() -> service.upload(file("a.pdf", new byte[]{1})))
                .satisfies(ex -> assertBiz(ex, ErrorCode.FORBIDDEN, "无权限"));
    }

    @Test
    void anonymousCallerRejectedWith40101() {
        when(currentUserProvider.require())
                .thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED, "未登录或登录已过期"));

        assertThatThrownBy(() -> service.upload(file("a.pdf", new byte[]{1})))
                .satisfies(ex -> assertBiz(ex, ErrorCode.UNAUTHORIZED, "未登录或登录已过期"));
    }

    // ==================== 配置项与存储失败 ====================

    @Test
    void trailingSlashInPublicUrlPrefixDoesNotProduceDoubleSlash() throws Exception {
        loginAs(Role.STAFF);
        properties.setPublicUrlPrefix("/uploads/");

        FileUploadResponse resp = service.upload(file("a.pdf", new byte[]{1}));

        assertThat(resp.getFileUrl()).startsWith("/uploads/" + year() + "/");
        assertThat(resp.getFileUrl()).doesNotContain("//");
    }

    @Test
    void relativeStorageRootIsResolvedAgainstWorkingDirectory() throws Exception {
        loginAs(Role.STAFF);
        properties.setStorageRoot(Path.of("target", "file-storage-relative-root-test").toString());

        FileUploadResponse resp = service.upload(file("a.jpg", new byte[]{9}));

        Path stored = Path.of("target", "file-storage-relative-root-test")
                .resolve(resp.getFileUrl().substring("/uploads/".length()))
                .toAbsolutePath().normalize();
        assertThat(Files.readAllBytes(stored)).isEqualTo(new byte[]{9});
    }

    @Test
    void storageRootOccupiedByRegularFilePropagatesIoFailureInsteadOfFakeSuccess() {
        // 存储根被同名普通文件占用 → createDirectories 必然失败：不得被服务层悄悄吞掉伪装成成功
        Path blocker = root.resolve("not-a-dir");
        try {
            Files.writeString(blocker, "x");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        loginAs(Role.STAFF);
        properties.setStorageRoot(blocker.toString());

        assertThatThrownBy(() -> service.upload(file("a.pdf", new byte[]{1})))
                .isInstanceOf(IOException.class);
    }
}
