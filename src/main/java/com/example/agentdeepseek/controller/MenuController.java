package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.model.entity.Menu;
import com.example.agentdeepseek.service.MenuService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/menu")
@Tag(name = "菜单管理", description = "菜单增删改查、用户菜单获取")
public class MenuController {

    @Autowired
    private MenuService menuService;

    @Operation(summary = "获取菜单树", description = "获取完整菜单列表（管理员用），可按类型筛选")
    @GetMapping("/tree")
    public ApiResponse<List<Menu>> getMenuTree(
            @RequestParam(required = false) String type,
            HttpServletRequest request) {
        checkAdmin(request);
        List<Menu> menus = menuService.getAllMenus();
        if (type != null && !type.trim().isEmpty()) {
            menus = menus.stream()
                    .filter(m -> type.equals(m.getMenuType()))
                    .collect(Collectors.toList());
        }
        return ApiResponse.success(menus);
    }

    @Operation(summary = "创建菜单")
    @PostMapping("/create")
    public ApiResponse<Menu> createMenu(@RequestBody Menu menu, HttpServletRequest request) {
        checkAdmin(request);
        Menu created = menuService.createMenu(menu);
        return ApiResponse.success(created);
    }

    @Operation(summary = "更新菜单")
    @PutMapping("/update")
    public ApiResponse<Void> updateMenu(@RequestBody Menu menu, HttpServletRequest request) {
        checkAdmin(request);
        menuService.updateMenu(menu);
        return ApiResponse.success(null);
    }

    @Operation(summary = "删除菜单")
    @DeleteMapping("/delete/{id}")
    public ApiResponse<Void> deleteMenu(@PathVariable Long id, HttpServletRequest request) {
        checkAdmin(request);
        menuService.deleteMenu(id);
        return ApiResponse.success(null);
    }

    @Operation(summary = "获取当前用户可见菜单", description = "根据用户角色返回可见的菜单列表，供前端Layout渲染")
    @GetMapping("/user-menus")
    public ApiResponse<List<Menu>> getUserMenus(
            @RequestParam(defaultValue = "LINK") String type,
            HttpServletRequest request) {
        List<Menu> menus = menuService.getCurrentUserMenus(request, type);
        return ApiResponse.success(menus);
    }

    private void checkAdmin(HttpServletRequest request) {
        String role = (String) request.getAttribute("userRole");
        if (!"admin".equals(role)) {
            throw new RuntimeException("权限不足，需要管理员权限");
        }
    }
}
