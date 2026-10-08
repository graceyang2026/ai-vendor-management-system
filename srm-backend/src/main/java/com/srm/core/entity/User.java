package com.srm.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体，映射 sys_user 表（docs/backend-interface-design.md 第 2.1 节）。
 * password_hash 为 BCrypt 编码，绝不出现在任何 Response DTO。
 */
@Data
@TableName("sys_user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String passwordHash;

    private String realName;

    /** 角色名（Role 枚举的 name()），如 ADMIN/STAFF/AUDITOR */
    private String role;

    /** 停用账号不允许登录 */
    private Boolean enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
