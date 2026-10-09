package com.srm.core.dto.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.srm.core.common.enums.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 新增用户请求体（docs/api-spec.md §6 UserCreateRequest）。
 * password 明文由 Service 层 BCrypt 编码后写库，绝不出现在任何 Response DTO。
 */
@Data
public class UserCreateRequest {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;

    @NotBlank(message = "姓名不能为空")
    @JsonProperty("real_name")
    private String realName;

    @NotNull(message = "角色不能为空")
    private Role role;
}
