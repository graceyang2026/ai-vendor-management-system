package com.srm.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.srm.core.config.FileStorageProperties;
import com.srm.core.mapper.SupplierMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 模块一 §1.6 文件上传端到端集成测试（真实 Spring 上下文 + H2 + Security 过滤链 + 静态资源映射）：
 * 与单测的分工是——单测锁"校验规则与返回形态"，本类锁"这些规则在真实链路上真的生效"：
 * 1. STAFF 上传成功 → code=0 + data{file_url,file_name,size}，file_url 经 {@code /uploads/**} 静态映射
 *    能<b>下载回原字节</b>（契约"先传文件换 URL 再填资质"的闭环前提）；
 * 2. AUDITOR / ADMIN → HTTP 403 + 40301（@PreAuthorize 经 Security 层，非 advice），匿名 → HTTP 401 + 40101；
 * 3. 类型不符 / 超限 → 40001 + 契约文案；
 * 4. <b>不写任何业务表</b>：上传前后 supplier 表行数必须一致（本接口无 Mapper 参与）；
 * 5. 落盘只在存储根的年月目录内，仓库工作目录不留任何上传文件（临时根指向 target/ 并在类结束时删除）。
 */
@SpringBootTest(properties = {
        "srm.seed-demo-users=true",
        // 测试专用存储根：绝不写默认 ./uploads（那会把上传件落进仓库工作目录）
        "srm.file.storage-root=./target/it-file-upload-storage"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FileUploadIntegrationTest {

    private static final String MSG_TYPE = "文件类型不支持，仅支持 pdf/jpg/jpeg/png 格式";
    private static final String MSG_SIZE = "文件大小不能超过10MB";
    private static final long MAX_BYTES = 10L * 1024 * 1024;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SupplierMapper supplierMapper;

    @Autowired
    private FileStorageProperties fileStorageProperties;

    /**
     * 用例之间互不干扰：本类多条断言形如"被拒文件一个都没落盘"，其前提是进入用例时存储根为空；
     * 若不逐例清理，先跑的成功用例会污染后跑的拒绝用例计数（JUnit5 方法顺序不由我们保证）。
     */
    @BeforeEach
    void emptyTemporaryStorageRoot() throws IOException {
        deleteTreeContents(storageRoot());
    }

    @AfterAll
    void removeTemporaryStorageRoot() throws IOException {
        deleteTreeContents(storageRoot());
        Files.deleteIfExists(storageRoot());
    }

    private void deleteTreeContents(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                if (!p.equals(root)) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    // ==================== 夹具 ====================

    private String loginToken(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data").get("token").asText();
    }

    private static MockMultipartFile part(String name, byte[] content) {
        return new MockMultipartFile("file", name, "application/octet-stream", content);
    }

    /** 上传并返回 data 节点（默认按 STAFF 身份） */
    private JsonNode upload(String token, MockMultipartFile file) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/v1/files/upload").file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private Path storageRoot() {
        return Path.of(fileStorageProperties.getStorageRoot()).toAbsolutePath().normalize();
    }

    private Path resolveUrl(String fileUrl) {
        String prefix = fileStorageProperties.getPublicUrlPrefix();
        assertThat(fileUrl).startsWith(prefix + "/");
        return storageRoot().resolve(fileUrl.substring(prefix.length() + 1));
    }

    // ==================== 成功：上传 → 回显下载闭环 ====================

    @Test
    void staffUploadReturnsUrlAndTheSameBytesAreDownloadableBack() throws Exception {
        String token = loginToken("staff01", "Staff@123");
        byte[] bytes = "%PDF-1.7 端到端营业执照".getBytes(StandardCharsets.UTF_8);

        JsonNode data = upload(token, part("营业执照.pdf", bytes));

        String year = String.valueOf(LocalDate.now().getYear());
        String month = String.format("%02d", LocalDate.now().getMonthValue());
        String url = data.get("file_url").asText();
        assertThat(url).matches("^/uploads/" + year + "/" + month + "/[0-9a-f]{32}\\.pdf$");
        assertThat(data.get("file_name").asText()).isEqualTo("营业执照.pdf");
        assertThat(data.get("size").asLong()).isEqualTo(bytes.length);
        assertThat(resolveUrl(url)).exists();     // 真落盘

        // 契约闭环：返回的 file_url 可直接取回原字节（静态映射真实生效，不是拼出来的假路径）
        mockMvc.perform(get(url).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes));
    }

    @Test
    void uploadedFileIsReachableWithoutTokenBecauseMvpStaticMappingIsOpen() throws Exception {
        // 本用例锁定的正是"MVP 放行 /uploads/** 静态回显"这一决策（<img>/<a> 无法携带 Authorization 头）。
        // 若人类裁决改为"下载也需鉴权"，需要动的就是这一条断言与 SecurityConfig 的那一行放行。
        String token = loginToken("staff01", "Staff@123");
        byte[] bytes = "PNG-bytes".getBytes(StandardCharsets.UTF_8);
        String url = upload(token, part("photo.png", bytes)).get("file_url").asText();

        mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes));
    }

    @Test
    void uploadDoesNotWriteAnyBusinessTableRows() throws Exception {
        String token = loginToken("staff01", "Staff@123");
        long before = supplierMapper.selectCount(null);

        upload(token, part("a.pdf", "x".getBytes(StandardCharsets.UTF_8)));
        upload(token, part("b.jpg", "y".getBytes(StandardCharsets.UTF_8)));

        assertThat(supplierMapper.selectCount(null)).isEqualTo(before);  // 契约：本接口不写业务表
    }

    @Test
    void hostileFileNameNeverEscapesStorageRoot() throws Exception {
        String token = loginToken("staff01", "Staff@123");
        byte[] bytes = "x".getBytes(StandardCharsets.UTF_8);

        JsonNode data = upload(token, part("../../../../tmp/escaped.pdf", bytes));

        assertThat(data.get("file_name").asText()).isEqualTo("escaped.pdf");   // 只取 basename 回显
        assertThat(data.get("file_url").asText()).doesNotContain("..");
        assertThat(resolveUrl(data.get("file_url").asText()).getParent())
                .isEqualTo(storageRoot().resolve(String.join("/",
                        String.valueOf(LocalDate.now().getYear()),
                        String.format("%02d", LocalDate.now().getMonthValue()))));  // 就在年月目录内，别处无写入
    }

    // ==================== 角色拦截（Security 层真实链路） ====================

    @Test
    void auditorAndAdminUploadRejectedWithHttp403And40301() throws Exception {
        String auditorToken = loginToken("auditor01", "Auditor@123");
        String adminToken = loginToken("admin", "Admin@123");

        mockMvc.perform(multipart("/api/v1/files/upload").file(part("a.pdf", new byte[]{1}))
                        .header("Authorization", "Bearer " + auditorToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301))
                .andExpect(jsonPath("$.message").value("无权限"));

        mockMvc.perform(multipart("/api/v1/files/upload").file(part("a.pdf", new byte[]{1}))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    void anonymousUploadRejectedWithHttp401And40101() throws Exception {
        mockMvc.perform(multipart("/api/v1/files/upload").file(part("a.pdf", new byte[]{1})))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    // ==================== 40001 校验分支（端到端） ====================

    @Test
    void disallowedExtensionRejectedWith40001AndNothingStored() throws Exception {
        String token = loginToken("staff01", "Staff@123");

        mockMvc.perform(multipart("/api/v1/files/upload").file(part("payload.exe", new byte[]{1, 2, 3}))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(MSG_TYPE))
                .andExpect(jsonPath("$.data").doesNotExist());

        assertThat(storedFileCount()).isZero();
    }

    @Test
    void oneByteOverTenMegabytesRejectedWith40001() throws Exception {
        String token = loginToken("staff01", "Staff@123");
        // MockMvc 的 MockMultipartFile 不经容器 multipart 解析阶段，因此这里校验的正是服务层
        // 以 getSize() 为准的业务上限（容器侧上限另由 MaxUploadSizeExceededException 处理器兜住）
        byte[] oversized = new byte[(int) MAX_BYTES + 1];

        mockMvc.perform(multipart("/api/v1/files/upload")
                        .file(new MockMultipartFile("file", "big.pdf", "application/pdf", oversized))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(MSG_SIZE));

        assertThat(storedFileCount()).isZero();
    }

    @Test
    void emptyFileRejectedWith40001() throws Exception {
        String token = loginToken("staff01", "Staff@123");

        mockMvc.perform(multipart("/api/v1/files/upload").file(part("empty.pdf", new byte[0]))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001));

        assertThat(storedFileCount()).isZero();
    }

    private long storedFileCount() throws IOException {
        Path root = storageRoot();
        if (!Files.exists(root)) {
            return 0;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile).count();
        }
    }
}
