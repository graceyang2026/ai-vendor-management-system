package com.srm.core.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    void handleBusinessReturnsExceptionCodeAndMessage() {
        BusinessException ex = new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "当前状态不允许该操作");

        ApiResponse<Void> response = handler.handleBusiness(ex);

        assertThat(response.getCode()).isEqualTo(40302);
        assertThat(response.getMessage()).isEqualTo("当前状态不允许该操作");
        assertThat(response.getData()).isNull();
    }

    @Test
    void handleValidationReturns40001WithFirstFieldMessage() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fieldError = new FieldError("loginRequest", "username", "用户名不能为空");
        when(ex.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));

        ApiResponse<Void> response = handler.handleValidation(ex);

        assertThat(response.getCode()).isEqualTo(40001);
        assertThat(response.getMessage()).isEqualTo("用户名不能为空");
    }

    @Test
    void handleValidationWithoutFieldErrorsFallsBackToDefaultMessage() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult bindingResult = mock(BindingResult.class);
        when(ex.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getFieldErrors()).thenReturn(List.of());

        ApiResponse<Void> response = handler.handleValidation(ex);

        assertThat(response.getCode()).isEqualTo(40001);
        assertThat(response.getMessage()).isEqualTo("参数校验失败");
    }

    @Test
    void handleOptimisticLockReturns40901() {
        OptimisticLockingFailureException ex = new OptimisticLockingFailureException("version conflict");

        ApiResponse<Void> response = handler.handleOptimisticLock(ex);

        assertThat(response.getCode()).isEqualTo(40901);
        assertThat(response.getMessage()).isEqualTo("数据已被其他操作修改，请刷新后重试");
    }

    @Test
    void handleAccessDeniedReturns40301() {
        AccessDeniedException ex = new AccessDeniedException("denied");

        ApiResponse<Void> response = handler.handleAccessDenied(ex);

        assertThat(response.getCode()).isEqualTo(40301);
        assertThat(response.getMessage()).isEqualTo("无权限");
    }

    @Test
    void handleUnknownReturns50001() {
        RuntimeException ex = new RuntimeException("boom");

        ApiResponse<Void> response = handler.handleUnknown(ex);

        assertThat(response.getCode()).isEqualTo(50001);
        assertThat(response.getMessage()).isEqualTo("服务器内部错误");
    }
}
