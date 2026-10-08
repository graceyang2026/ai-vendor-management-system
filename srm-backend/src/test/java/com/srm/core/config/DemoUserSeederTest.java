package com.srm.core.config;

import com.srm.core.common.enums.Role;
import com.srm.core.entity.User;
import com.srm.core.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DemoUserSeederTest {

    private UserMapper userMapper;
    private PasswordEncoder passwordEncoder;
    private DemoUserSeeder seeder;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        seeder = new DemoUserSeeder(userMapper, passwordEncoder);
        when(passwordEncoder.encode(any())).thenAnswer(inv -> "encoded:" + inv.getArgument(0));
    }

    private void enableSeeding(boolean enabled) {
        ReflectionTestUtils.setField(seeder, "seedDemoUsers", enabled);
    }

    @Test
    void seedsThreeDemoAccountsWhenTableEmptyAndSwitchOn() throws Exception {
        enableSeeding(true);
        when(userMapper.selectCount(null)).thenReturn(0L);

        seeder.run(null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper, times(3)).insert(captor.capture());
        List<User> inserted = captor.getAllValues();

        assertThat(inserted).extracting(User::getUsername)
                .containsExactly("admin", "staff01", "auditor01");
        assertThat(inserted).extracting(User::getRole)
                .containsExactly("ADMIN", "STAFF", "AUDITOR");
        assertThat(inserted).allMatch(User::getEnabled);
        assertThat(inserted).extracting(User::getPasswordHash)
                .containsExactly("encoded:Admin@123", "encoded:Staff@123", "encoded:Auditor@123");
        assertThat(inserted).extracting(User::getRealName)
                .containsExactly("系统管理员", "张三", "李四");
    }

    @Test
    void skipsSeedingWhenTableNotEmpty() throws Exception {
        enableSeeding(true);
        when(userMapper.selectCount(null)).thenReturn(5L);

        seeder.run(null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper, never()).insert(captor.capture());
    }

    @Test
    void skipsSeedingWhenSwitchOff() throws Exception {
        enableSeeding(false);

        seeder.run(null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper, never()).selectCount(null);
        verify(userMapper, never()).insert(captor.capture());
    }

    @Test
    void seedsWhenCountIsNullForDefensiveness() throws Exception {
        enableSeeding(true);
        when(userMapper.selectCount(null)).thenReturn(null);

        seeder.run(null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper, times(3)).insert(captor.capture());
    }
}
