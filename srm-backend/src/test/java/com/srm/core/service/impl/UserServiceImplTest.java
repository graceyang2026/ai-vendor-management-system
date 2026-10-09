package com.srm.core.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.srm.core.audit.AuditAction;
import com.srm.core.audit.AuditResult;
import com.srm.core.audit.EntityType;
import com.srm.core.common.BusinessException;
import com.srm.core.common.ErrorCode;
import com.srm.core.common.PageResult;
import com.srm.core.common.enums.Role;
import com.srm.core.dto.user.UserCreateRequest;
import com.srm.core.dto.user.UserResponse;
import com.srm.core.dto.user.UserStatusUpdateRequest;
import com.srm.core.dto.user.UserUpdateRequest;
import com.srm.core.entity.User;
import com.srm.core.mapper.UserMapper;
import com.srm.core.security.UserPrincipal;
import com.srm.core.service.AuditLogService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模块 5 用户管理服务单元测试。
 * 锁定：
 * 1. create 用户名重复 → 40903；BCrypt 编码；审计 CREATE_USER
 * 2. update 不允许修改 username/password → 40001；改自己角色 → 40302；不存在 → 40401；审计 UPDATE_USER
 * 3. updateStatus 停用自己 → 40302；幂等不写 DB/审计；ENABLE_USER / DISABLE_USER 审计
 * 4. list 分页返回 PageResult，不含 password_hash
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuditLogService auditLogService;

    private UserServiceImpl userService;

    @BeforeAll
    static void initMybatisPlusTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), User.class);
    }

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl(userMapper, passwordEncoder, auditLogService);
    }

    private static UserPrincipal admin() {
        return new UserPrincipal(1L, "admin", null, "系统管理员", Role.ADMIN, true);
    }

    private static User existingUser(Long id, String username, Role role, boolean enabled) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setPasswordHash("encoded_hash");
        u.setRealName("张三");
        u.setRole(role.name());
        u.setEnabled(enabled);
        u.setCreatedAt(LocalDateTime.of(2026, 10, 1, 0, 0));
        u.setUpdatedAt(LocalDateTime.of(2026, 10, 1, 0, 0));
        return u;
    }

    // ===== create =====

    @Test
    void createSuccessEncodesPasswordAndAuditsCreateUser() {
        UserPrincipal operator = admin();
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode("Secret@123")).thenReturn("bcrypt_hash");
        when(userMapper.insert(any(User.class))).thenReturn(1);

        UserCreateRequest req = new UserCreateRequest();
        req.setUsername("staff02");
        req.setPassword("Secret@123");
        req.setRealName("李四");
        req.setRole(Role.STAFF);

        UserResponse resp = userService.create(req, operator);

        assertThat(resp.getUsername()).isEqualTo("staff02");
        assertThat(resp.getRole()).isEqualTo("STAFF");
        assertThat(resp.isEnabled()).isTrue();
        // password 绝不出现在 response
        assertThat(resp.toString()).doesNotContainIgnoringCase("password");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("bcrypt_hash");

        verify(auditLogService).record(
                eq(EntityType.USER), any(), eq(operator),
                eq(AuditAction.CREATE_USER.name()),
                eq(null), eq("true"), eq(AuditResult.SUCCESS), eq(null));
    }

    @Test
    void createDuplicateUsernameThrows40903() {
        when(userMapper.selectCount(any())).thenReturn(1L);

        UserCreateRequest req = new UserCreateRequest();
        req.setUsername("admin");
        req.setPassword("Any@1234");
        req.setRealName("超管");
        req.setRole(Role.ADMIN);

        assertThatThrownBy(() -> userService.create(req, admin()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.USERNAME_DUPLICATE));

        verify(userMapper, never()).insert(ArgumentMatchers.<User>any());
        org.mockito.Mockito.verifyNoInteractions(auditLogService);
    }

    // ===== update =====

    @Test
    void updateNotFoundThrows40401() {
        when(userMapper.selectById(99L)).thenReturn(null);

        UserUpdateRequest req = new UserUpdateRequest();
        req.setRealName("新名字");

        assertThatThrownBy(() -> userService.update(99L, req, admin()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void updateWithUsernameFieldThrows40001() {
        UserUpdateRequest req = new UserUpdateRequest();
        req.setUsername("hacker");

        assertThatThrownBy(() -> userService.update(2L, req, admin()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.VALIDATION_FAILED));

        verify(userMapper, never()).selectById(any());
    }

    @Test
    void updateWithPasswordFieldThrows40001() {
        UserUpdateRequest req = new UserUpdateRequest();
        req.setPassword("P@ssw0rd");

        assertThatThrownBy(() -> userService.update(2L, req, admin()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void updateSelfRoleChangeThrows40302() {
        when(userMapper.selectById(1L)).thenReturn(existingUser(1L, "admin", Role.ADMIN, true));

        UserUpdateRequest req = new UserUpdateRequest();
        req.setRole(Role.STAFF);

        assertThatThrownBy(() -> userService.update(1L, req, admin()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.STATUS_NOT_ALLOWED));

        verify(userMapper, never()).updateById(ArgumentMatchers.<User>any());
    }

    @Test
    void updateSuccessChangesFieldsAndAuditsUpdateUser() {
        UserPrincipal operator = admin();
        User user = existingUser(2L, "staff01", Role.STAFF, true);
        when(userMapper.selectById(2L)).thenReturn(user);
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        UserUpdateRequest req = new UserUpdateRequest();
        req.setRealName("采购员张三");
        req.setRole(Role.AUDITOR);

        UserResponse resp = userService.update(2L, req, operator);

        assertThat(resp.getRealName()).isEqualTo("采购员张三");
        assertThat(resp.getRole()).isEqualTo("AUDITOR");

        verify(userMapper).updateById(any(User.class));
        verify(auditLogService).record(
                eq(EntityType.USER), eq(2L), eq(operator),
                eq(AuditAction.UPDATE_USER.name()),
                eq(null), eq(null), eq(AuditResult.SUCCESS), eq(null));
    }

    // ===== updateStatus =====

    @Test
    void updateStatusNotFoundThrows40401() {
        when(userMapper.selectById(99L)).thenReturn(null);

        UserStatusUpdateRequest req = new UserStatusUpdateRequest();
        req.setEnabled(false);

        assertThatThrownBy(() -> userService.updateStatus(99L, req, admin()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void updateStatusDisableSelfThrows40302() {
        User adminUser = existingUser(1L, "admin", Role.ADMIN, true);
        when(userMapper.selectById(1L)).thenReturn(adminUser);

        UserStatusUpdateRequest req = new UserStatusUpdateRequest();
        req.setEnabled(false);

        assertThatThrownBy(() -> userService.updateStatus(1L, req, admin()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.STATUS_NOT_ALLOWED));

        verify(userMapper, never()).updateById(ArgumentMatchers.<User>any());
    }

    @Test
    void updateStatusEnableAuditsEnableUser() {
        UserPrincipal operator = admin();
        User user = existingUser(2L, "staff01", Role.STAFF, false);
        when(userMapper.selectById(2L)).thenReturn(user);
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        UserStatusUpdateRequest req = new UserStatusUpdateRequest();
        req.setEnabled(true);

        userService.updateStatus(2L, req, operator);

        verify(auditLogService).record(
                eq(EntityType.USER), eq(2L), eq(operator),
                eq(AuditAction.ENABLE_USER.name()),
                eq("false"), eq("true"), eq(AuditResult.SUCCESS), eq(null));
    }

    @Test
    void updateStatusIdempotentDoesNotUpdateDbOrAudit() {
        User user = existingUser(2L, "staff01", Role.STAFF, true);
        when(userMapper.selectById(2L)).thenReturn(user);

        UserStatusUpdateRequest req = new UserStatusUpdateRequest();
        req.setEnabled(true); // 已是 true，幂等

        userService.updateStatus(2L, req, admin());

        verify(userMapper, never()).updateById(ArgumentMatchers.<User>any());
        org.mockito.Mockito.verifyNoInteractions(auditLogService);
    }

    @Test
    void updateNoChangesDoesNotUpdateDbOrAudit() {
        UserPrincipal operator = admin();
        User user = existingUser(2L, "staff01", Role.STAFF, true);
        when(userMapper.selectById(2L)).thenReturn(user);

        // realName=null、role=null → changed=false，不写库、不审计
        UserUpdateRequest req = new UserUpdateRequest();

        UserResponse resp = userService.update(2L, req, operator);

        assertThat(resp.getUsername()).isEqualTo("staff01"); // 返回当前用户
        verify(userMapper, never()).updateById(ArgumentMatchers.<User>any());
        org.mockito.Mockito.verifyNoInteractions(auditLogService);
    }

    @Test
    void updateStatusDisableAuditsDisableUser() {
        UserPrincipal operator = admin();
        User user = existingUser(3L, "auditor01", Role.AUDITOR, true);
        when(userMapper.selectById(3L)).thenReturn(user);
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        UserStatusUpdateRequest req = new UserStatusUpdateRequest();
        req.setEnabled(false);

        userService.updateStatus(3L, req, operator);

        verify(auditLogService).record(
                eq(EntityType.USER), eq(3L), eq(operator),
                eq(AuditAction.DISABLE_USER.name()),
                eq("true"), eq("false"), eq(AuditResult.SUCCESS), eq(null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listReturnsPageResultWithoutPassword() {
        User user = existingUser(2L, "staff01", Role.STAFF, true);
        Page<User> mockPage = new Page<>(1, 20);
        mockPage.setRecords(List.of(user));
        mockPage.setTotal(1L);
        when(userMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenReturn(mockPage);

        PageResult<UserResponse> result = userService.list(1, 20);

        assertThat(result.getTotal()).isEqualTo(1L);
        assertThat(result.getPageSize()).isEqualTo(20);
        assertThat(result.getList()).hasSize(1);
        UserResponse row = result.getList().get(0);
        assertThat(row.getUsername()).isEqualTo("staff01");
        // password 绝不出现在 UserResponse
        assertThat(row.toString()).doesNotContainIgnoringCase("password");
    }


}
