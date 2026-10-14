package com.srm.core.common;

import com.srm.core.dto.file.FileUploadConstraints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

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

    /**
     * §1.6 文件上传的容器层超限兜底：请求体在 {@code MultipartResolver} 解析阶段就被 Tomcat 拦下，
     * 根本到不了 {@code FileStorageServiceImpl} 的业务大小校验。契约错误码矩阵对"超限"只给了
     * {@code 40001} 一条口径，因此这里必须折成与业务校验<b>完全相同</b>的 code + message
     * （文案取自 {@link FileUploadConstraints} 单一事实源），绝不能被下面的兜底处理器吞成 50001。
     * 必须写在 {@code handleUnknown(Exception)} 之前生效——Spring 按最具体类型匹配，二者互不影响。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ApiResponse<Void> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        log.warn("上传文件超出容器允许上限：{}", ex.getMessage());
        return ApiResponse.error(ErrorCode.VALIDATION_FAILED, FileUploadConstraints.MESSAGE_SIZE_EXCEEDED);
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
