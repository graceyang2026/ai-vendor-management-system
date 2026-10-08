package com.srm.core.security;

import com.srm.core.common.BusinessException;
import com.srm.core.common.ErrorCode;
import com.srm.core.common.enums.Role;
import org.springframework.stereotype.Component;

/**
 * 模块 2 RBAC：Service 层二次校验组件（rules.md §4.1 前后端双重校验铁律）。
 * Controller 的 @PreAuthorize 只做角色粗粒度拦截；"ADMIN 不参与业务写操作"、
 * "仅限草稿/退回状态可编辑"、"删除草稿仅限本人"这类细粒度规则必须在 Service 层
 * 通过本组件再校验一次，@PreAuthorize 不是唯一防线。
 * 错误码语义：角色不匹配 → 40301；状态/本人不满足 → 40302。
 */
@Component
public class RoleGuard {

    private final CurrentUserProvider currentUserProvider;

    public RoleGuard(CurrentUserProvider currentUserProvider) {
        this.currentUserProvider = currentUserProvider;
    }

    /** 角色兜底校验：当前角色不在允许列表内 → 40301 无权限（未登录 → 40101） */
    public void requireRole(Role... allowed) {
        Role current = currentUserProvider.require().getRole();
        for (Role role : allowed) {
            if (role == current) {
                return;
            }
        }
        throw new BusinessException(ErrorCode.FORBIDDEN, "无权限");
    }

    /** 状态二次校验：条件为 false → 40302（如【待审核】及以后状态拒绝 STAFF 编辑） */
    public void requireStatusAllowed(boolean allowed, String message) {
        if (!allowed) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, message);
        }
    }

    /** 本人校验：记录所有者必须是当前登录用户，否则 → 40302 仅限本人操作 */
    public void requireSelf(Long ownerId) {
        Long currentUserId = currentUserProvider.require().getId();
        if (ownerId == null || !ownerId.equals(currentUserId)) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "仅限本人操作");
        }
    }
}
