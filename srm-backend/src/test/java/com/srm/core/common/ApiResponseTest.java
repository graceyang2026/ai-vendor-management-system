package com.srm.core.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @Test
    void successReturnsCodeZeroWithData() {
        ApiResponse<String> response = ApiResponse.success("hello");

        assertThat(response.getCode()).isEqualTo(0);
        assertThat(response.getMessage()).isEqualTo("成功");
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
        ApiResponse<Void> response = ApiResponse.error(ErrorCode.INVALID_CREDENTIALS, "用户名或密码错误");

        assertThat(response.getCode()).isEqualTo(40102);
        assertThat(response.getMessage()).isEqualTo("用户名或密码错误");
        assertThat(response.getData()).isNull();
    }

    @Test
    void errorWithEveryErrorCodeKeepsValue() {
        for (ErrorCode code : ErrorCode.values()) {
            ApiResponse<Void> response = ApiResponse.error(code, code.name());
            assertThat(response.getCode()).isEqualTo(code.getValue());
        }
    }
}
