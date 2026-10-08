package com.srm.core.common;

import lombok.Getter;

/**
 * 统一响应体（docs/api-spec.md 第 0 节）：
 * code=0 表示成功，非 0 见 ErrorCode。
 */
@Getter
public class ApiResponse<T> {

    private final int code;
    private final String message;
    private final T data;

    public ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(ErrorCode.SUCCESS.getValue(), "成功", data);
    }

    public static <T> ApiResponse<T> error(ErrorCode code, String message) {
        return new ApiResponse<>(code.getValue(), message, null);
    }
}
