package com.srm.core.common;

import lombok.Getter;

/**
 * 业务异常，携带统一错误码，由 GlobalExceptionHandler 转换为 ApiResponse。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode code;

    public BusinessException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }
}
