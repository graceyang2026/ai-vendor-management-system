package com.srm.core.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorCodeTest {

    @Test
    void everyErrorCodeHasContractValue() {
        assertThat(ErrorCode.SUCCESS.getValue()).isEqualTo(0);
        assertThat(ErrorCode.VALIDATION_FAILED.getValue()).isEqualTo(40001);
        assertThat(ErrorCode.UNAUTHORIZED.getValue()).isEqualTo(40101);
        assertThat(ErrorCode.INVALID_CREDENTIALS.getValue()).isEqualTo(40102);
        assertThat(ErrorCode.FORBIDDEN.getValue()).isEqualTo(40301);
        assertThat(ErrorCode.STATUS_NOT_ALLOWED.getValue()).isEqualTo(40302);
        assertThat(ErrorCode.NOT_FOUND.getValue()).isEqualTo(40401);
        assertThat(ErrorCode.OPTIMISTIC_LOCK_CONFLICT.getValue()).isEqualTo(40901);
        assertThat(ErrorCode.DUPLICATE_IN_PROGRESS.getValue()).isEqualTo(40902);
        assertThat(ErrorCode.INTERNAL_ERROR.getValue()).isEqualTo(50001);
    }

    @Test
    void errorCodeCountMatchesContract() {
        assertThat(ErrorCode.values()).hasSize(10);
    }
}
