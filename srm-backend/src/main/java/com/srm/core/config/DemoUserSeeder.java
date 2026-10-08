package com.srm.core.config;

import com.srm.core.common.enums.Role;
import com.srm.core.entity.User;
import com.srm.core.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 演示账号种子（docs/backend-interface-design.md 第 10 节）：
 * 仅当 srm.seed-demo-users=true 且 sys_user 表为空时，
 * 自动播种 admin/staff01/auditor01 三个演示账号。
 */
@Component
public class DemoUserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoUserSeeder.class);

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Value("${srm.seed-demo-users:true}")
    private boolean seedDemoUsers;

    public DemoUserSeeder(UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!seedDemoUsers) {
            log.info("演示账号播种已禁用（srm.seed-demo-users=false），跳过");
            return;
        }
        Long count = userMapper.selectCount(null);
        if (count != null && count > 0) {
            log.info("sys_user 表已有 {} 条数据，跳过演示账号播种", count);
            return;
        }
        insertDemoUser("admin", "Admin@123", "系统管理员", Role.ADMIN);
        insertDemoUser("staff01", "Staff@123", "张三", Role.STAFF);
        insertDemoUser("auditor01", "Auditor@123", "李四", Role.AUDITOR);
        log.info("已播种 3 个演示账号：admin / staff01 / auditor01");
    }

    private void insertDemoUser(String username, String rawPassword, String realName, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRealName(realName);
        user.setRole(role.name());
        user.setEnabled(true);
        userMapper.insert(user);
    }
}
