package com.srm.core.dto.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.srm.core.common.enums.Role;
import lombok.Data;

/**
 * 修改用户请求体（docs/api-spec.md §6 UserUpdateRequest）。
 * 仅允许修改 real_name/role；username/password 字段保留用于检测非法请求（非 null → 40001 拒绝）。
 */
@Data
public class UserUpdateRequest {

    @JsonProperty("real_name")
    private String realName;

    private Role role;

    /**
     * 检测字段：请求体出现非 null 值时 Service 抛出 40001。
     * 不允许通过 PUT 修改用户名。
     */
    private String username;

    /**
     * 检测字段：请求体出现非 null 值时 Service 抛出 40001。
     * 不允许通过 PUT 修改密码（MVP 不支持重置密码）。
     */
    private String password;
}
