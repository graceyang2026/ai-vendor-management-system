package com.srm.core.service.impl;

import com.srm.core.common.BusinessException;
import com.srm.core.common.ErrorCode;
import com.srm.core.dto.auth.LoginRequest;
import com.srm.core.dto.auth.LoginResponse;
import com.srm.core.security.JwtTokenProvider;
import com.srm.core.security.UserPrincipal;
import com.srm.core.service.AuthService;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

/**
 * 登录实现：账号不存在、密码错误、账号停用统一返回 40102（不区分原因）。
 */
@Service
public class AuthServiceImpl implements AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthServiceImpl(AuthenticationManager authenticationManager, JwtTokenProvider jwtTokenProvider) {
        this.authenticationManager = authenticationManager;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));
            UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
            return LoginResponse.builder()
                    .token(jwtTokenProvider.generateToken(principal))
                    .userId(principal.getId())
                    .username(principal.getUsername())
                    .realName(principal.getRealName())
                    .role(principal.getRole())
                    .build();
        } catch (AuthenticationException ex) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "用户名或密码错误");
        }
    }
}
