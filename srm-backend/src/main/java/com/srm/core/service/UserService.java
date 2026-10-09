package com.srm.core.service;

import com.srm.core.common.PageResult;
import com.srm.core.dto.user.UserCreateRequest;
import com.srm.core.dto.user.UserResponse;
import com.srm.core.dto.user.UserStatusUpdateRequest;
import com.srm.core.dto.user.UserUpdateRequest;
import com.srm.core.security.UserPrincipal;

/**
 * 模块 5：用户管理服务接口（docs/api-spec.md §6）。
 * 所有写操作均须传入操作人 UserPrincipal，用于防自锁校验与审计留痕。
 */
public interface UserService {

    /**
     * 分页查询用户列表，created_at 降序。
     */
    PageResult<UserResponse> list(int page, int pageSize);

    /**
     * 新增用户（BCrypt 编码密码）；用户名已存在 → 40903；成功后审计 CREATE_USER。
     */
    UserResponse create(UserCreateRequest request, UserPrincipal operator);

    /**
     * 修改用户资料（仅 real_name/role）；
     * 请求体含非 null username/password → 40001；
     * 修改自己角色 → 40302；用户不存在 → 40401；成功后审计 UPDATE_USER。
     */
    UserResponse update(Long id, UserUpdateRequest request, UserPrincipal operator);

    /**
     * 启用/停用用户；
     * 停用自己 → 40302；幂等（目标状态与当前一致时不写 DB/审计）；
     * 用户不存在 → 40401；成功后审计 ENABLE_USER / DISABLE_USER。
     */
    void updateStatus(Long id, UserStatusUpdateRequest request, UserPrincipal operator);
}
