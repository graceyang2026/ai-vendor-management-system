package com.srm.core.dto.user;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 启用/停用用户请求体（docs/api-spec.md §6 PATCH /users/{id}/status）。
 */
@Data
public class UserStatusUpdateRequest {

    @NotNull(message = "enabled 不能为空")
    private Boolean enabled;
}
