package com.srm.core.controller;

import com.srm.core.common.enums.Role;
import com.srm.core.common.PageResult;
import com.srm.core.dto.user.UserCreateRequest;
import com.srm.core.dto.user.UserResponse;
import com.srm.core.dto.user.UserStatusUpdateRequest;
import com.srm.core.dto.user.UserUpdateRequest;
import com.srm.core.security.UserPrincipal;
import com.srm.core.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 模块 5 UserController 契约测试：
 * 4 个 ADMIN 专属端点（GET/POST/PUT/PATCH）参数传递与响应结构。
 * 使用自定义 HandlerMethodArgumentResolver 模拟 @AuthenticationPrincipal UserPrincipal。
 */
@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private UserService userService;

    private MockMvc mockMvc;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final UserPrincipal ADMIN =
            new UserPrincipal(1L, "admin", null, "系统管理员", Role.ADMIN, true);

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new UserController(userService))
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.hasParameterAnnotation(AuthenticationPrincipal.class)
                                && UserPrincipal.class.isAssignableFrom(parameter.getParameterType());
                    }

                    @Override
                    public Object resolveArgument(MethodParameter parameter,
                            ModelAndViewContainer mavContainer, NativeWebRequest webRequest,
                            WebDataBinderFactory binderFactory) {
                        return ADMIN;
                    }
                })
                .build();
    }

    private static UserResponse sampleUser() {
        return UserResponse.builder()
                .id(2L).username("staff01").realName("采购员张三")
                .role("STAFF").enabled(true)
                .createdAt(LocalDateTime.of(2026, 10, 1, 0, 0))
                .build();
    }

    @Test
    void getListDefaultPageAndPageSize20() throws Exception {
        when(userService.list(1, 20))
                .thenReturn(new PageResult<>(List.of(sampleUser()), 1L, 1, 20));

        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list[0].username").value("staff01"))
                .andExpect(jsonPath("$.data.list[0].real_name").value("采购员张三"))
                .andExpect(jsonPath("$.data.list[0].created_at").exists())
                .andExpect(jsonPath("$.data.page_size").value(20));

        verify(userService).list(1, 20);
    }

    @Test
    void getListPassesExplicitPagingParams() throws Exception {
        when(userService.list(2, 10))
                .thenReturn(new PageResult<>(List.of(), 0L, 2, 10));

        mockMvc.perform(get("/api/v1/users")
                        .param("page", "2")
                        .param("page_size", "10"))
                .andExpect(status().isOk());

        verify(userService).list(2, 10);
    }

    @Test
    void create200ReturnsUserResponse() throws Exception {
        when(userService.create(any(UserCreateRequest.class), eq(ADMIN)))
                .thenReturn(sampleUser());

        UserCreateRequest req = new UserCreateRequest();
        req.setUsername("staff02");
        req.setPassword("Secret@123");
        req.setRealName("李四");
        req.setRole(Role.STAFF);

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value("staff01"))
                .andExpect(jsonPath("$.data.real_name").value("采购员张三"));
    }

    @Test
    void update200ReturnsUpdatedUser() throws Exception {
        when(userService.update(eq(2L), any(UserUpdateRequest.class), eq(ADMIN)))
                .thenReturn(sampleUser());

        UserUpdateRequest req = new UserUpdateRequest();
        req.setRealName("新名字");

        mockMvc.perform(put("/api/v1/users/2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.real_name").value("采购员张三"));
    }

    @Test
    void updateStatus200ReturnsSuccessVoid() throws Exception {
        UserStatusUpdateRequest req = new UserStatusUpdateRequest();
        req.setEnabled(false);

        mockMvc.perform(patch("/api/v1/users/2/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(userService).updateStatus(eq(2L), any(UserStatusUpdateRequest.class), eq(ADMIN));
    }
}
