package com.srm.core.security;

import com.srm.core.common.enums.Role;
import com.srm.core.entity.User;
import com.srm.core.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CustomUserDetailsServiceTest {

    private UserMapper userMapper;
    private CustomUserDetailsService service;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        service = new CustomUserDetailsService(userMapper);
    }

    private User enabledUser(String username, String role) {
        User user = new User();
        user.setId(3L);
        user.setUsername(username);
        user.setPasswordHash("encoded-password");
        user.setRealName("张三");
        user.setRole(role);
        user.setEnabled(true);
        return user;
    }

    @Test
    void loadUserByUsernameReturnsUserPrincipal() {
        when(userMapper.selectOne(any())).thenReturn(enabledUser("staff01", "STAFF"));

        UserDetails details = service.loadUserByUsername("staff01");

        assertThat(details).isInstanceOf(UserPrincipal.class);
        UserPrincipal principal = (UserPrincipal) details;
        assertThat(principal.getUsername()).isEqualTo("staff01");
        assertThat(principal.getId()).isEqualTo(3L);
        assertThat(principal.getRole()).isEqualTo(Role.STAFF);
        assertThat(principal.getRealName()).isEqualTo("张三");
        assertThat(principal.getPassword()).isEqualTo("encoded-password");
        assertThat(principal.isEnabled()).isTrue();
    }

    @Test
    void loadUserByUsernameThrowsWhenUserNotFound() {
        when(userMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.loadUserByUsername("ghost"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void loadUserByUsernameThrowsWhenUserDisabled() {
        User disabled = enabledUser("disabled01", "STAFF");
        disabled.setEnabled(false);
        when(userMapper.selectOne(any())).thenReturn(disabled);

        assertThatThrownBy(() -> service.loadUserByUsername("disabled01"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("disabled01");
    }

    @Test
    void loadUserByUsernameMapsEveryRole() {
        for (Role role : Role.values()) {
            when(userMapper.selectOne(any())).thenReturn(enabledUser("u-" + role, role.name()));

            UserPrincipal principal = (UserPrincipal) service.loadUserByUsername("u-" + role);

            assertThat(principal.getRole()).isEqualTo(role);
        }
    }
}
