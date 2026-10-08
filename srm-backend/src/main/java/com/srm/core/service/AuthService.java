package com.srm.core.service;

import com.srm.core.dto.auth.LoginRequest;
import com.srm.core.dto.auth.LoginResponse;

/**
 * 认证服务（docs/backend-interface-design.md 第 4.1 节）。
 */
public interface AuthService {

    LoginResponse login(LoginRequest request);
}
