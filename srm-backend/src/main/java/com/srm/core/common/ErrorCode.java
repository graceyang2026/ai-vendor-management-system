package com.srm.core.common;

/**
 * 统一错误码（docs/api-spec.md 第 8 节）。
 */
public enum ErrorCode {

    SUCCESS(0),
    VALIDATION_FAILED(40001),
    UNAUTHORIZED(40101),
    INVALID_CREDENTIALS(40102),
    FORBIDDEN(40301),
    STATUS_NOT_ALLOWED(40302),
    NOT_FOUND(40401),
    OPTIMISTIC_LOCK_CONFLICT(40901),
    DUPLICATE_IN_PROGRESS(40902),
    INTERNAL_ERROR(50001);

    private final int value;

    ErrorCode(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
