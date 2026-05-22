package com.example.agentdeepseek.service.impl;

import com.example.agentdeepseek.mapper.RoleMapper;
import com.example.agentdeepseek.mapper.RoleMenuMapper;
import com.example.agentdeepseek.model.entity.Role;
import com.example.agentdeepseek.service.RoleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Slf4j
public class RoleServiceImpl implements RoleService {

    @Autowired
    private RoleMapper roleMapper;

    @Autowired
    private RoleMenuMapper roleMenuMapper;

    @Override
    public List<Role> getAllRoles() {
        return roleMapper.selectAll();
    }

    @Override
    public List<Long> getRoleMenuIds(Long roleId) {
        return roleMenuMapper.selectMenuIdsByRoleId(roleId);
    }

    @Override
    @Transactional
    public void assignMenusToRole(Long roleId, List<Long> menuIds) {
        // 先删除旧的关联
        roleMenuMapper.deleteByRoleId(roleId);
        // 批量插入新的关联
        if (menuIds != null && !menuIds.isEmpty()) {
            for (Long menuId : menuIds) {
                roleMenuMapper.insert(roleId, menuId);
            }
        }
        log.info("分配菜单完成: roleId={}, menuCount={}", roleId, menuIds != null ? menuIds.size() : 0);
    }

    @Override
    public Role createRole(Role role) {
        if (role.getStatus() == null) role.setStatus(1);
        roleMapper.insert(role);
        log.info("创建角色: id={}, name={}, code={}", role.getId(), role.getName(), role.getCode());
        return role;
    }

    @Override
    public void updateRole(Role role) {
        roleMapper.update(role);
        log.info("更新角色: id={}, name={}", role.getId(), role.getName());
    }

    @Override
    @Transactional
    public void deleteRole(Long id) {
        // 删除角色关联的菜单权限
        roleMenuMapper.deleteByRoleId(id);
        roleMapper.delete(id);
        log.info("删除角色: id={}", id);
    }
}
