package com.example.agentdeepseek.service.impl;

import com.example.agentdeepseek.mapper.MenuMapper;
import com.example.agentdeepseek.mapper.RoleMapper;
import com.example.agentdeepseek.mapper.RoleMenuMapper;
import com.example.agentdeepseek.model.entity.Menu;
import com.example.agentdeepseek.model.entity.Role;
import com.example.agentdeepseek.service.MenuService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class MenuServiceImpl implements MenuService {

    @Autowired
    private MenuMapper menuMapper;

    @Autowired
    private RoleMapper roleMapper;

    @Autowired
    private RoleMenuMapper roleMenuMapper;

    @Override
    public List<Menu> getAllMenus() {
        return menuMapper.selectAll();
    }

    @Override
    public Menu createMenu(Menu menu) {
        if (menu.getSortOrder() == null) menu.setSortOrder(1);
        if (menu.getVisible() == null) menu.setVisible(1);
        if (menu.getStatus() == null) menu.setStatus(1);
        if (menu.getMenuType() == null) menu.setMenuType("LINK");
        menuMapper.insert(menu);
        log.info("创建菜单: id={}, name={}, type={}", menu.getId(), menu.getName(), menu.getMenuType());
        return menu;
    }

    @Override
    public void updateMenu(Menu menu) {
        Menu existing = menuMapper.selectById(menu.getId());
        if (existing == null) throw new RuntimeException("菜单不存在");
        if (menu.getName() == null) menu.setName(existing.getName());
        if (menu.getPath() == null) menu.setPath(existing.getPath());
        if (menu.getIcon() == null) menu.setIcon(existing.getIcon());
        if (menu.getMenuType() == null) menu.setMenuType(existing.getMenuType());
        if (menu.getSortOrder() == null) menu.setSortOrder(existing.getSortOrder());
        if (menu.getStatus() == null) menu.setStatus(existing.getStatus() != null ? existing.getStatus() : 1);
        if (menu.getVisible() == null) menu.setVisible(existing.getVisible() != null ? existing.getVisible() : 1);
        menuMapper.update(menu);
        log.info("更新菜单: id={}, name={}", menu.getId(), menu.getName());
    }

    @Override
    public void deleteMenu(Long id) {
        Menu menu = menuMapper.selectById(id);
        if (menu == null) throw new RuntimeException("菜单不存在");
        // 删除关联的角色菜单记录
        roleMenuMapper.deleteByMenuId(id);
        menuMapper.delete(id);
        log.info("删除菜单: id={}, name={}", id, menu.getName());
    }

    @Override
    public List<Menu> getCurrentUserMenus(HttpServletRequest request, String menuType) {
        String role = (String) request.getAttribute("userRole");
        String roleCode = role != null ? role : "user";
        Role userRole = roleMapper.selectByCode(roleCode);
        if (userRole == null) return new ArrayList<>();
        List<Long> menuIds = roleMenuMapper.selectMenuIdsByRoleId(userRole.getId());
        if (menuIds.isEmpty()) return new ArrayList<>();
        List<Menu> allMenus = menuMapper.selectByIds(menuIds);
        return allMenus.stream()
                .filter(m -> menuType.equals(m.getMenuType()) && m.getStatus() != null && m.getStatus() == 1)
                .sorted(Comparator.comparingInt(Menu::getSortOrder))
                .collect(Collectors.toList());
    }
}
