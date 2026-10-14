package com.srm.core.config;

import com.srm.core.security.JwtAuthenticationFilter;
import com.srm.core.security.JwtTokenProvider;
import com.srm.core.security.RestAccessDeniedHandler;
import com.srm.core.security.RestAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 配置（docs/backend-interface-design.md 第 10 节）：
 * 无状态 JWT 鉴权，禁用 CSRF/Session；登录接口与联通测试接口放行，其余全部需认证。
 *
 * 本次为 §1.6 文件上传只新增一条<b>只读</b>放行：{@code GET <回显前缀>/**}（默认 {@code /uploads/**}）。
 * 理由与边界（详见会话留痕，属待 tech-lead 复核项）：
 * <ul>
 *   <li>契约要求上传返回的 {@code file_url} 直接可用于资质/建档页面回显证照，而浏览器
 *       {@code <img>}/{@code <a>} 发起的静态请求<b>无法携带 Authorization 头</b>；若这条路径仍要求认证，
 *       前端唯一的取图方式就剩下"axios 拉 blob 再拼 objectURL"，而契约里并没有这样一个下载接口。</li>
 *   <li>放行的<b>只有 GET</b>，且前缀取自 {@link FileStorageProperties}（与静态映射同源），
 *       写接口 {@code POST /api/v1/files/upload} 仍由 {@code @PreAuthorize} 限 STAFF，未被放大。</li>
 *   <li>MVP 阶段的访问凭证就是那串 32 位随机文件名（不可枚举、不在任何列表接口里返回）；
 *       这是"本地磁盘 + 静态映射"方案的已知代价，二期应改为需鉴权且做归属校验的下载接口（或签名 URL）。
 *       {@code FileUploadIntegrationTest} 里有一条用例专门锁定本决策，裁决一变只需改那一处断言。</li>
 * </ul>
 */
@Configuration
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;
    private final FileStorageProperties fileStorageProperties;

    public SecurityConfig(JwtTokenProvider jwtTokenProvider, FileStorageProperties fileStorageProperties) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.fileStorageProperties = fileStorageProperties;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           RestAuthenticationEntryPoint authenticationEntryPoint,
                                           RestAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                fileStorageProperties.normalizedPublicUrlPrefix() + "/**").permitAll()
                        .requestMatchers("/api/test/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }
}
