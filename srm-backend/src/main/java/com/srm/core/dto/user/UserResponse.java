package com.srm.core.dto.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 用户响应体（docs/api-spec.md §6 UserResponse）。
 * 注意：password_hash 绝不出现于本 DTO，保证密码永不明文外泄。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class UserResponse {

    private Long id;

    private String username;

    @JsonProperty("real_name")
    private String realName;

    /** 角色名：ADMIN / STAFF / AUDITOR */
    private String role;

    private boolean enabled;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;
}
