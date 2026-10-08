package com.srm.core.service.impl;

import com.srm.core.common.BusinessException;
import com.srm.core.common.ErrorCode;
import com.srm.core.common.enums.Role;
import com.srm.core.dto.auth.LoginRequest;
import com.srm.core.dto.auth.LoginResponse;
import com.srm.core.security.UserPrincipal;
import com.srm.core.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceImplTest {

    private AuthenticationManager authenticationManager;
    private JwtTokenProvider jwtTokenProvider;
    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        authenticationManager = mock(AuthenticationManager.class);
        jwtTokenProvider = mock(JwtTokenProvider.class);
        authService = new AuthServiceImpl(authenticationManager, jwtTokenProvider);
    }

    private LoginRequest request() {
        LoginRequest request = new LoginRequest();
        request.setUsername("staff01");
        request.setPassword("Staff@123");
        return request;
    }

    @Test
    void loginSuccessReturnsTokenAndUserInfo() {
        UserPrincipal principal = new UserPrincipal(2L, "staff01", "encoded", "张三", Role.STAFF, true);
        Authentication authentication = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        when(authenticationManager.authenticate(any())).thenReturn(authentication);
        when(jwtTokenProvider.generateToken(principal)).thenReturn("jwt-token-value");

        LoginResponse response = authService.login(request());

        assertThat(response.getToken()).isEqualTo("jwt-token-value");
        assertThat(response.getUserId()).isEqualTo(2L);
        assertThat(response.getUsername()).isEqualTo("staff01");
        assertThat(response.getRealName()).isEqualTo("张三");
        assertThat(response.getRole()).isEqualTo(Role.STAFF);
        verify(jwtTokenProvider).generateToken(principal);
    }

    @Test
    void loginAuthenticatesWithUsernameAndRawPassword() {
        UserPrincipal principal = new UserPrincipal(1L, "admin", "encoded", "系统管理员", Role.ADMIN, true);
        Authentication authentication = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        when(authenticationManager.authenticate(any())).thenReturn(authentication);
        when(jwtTokenProvider.generateToken(any())).thenReturn("t");

        authService.login(request());

        verify(authenticationManager).authenticate(new UsernamePasswordAuthenticationToken("staff01", "Staff@123"));
    }

    @Test
    void wrongPasswordReturns40102() {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));

        assertThatThrownBy(() -> authService.login(request()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void disabledUserReturns40102() {
        when(authenticationManager.authenticate(any())).thenThrow(new DisabledException("disabled"));

        assertThatThrownBy(() -> authService.login(request()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void unknownUserReturns40102() {
        when(authenticationManager.authenticate(any())).thenThrow(new UsernameNotFoundException("ghost"));

        assertThatThrownBy(() -> authService.login(request()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void anyAuthenticationExceptionReturns40102() {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new AuthenticationException("locked") {
                });

        assertThatThrownBy(() -> authService.login(request()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void loginFailureNeverGeneratesToken() {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));

        assertThatThrownBy(() -> authService.login(request())).isInstanceOf(BusinessException.class);

        verify(jwtTokenProvider, org.mockito.Mockito.never()).generateToken(any());
    }
}
