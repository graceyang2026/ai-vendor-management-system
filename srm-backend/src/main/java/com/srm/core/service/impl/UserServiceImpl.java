package com.srm.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.srm.core.audit.AuditAction;
import com.srm.core.audit.AuditResult;
import com.srm.core.audit.EntityType;
import com.srm.core.common.BusinessException;
import com.srm.core.common.ErrorCode;
import com.srm.core.common.PageResult;
import com.srm.core.dto.user.UserCreateRequest;
import com.srm.core.dto.user.UserResponse;
import com.srm.core.dto.user.UserStatusUpdateRequest;
import com.srm.core.dto.user.UserUpdateRequest;
import com.srm.core.entity.User;
import com.srm.core.mapper.UserMapper;
import com.srm.core.security.UserPrincipal;
import com.srm.core.service.AuditLogService;
import com.srm.core.service.UserService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 模块 5：用户管理服务实现（docs/api-spec.md §6）。
 * 防自锁铁律：ADMIN 不能停用自己（40302）/不能修改自己角色（40302）；
 * 审计隔离铁律：auditLogService.record() 内部已保证绝不上抛，此处直接调用。
 */
@Service
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    public UserServiceImpl(UserMapper userMapper, PasswordEncoder passwordEncoder,
                           AuditLogService auditLogService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
    }

    @Override
    public PageResult<UserResponse> list(int page, int pageSize) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
                .orderByDesc(User::getCreatedAt);
        IPage<User> result = userMapper.selectPage(new Page<>(page, pageSize), wrapper);
        List<UserResponse> items = result.getRecords().stream()
                .map(this::toResponse)
                .toList();
        return new PageResult<>(items, result.getTotal(), page, pageSize);
    }

    @Override
    @Transactional
    public UserResponse create(UserCreateRequest request, UserPrincipal operator) {
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, request.getUsername()));
        if (count != null && count > 0) {
            throw new BusinessException(ErrorCode.USERNAME_DUPLICATE, "用户名已存在");
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setRealName(request.getRealName());
        user.setRole(request.getRole().name());
        user.setEnabled(true);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);

        // 审计：新增用户 old_status=null, new_status="true"
        auditLogService.record(EntityType.USER, user.getId(), operator,
                AuditAction.CREATE_USER.name(), null, "true", AuditResult.SUCCESS, null);

        return toResponse(user);
    }

    @Override
    @Transactional
    public UserResponse update(Long id, UserUpdateRequest request, UserPrincipal operator) {
        // 禁止通过 PUT 修改 username/password（Q4 裁决：非 null 即 40001 拒绝）
        if (request.getUsername() != null || request.getPassword() != null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不允许修改用户名和密码");
        }

        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }

        // 防自锁：不允许修改自己的角色（角色有变化时触发）
        if (operator.getId().equals(id) && request.getRole() != null
                && !request.getRole().name().equals(user.getRole())) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "不允许修改自己的角色");
        }

        boolean changed = false;
        if (StringUtils.hasText(request.getRealName())) {
            user.setRealName(request.getRealName());
            changed = true;
        }
        if (request.getRole() != null) {
            user.setRole(request.getRole().name());
            changed = true;
        }

        if (changed) {
            user.setUpdatedAt(LocalDateTime.now());
            userMapper.updateById(user);
            auditLogService.record(EntityType.USER, id, operator,
                    AuditAction.UPDATE_USER.name(), null, null, AuditResult.SUCCESS, null);
        }

        return toResponse(user);
    }

    @Override
    @Transactional
    public void updateStatus(Long id, UserStatusUpdateRequest request, UserPrincipal operator) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }

        boolean newEnabled = Boolean.TRUE.equals(request.getEnabled());
        boolean oldEnabled = Boolean.TRUE.equals(user.getEnabled());

        // 防自锁：不允许停用自己
        if (operator.getId().equals(id) && !newEnabled) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "不允许停用自己");
        }

        // 幂等：目标状态与当前一致，不写 DB/审计
        if (newEnabled == oldEnabled) {
            return;
        }

        user.setEnabled(newEnabled);
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);

        String action = newEnabled ? AuditAction.ENABLE_USER.name() : AuditAction.DISABLE_USER.name();
        auditLogService.record(EntityType.USER, id, operator,
                action, String.valueOf(oldEnabled), String.valueOf(newEnabled),
                AuditResult.SUCCESS, null);
    }

    private UserResponse toResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .realName(user.getRealName())
                .role(user.getRole())
                .enabled(Boolean.TRUE.equals(user.getEnabled()))
                .createdAt(user.getCreatedAt())
                .build();
    }
}
