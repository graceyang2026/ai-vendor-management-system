package com.srm.core.security;

import com.srm.core.common.BusinessException;
import com.srm.core.common.ErrorCode;
import com.srm.core.common.enums.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模块 2 RBAC：Service 层二次校验组件测试（rules.md §4.1 前后端双重校验铁律）。
 * - 角色兜底：不在允许角色 → 40301
 * - 状态校验：非法状态迁移/写操作 → 40302
 * - 本人校验：非记录所有者 → 40302
 */
class RoleGuardTest {

    private RoleGuard guard;

    @BeforeEach
    void setUp() {
        guard = new RoleGuard(new CurrentUserProvider());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(Long id, String username, Role role) {
        UserPrincipal principal = new UserPrincipal(id, username, null, null, role, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @Test
    void requireRolePassesWhenRoleInAllowedList() {
        loginAs(2L, "staff01", Role.STAFF);

        assertThatCode(() -> guard.requireRole(Role.STAFF, Role.AUDITOR)).doesNotThrowAnyException();
    }

    @Test
    void requireRoleThrows40301WhenRoleNotInList() {
        loginAs(1L, "admin", Role.ADMIN);

        // ADMIN 不参与供应商业务写操作（业务铁律：管理权限与业务权限严格分离）
        assertThatThrownBy(() -> guard.requireRole(Role.STAFF, Role.AUDITOR))
                .isInstanceOf(BusinessException.class)
                .hasMessage("无权限")
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void requireRoleThrows40101WhenAnonymous() {
        assertThatThrownBy(() -> guard.requireRole(Role.STAFF))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    void requireStatusAllowedPassesWhenConditionTrue() {
        assertThatCode(() -> guard.requireStatusAllowed(true, "当前状态不允许该操作"))
                .doesNotThrowAnyException();
    }

    @Test
    void requireStatusAllowedThrows40302WhenConditionFalse() {
        // 示例：供应商处于【待审核】状态时强制拒绝 STAFF 编辑
        assertThatThrownBy(() -> guard.requireStatusAllowed(false, "【待审核】状态不允许编辑"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("【待审核】状态不允许编辑")
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.STATUS_NOT_ALLOWED);
    }

    @Test
    void requireSelfPassesWhenOwnerIsCurrentUser() {
        loginAs(2L, "staff01", Role.STAFF);

        assertThatCode(() -> guard.requireSelf(2L)).doesNotThrowAnyException();
    }

    @Test
    void requireSelfThrows40302WhenOwnerIsAnotherUser() {
        loginAs(2L, "staff01", Role.STAFF);

        // 示例：删除供应商草稿仅限本人
        assertThatThrownBy(() -> guard.requireSelf(999L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("仅限本人操作")
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.STATUS_NOT_ALLOWED);
    }

    @Test
    void requireSelfThrows40302WhenOwnerIdIsNull() {
        loginAs(2L, "staff01", Role.STAFF);

        assertThatThrownBy(() -> guard.requireSelf(null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.STATUS_NOT_ALLOWED);
    }

    @Test
    void requireSelfThrows40101WhenAnonymous() {
        assertThatThrownBy(() -> guard.requireSelf(2L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }
}
