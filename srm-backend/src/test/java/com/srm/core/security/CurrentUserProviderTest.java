package com.srm.core.security;

import com.srm.core.common.BusinessException;
import com.srm.core.common.ErrorCode;
import com.srm.core.common.enums.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模块 2 RBAC：当前登录主体获取组件测试。
 */
class CurrentUserProviderTest {

    private final CurrentUserProvider provider = new CurrentUserProvider();

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
    void currentReturnsEmptyWhenAnonymous() {
        assertThat(provider.current()).isEmpty();
    }

    @Test
    void currentReturnsEmptyWhenPrincipalNotUserPrincipal() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("anonymousUser", null));

        assertThat(provider.current()).isEmpty();
    }

    @Test
    void currentReturnsPrincipalWhenAuthenticated() {
        loginAs(2L, "staff01", Role.STAFF);

        Optional<UserPrincipal> current = provider.current();

        assertThat(current).isPresent();
        assertThat(current.get().getUsername()).isEqualTo("staff01");
        assertThat(current.get().getRole()).isEqualTo(Role.STAFF);
    }

    @Test
    void requireReturnsPrincipalWhenAuthenticated() {
        loginAs(3L, "auditor01", Role.AUDITOR);

        UserPrincipal principal = provider.require();

        assertThat(principal.getId()).isEqualTo(3L);
        assertThat(principal.getRole()).isEqualTo(Role.AUDITOR);
    }

    @Test
    void requireThrows40101WhenAnonymous() {
        assertThatThrownBy(() -> provider.require())
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }
}
