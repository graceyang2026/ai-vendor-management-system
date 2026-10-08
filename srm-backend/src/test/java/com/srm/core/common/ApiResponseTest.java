package com.srm.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @Test
    void successReturnsCodeZeroWithData() {
        ApiResponse<String> response = ApiResponse.success("hello");

        assertThat(response.getCode()).isEqualTo(0);
        assertThat(response.getMessage()).isEqualTo("success");
        assertThat(response.getData()).isEqualTo("hello");
    }

    @Test
    void successSupportsNullData() {
        ApiResponse<Void> response = ApiResponse.success(null);

        assertThat(response.getCode()).isEqualTo(0);
        assertThat(response.getData()).isNull();
    }

    @Test
    void errorReturnsGivenCodeAndMessage() {
        ApiResponse<Void> response = ApiResponse.error(ErrorCode.LOGIN_FAILED, "用户名或密码错误");

        assertThat(response.getCode()).isEqualTo(40102);
        assertThat(response.getMessage()).isEqualTo("用户名或密码错误");
        assertThat(response.getData()).isNull();
    }

    @Test
    void errorWithRawIntCodeKeepsValue() {
        ApiResponse<Void> response = ApiResponse.error(40500, "自定义错误");

        assertThat(response.getCode()).isEqualTo(40500);
        assertThat(response.getMessage()).isEqualTo("自定义错误");
        assertThat(response.getData()).isNull();
    }

    @Test
    void errorWithEveryErrorCodeKeepsValue() {
        for (ErrorCode code : ErrorCode.values()) {
            ApiResponse<Void> response = ApiResponse.error(code, code.name());
            assertThat(response.getCode()).isEqualTo(code.getValue());
        }
    }

    @Test
    void pageResultSerializesPageSizeAsSnakeCaseEvenWithoutGlobalNamingStrategy() throws Exception {
        // 契约要求 page_size 字段显式 @JsonProperty，不依赖全局 SNAKE_CASE 也能正确输出
        ObjectMapper plainMapper = new ObjectMapper();

        String json = plainMapper.writeValueAsString(new PageResult<>(List.of("a"), 100L, 2, 20));

        assertThat(json).contains("\"page_size\":20");
        assertThat(json).contains("\"list\":[\"a\"]", "\"total\":100", "\"page\":2");
    }
}
