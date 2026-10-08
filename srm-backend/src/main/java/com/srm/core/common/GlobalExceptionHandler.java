package com.srm.core.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器：把所有异常统一转换为 ApiResponse（docs/backend-interface-design.md 第 9 节）。
 * 契约铁律（底座功能契约模块 2/3）：40101/40301 由 Security 层的
 * RestAuthenticationEntryPoint/RestAccessDeniedHandler 唯一产出，不经此处；
 * 因此 @PreAuthorize 拒绝产生的 AccessDeniedException 不在这里转换响应体，
 * 而是在 handleUnknown 中原样抛出，交由 ExceptionTranslationFilter → RestAccessDeniedHandler
 * 输出 403 + 40301，避免被兜底误报 50001。
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
        return ApiResponse.error(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, "数据已被修改，请刷新后重试");
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleUnknown(Exception ex) throws Exception {
        // 契约：40301 唯一出口是 Security 层 RestAccessDeniedHandler，这里原样重抛使其穿透
        // DispatcherServlet 冒泡到 ExceptionTranslationFilter，不被兜底吞成 50001。
        if (ex instanceof AccessDeniedException) {
            throw ex;
        }
        log.error("未处理的服务器异常", ex);
        return ApiResponse.error(ErrorCode.INTERNAL_ERROR, "服务器内部错误");
    }
}
