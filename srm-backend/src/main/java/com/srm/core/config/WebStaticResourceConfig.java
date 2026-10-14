package com.srm.core.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

/**
 * 静态资源映射：{@code /uploads/**} → 磁盘存储根（契约 §1.6「MVP 阶段存本地磁盘即可，
 * 后端通过静态资源映射对外提供返回的 file_url」）。
 *
 * 映射前缀与存储根都取自 {@link FileStorageProperties}，与 {@code FileStorageServiceImpl}
 * 生成 {@code file_url} 用的是<b>同一份配置</b>：改了根目录或前缀，三处（URL 生成、静态映射、
 * Security 放行）同步生效，不会出现"返回的 URL 打不开"的错配。
 *
 * 位置串用 {@code file:<encodedPath>/} 形式：{@code Path.toUri()} 只有在目录已存在时才自动补尾斜杠，
 * 而首次上传前目录往往还不存在，缺尾斜杠会让 Spring 把整条位置当文件处理导致全部 404，
 * 故显式拼上；同时保留 URI 编码，路径含空格也能正常解析。
 *
 * 目录不存在时本映射只是 404，不会在应用启动时创建任何目录（也不该创建：上传件目录归运维，
 * 仓库工作目录里不留垃圾文件）。Spring 的 {@code ResourceHttpRequestHandler} 自带路径穿越防护。
 */
@Configuration
public class WebStaticResourceConfig implements WebMvcConfigurer {

    private final FileStorageProperties properties;

    public WebStaticResourceConfig(FileStorageProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path root = Path.of(properties.getStorageRoot()).toAbsolutePath().normalize();
        String location = "file:" + root.toUri().getRawPath() + "/";
        registry.addResourceHandler(properties.normalizedPublicUrlPrefix() + "/**")
                .addResourceLocations(location);
    }
}
