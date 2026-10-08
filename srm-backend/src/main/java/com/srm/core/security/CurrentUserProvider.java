package com.srm.core.security;

import com.srm.core.common.BusinessException;
import com.srm.core.common.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 模块 2 RBAC：当前登录主体获取组件。
 * Service 层二次校验（RoleGuard）与业务审计（操作者记录）从这里取 UserPrincipal，
 * 未登录场景统一抛 40101（与 RestAuthenticationEntryPoint 同语义）。
 */
@Component
public class CurrentUserProvider {

    /** 已登录且主体为 UserPrincipal 时返回，否则 empty（不抛异常，供可选场景使用） */
    public Optional<UserPrincipal> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal) {
            return Optional.of(principal);
        }
        return Optional.empty();
    }

    /** 必须登录才能继续的场景（Service 层二次校验入口），未登录 → 40101 */
    public UserPrincipal require() {
        return current().orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "未登录或登录已过期"));
    }
}
