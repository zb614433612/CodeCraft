package com.example.agentdeepseek.tool;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表
 * 管理所有可用的工具，提供注册和查找功能
 */
@Component
public class ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    /**
     * 注册工具
     * @param tool 工具实例
     */
    public void register(Tool tool) {
        if (tool == null) {
            throw new IllegalArgumentException("Tool cannot be null");
        }
        tools.put(tool.getName(), tool);
    }

    /**
     * 根据名称查找工具
     * @param name 工具名称
     * @return 工具实例，如果不存在则返回null
     */
    public Tool getTool(String name) {
        return tools.get(name);
    }

    /**
     * 获取所有工具
     * @return 工具集合
     */
    public Collection<Tool> getAllTools() {
        return Collections.unmodifiableCollection(tools.values());
    }

    /**
     * 检查工具是否存在
     * @param name 工具名称
     * @return 是否存在
     */
    public boolean containsTool(String name) {
        return tools.containsKey(name);
    }

    /**
     * 移除工具
     * @param name 工具名称
     * @return 被移除的工具实例，如果不存在则返回null
     */
    public Tool removeTool(String name) {
        return tools.remove(name);
    }

    /**
     * 清空所有工具
     */
    public void clear() {
        tools.clear();
    }

    /**
     * 获取工具数量
     * @return 工具数量
     */
    public int size() {
        return tools.size();
    }
}