package com.example.agentdeepseek.service;

import com.example.agentdeepseek.model.entity.Menu;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

public interface MenuService {

    List<Menu> getAllMenus();

    Menu createMenu(Menu menu);

    void updateMenu(Menu menu);

    void deleteMenu(Long id);

    List<Menu> getCurrentUserMenus(HttpServletRequest request, String menuType);
}
