package com.example.agentdeepseek.service;

import com.example.agentdeepseek.model.entity.Role;

import java.util.List;

public interface RoleService {

    List<Role> getAllRoles();

    List<Long> getRoleMenuIds(Long roleId);

    void assignMenusToRole(Long roleId, List<Long> menuIds);

    Role createRole(Role role);

    void updateRole(Role role);

    void deleteRole(Long id);
}
