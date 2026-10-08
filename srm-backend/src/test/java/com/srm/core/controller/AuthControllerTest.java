package com.srm.core.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.srm.core.common.BusinessException;
import com.srm.core.common.ErrorCode;
import com.srm.core.common.GlobalExceptionHandler;
import com.srm.core.common.enums.Role;
import com.srm.core.dto.auth.LoginRequest;
import com.srm.core.dto.auth.LoginResponse;
import com.srm.core.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerTest {

    private AuthService authService;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        authService = Mockito.mock(AuthService.class);
        // 与生产一致的 SNAKE_CASE 序列化策略，验证对外契约字段名
        objectMapper = Jackson2ObjectMapperBuilder.json()
                .propertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE)
                .build();
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(authService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void loginReturnsApiResponseWithSnakeCaseFields() throws Exception {
        LoginResponse loginResponse = LoginResponse.builder()
                .token("jwt-token")
                .userId(2L)
                .username("staff01")
                .realName("张三")
                .role(Role.STAFF)
                .build();
        when(authService.login(any(LoginRequest.class))).thenReturn(loginResponse);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"staff01\",\"password\":\"Staff@123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.token").value("jwt-token"))
                .andExpect(jsonPath("$.data.user_id").value(2))
                .andExpect(jsonPath("$.data.username").value("staff01"))
                .andExpect(jsonPath("$.data.real_name").value("张三"))
                .andExpect(jsonPath("$.data.role").value("STAFF"));
    }

    @Test
    void loginPassesRequestThroughAndReturnsServiceExceptionAs40102() throws Exception {
        when(authService.login(any(LoginRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.LOGIN_FAILED, "用户名或密码错误"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"staff01\",\"password\":\"wrong\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40102))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void blankUsernameIsRejectedWith40001() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"Staff@123\"}"))
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("用户名不能为空"));
    }

    @Test
    void blankPasswordIsRejectedWith40001() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"staff01\",\"password\":\"\"}"))
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("密码不能为空"));
    }

    @Test
    void loginSerializesRequestWithSnakeCaseTolerantParsing() throws Exception {
        LoginResponse loginResponse = LoginResponse.builder()
                .token("t")
                .userId(1L)
                .username("admin")
                .realName("系统管理员")
                .role(Role.ADMIN)
                .build();
        when(authService.login(any(LoginRequest.class))).thenReturn(loginResponse);

        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"Admin@123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNodeResponse json = objectMapper.readValue(body, JsonNodeResponse.class);
        assertThat(json.code).isEqualTo(0);
        verify(authService).login(any(LoginRequest.class));
    }

    /** 仅用于反序列化断言的轻量承载类 */
    public static class JsonNodeResponse {
        public int code;
        public String message;
    }
}
