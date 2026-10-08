package com.srm.core.security;

import com.srm.core.common.enums.Role;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserPrincipalTest {

    @Test
    void exposesIdUsernameRoleAndRealName() {
        UserPrincipal principal = new UserPrincipal(7L, "auditor01", "hash", "李四", Role.AUDITOR, true);

        assertThat(principal.getId()).isEqualTo(7L);
        assertThat(principal.getUsername()).isEqualTo("auditor01");
        assertThat(principal.getPassword()).isEqualTo("hash");
        assertThat(principal.getRealName()).isEqualTo("李四");
        assertThat(principal.getRole()).isEqualTo(Role.AUDITOR);
    }

    @Test
    void authoritiesContainPrefixedRoleOnly() {
        for (Role role : Role.values()) {
            UserPrincipal principal = new UserPrincipal(1L, "u", "pw", "名", role, true);

            assertThat(principal.getAuthorities())
                    .extracting(Object::toString)
                    .containsExactly("ROLE_" + role.name());
        }
    }

    @Test
    void accountFlagsAreAlwaysTrue() {
        UserPrincipal principal = new UserPrincipal(1L, "u", "pw", "名", Role.STAFF, true);

        assertThat(principal.isAccountNonExpired()).isTrue();
        assertThat(principal.isAccountNonLocked()).isTrue();
        assertThat(principal.isCredentialsNonExpired()).isTrue();
    }

    @Test
    void enabledFlagReflectsConstructorArgument() {
        UserPrincipal enabled = new UserPrincipal(1L, "u", "pw", "名", Role.STAFF, true);
        UserPrincipal disabled = new UserPrincipal(2L, "u2", "pw", "名", Role.STAFF, false);

        assertThat(enabled.isEnabled()).isTrue();
        assertThat(disabled.isEnabled()).isFalse();
    }
}
