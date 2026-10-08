package com.srm.core.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器：把所有异常统一转换为 ApiResponse（docs/backend-interface-design.md 第 9 节）。
 * 40101/40301（filter 层拒绝）由 Security 层的 RestAuthenticationEntryPoint/RestAccessDeniedHandler
 * 处理，不经此处；@PreAuthorize 方法级校验抛出的 AccessDeniedException 会进入 DispatcherServlet，
 * 此处兜底为 40301 保证语义一致。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handleBusiness(BusinessException ex) {
        return ApiResponse.error(ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("参数校验失败");
        return ApiResponse.error(ErrorCode.VALIDATION_FAILED, message);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ApiResponse<Void> handleOptimisticLock(OptimisticLockingFailureException ex) {
        return ApiResponse.error(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, "数据已被其他操作修改，请刷新后重试");
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleAccessDenied(AccessDeniedException ex) {
        return ApiResponse.error(ErrorCode.FORBIDDEN, "无权限");
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleUnknown(Exception ex) {
        log.error("未处理的服务器异常", ex);
        return ApiResponse.error(ErrorCode.INTERNAL_ERROR, "服务器内部错误");
    }
}
