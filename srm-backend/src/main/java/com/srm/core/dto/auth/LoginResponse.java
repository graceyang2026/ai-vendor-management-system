package com.srm.core.dto.auth;

import com.srm.core.common.enums.Role;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录响应（docs/api-spec.md 第 1 节），
 * 经全局 SNAKE_CASE 序列化后对外字段为 token/user_id/username/real_name/role。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    private String token;

    private Long userId;

    private String username;

    private String realName;

    private Role role;
}
