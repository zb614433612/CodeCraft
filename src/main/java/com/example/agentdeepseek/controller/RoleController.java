package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.model.entity.Role;
import com.example.agentdeepseek.service.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/role")
@Tag(name = "角色管理", description = "角色列表、角色菜单分配")
public class RoleController {

    @Autowired
    private RoleService roleService;

    @Operation(summary = "获取角色列表")
    @GetMapping("/list")
    public ApiResponse<List<Role>> getRoleList(HttpServletRequest request) {
        checkAdmin(request);
        return ApiResponse.success(roleService.getAllRoles());
    }

    @Operation(summary = "获取角色已分配的菜单ID列表")
    @GetMapping("/menus/{roleId}")
    public ApiResponse<List<Long>> getRoleMenuIds(@PathVariable Long roleId, HttpServletRequest request) {
        checkAdmin(request);
        return ApiResponse.success(roleService.getRoleMenuIds(roleId));
    }

    @Operation(summary = "为角色分配菜单")
    @PostMapping("/assign-menus")
    public ApiResponse<Void> assignMenus(@RequestBody Map<String, Object> params, HttpServletRequest request) {
        checkAdmin(request);
        Long roleId = Long.valueOf(params.get("roleId").toString());
        @SuppressWarnings("unchecked")
        List<Integer> menuIdInts = (List<Integer>) params.get("menuIds");
        List<Long> menuIds = menuIdInts.stream().map(Long::valueOf).collect(Collectors.toList());
        roleService.assignMenusToRole(roleId, menuIds);
        return ApiResponse.success(null);
    }

    @Operation(summary = "创建角色")
    @PostMapping("/create")
    public ApiResponse<Role> createRole(@RequestBody Role role, HttpServletRequest request) {
        checkAdmin(request);
        Role created = roleService.createRole(role);
        return ApiResponse.success(created);
    }

    @Operation(summary = "更新角色")
    @PutMapping("/update")
    public ApiResponse<Void> updateRole(@RequestBody Role role, HttpServletRequest request) {
        checkAdmin(request);
        roleService.updateRole(role);
        return ApiResponse.success(null);
    }

    @Operation(summary = "删除角色")
    @DeleteMapping("/delete/{id}")
    public ApiResponse<Void> deleteRole(@PathVariable Long id, HttpServletRequest request) {
        checkAdmin(request);
        roleService.deleteRole(id);
        return ApiResponse.success(null);
    }

    private void checkAdmin(HttpServletRequest request) {
        String role = (String) request.getAttribute("userRole");
        if (!"admin".equals(role)) {
            throw new RuntimeException("权限不足，需要管理员权限");
        }
    }
}
