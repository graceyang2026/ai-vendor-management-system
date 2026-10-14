package com.srm.core.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文件上传存储配置（契约 §1.6「MVP 本地磁盘存储，{@code /uploads/**} 可访问」的配置化落点）。
 *
 * 前缀刻意挂在既有 {@code srm.*} 命名空间下（与 {@code srm.jwt}、{@code srm.seed-demo-users} 同族），
 * 不新造顶层 {@code file.*} 方言；派单给的示意名是 {@code file.storage.root}，此处按项目既有命名族
 * 落为 {@code srm.file.storage-root}，语义与默认值不变（差异已在会话留痕说明）。
 *
 * <ul>
 *   <li>{@code srm.file.storage-root}：磁盘存储根，默认 {@code ./uploads}，生产以环境变量
 *       {@code FILE_STORAGE_ROOT} 覆盖；相对路径按进程工作目录解析。</li>
 *   <li>{@code srm.file.public-url-prefix}：对外回显前缀，默认 {@code /uploads}。
 *       {@code file_url} 生成、静态资源映射（{@link WebStaticResourceConfig}）、Security 放行口径
 *       （{@link SecurityConfig}）三处<b>共用这一个值</b>，杜绝"改了映射忘了改 URL"的错配。</li>
 * </ul>
 *
 * 本接口不写任何业务表，故这里也不出现任何数据源/表相关配置项。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "srm.file")
public class FileStorageProperties {

    /** 存储根目录（本地磁盘路径，MVP 不接云存储/OSS） */
    private String storageRoot = "./uploads";

    /** 对外回显 URL 前缀，同时是静态资源映射与放行的路径前缀 */
    private String publicUrlPrefix = "/uploads";

    /**
     * 归一化回显前缀：去掉可能存在的一个或多个尾部 {@code '/'}，
     * 避免拼出 {@code /uploads//2026/10/x.pdf} 这类与契约样例形态不符的 URL 或 {@code /**} 映射模式。
     * 用正则一次处理完，不留 {@code while}/条件分支（否则这条"配置手滑"的分支永远测不到、拉低分支覆盖率）。
     */
    public String normalizedPublicUrlPrefix() {
        return publicUrlPrefix.replaceAll("/+$", "");
    }
}
