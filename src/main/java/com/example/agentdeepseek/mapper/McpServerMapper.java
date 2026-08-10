package com.example.agentdeepseek.mapper;

import com.example.agentdeepseek.model.entity.McpServerConfig;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * MCP 外部服务器配置数据访问接口
 */
@Mapper
@Repository
public interface McpServerMapper {

    @Select("SELECT id, name, type, url, command, headers, tool_prefix, permission_level, " +
            "enabled, auto_register, created_at, updated_at FROM mcp_server WHERE id = #{id}")
    @Results(id = "mcpServerResultMap", value = {
        @Result(property = "id", column = "id"),
        @Result(property = "name", column = "name"),
        @Result(property = "type", column = "type"),
        @Result(property = "url", column = "url"),
        @Result(property = "command", column = "command"),
        @Result(property = "headers", column = "headers"),
        @Result(property = "toolPrefix", column = "tool_prefix"),
        @Result(property = "permissionLevel", column = "permission_level"),
        @Result(property = "enabled", column = "enabled"),
        @Result(property = "autoRegister", column = "auto_register"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    McpServerConfig selectById(Long id);

    @Select("SELECT id, name, type, url, command, headers, tool_prefix, permission_level, " +
            "enabled, auto_register, created_at, updated_at FROM mcp_server " +
            "ORDER BY id ASC")
    @ResultMap("mcpServerResultMap")
    List<McpServerConfig> selectAll();

    @Select("SELECT id, name, type, url, command, headers, tool_prefix, permission_level, " +
            "enabled, auto_register, created_at, updated_at FROM mcp_server " +
            "WHERE enabled = 1 ORDER BY id ASC")
    @ResultMap("mcpServerResultMap")
    List<McpServerConfig> selectAllEnabled();

    @Insert("INSERT INTO mcp_server (name, type, url, command, headers, tool_prefix, " +
            "permission_level, enabled, auto_register, created_at, updated_at) " +
            "VALUES (#{name}, #{type}, #{url}, #{command}, #{headers}, #{toolPrefix}, " +
            "#{permissionLevel}, #{enabled}, #{autoRegister}, #{createdAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(McpServerConfig config);

    @Update("UPDATE mcp_server SET name = #{name}, type = #{type}, url = #{url}, command = #{command}, " +
            "headers = #{headers}, tool_prefix = #{toolPrefix}, permission_level = #{permissionLevel}, " +
            "enabled = #{enabled}, auto_register = #{autoRegister}, updated_at = #{updatedAt} WHERE id = #{id}")
    int update(McpServerConfig config);

    @Delete("DELETE FROM mcp_server WHERE id = #{id}")
    int delete(Long id);
}
