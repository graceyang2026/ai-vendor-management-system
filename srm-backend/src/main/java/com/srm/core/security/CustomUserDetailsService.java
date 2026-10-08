package com.srm.core.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.srm.core.common.enums.Role;
import com.srm.core.entity.User;
import com.srm.core.mapper.UserMapper;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

/**
 * 登录时按用户名加载用户（docs/backend-interface-design.md 第 8 节）：
 * 找不到或 enabled=false 均抛 UsernameNotFoundException（不区分原因，避免泄露账号是否存在）。
 */
@Component
public class CustomUserDetailsService implements UserDetailsService {

    private final UserMapper userMapper;

    public CustomUserDetailsService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
            throw new UsernameNotFoundException("用户不存在或已停用: " + username);
        }
        return new UserPrincipal(
                user.getId(),
                user.getUsername(),
                user.getPasswordHash(),
                user.getRealName(),
                Role.valueOf(user.getRole()),
                true);
    }
}
