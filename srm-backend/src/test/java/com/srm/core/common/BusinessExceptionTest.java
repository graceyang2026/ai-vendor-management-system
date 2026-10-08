package com.srm.core.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessExceptionTest {

    @Test
    void carriesErrorCodeAndMessage() {
        BusinessException ex = new BusinessException(ErrorCode.NOT_FOUND, "供应商不存在");

        assertThat(ex.getCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(ex.getMessage()).isEqualTo("供应商不存在");
    }

    @Test
    void isRuntimeException() {
        assertThatThrownBy(() -> {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权限");
        }).isInstanceOf(RuntimeException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void codeValueMatchesExceptionCode() {
        BusinessException ex = new BusinessException(ErrorCode.INTERNAL_ERROR, "服务器内部错误");

        assertThat(ex.getCode().getValue()).isEqualTo(50001);
    }
}
