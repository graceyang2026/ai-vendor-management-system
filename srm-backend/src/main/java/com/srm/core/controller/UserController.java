package com.srm.core.controller;

import com.srm.core.common.ApiResponse;
import com.srm.core.common.PageResult;
import com.srm.core.dto.user.UserCreateRequest;
import com.srm.core.dto.user.UserResponse;
import com.srm.core.dto.user.UserStatusUpdateRequest;
import com.srm.core.dto.user.UserUpdateRequest;
import com.srm.core.security.UserPrincipal;
import com.srm.core.service.UserService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模块 5：用户管理接口（docs/api-spec.md §6）。
 * 全部端点 ADMIN 专属（@PreAuthorize），普通角色访问 → 40301（由 RestAccessDeniedHandler 处理）。
 */
@RestController
@RequestMapping("/api/v1")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /** GET /users — 分页查询用户列表，page_size 默认 20 */
    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PageResult<UserResponse>> list(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        return ApiResponse.success(userService.list(page, pageSize));
    }

    /** POST /users — 新增用户 */
    @PostMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<UserResponse> create(
            @RequestBody @Valid UserCreateRequest request,
            @AuthenticationPrincipal UserPrincipal operator) {
        return ApiResponse.success(userService.create(request, operator));
    }

    /** PUT /users/{id} — 修改用户资料（仅 real_name/role）*/
    @PutMapping("/users/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<UserResponse> update(
            @PathVariable Long id,
            @RequestBody @Valid UserUpdateRequest request,
            @AuthenticationPrincipal UserPrincipal operator) {
        return ApiResponse.success(userService.update(id, request, operator));
    }

    /** PATCH /users/{id}/status — 启用/停用用户 */
    @PatchMapping("/users/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> updateStatus(
            @PathVariable Long id,
            @RequestBody @Valid UserStatusUpdateRequest request,
            @AuthenticationPrincipal UserPrincipal operator) {
        userService.updateStatus(id, request, operator);
        return ApiResponse.success(null);
    }
}
