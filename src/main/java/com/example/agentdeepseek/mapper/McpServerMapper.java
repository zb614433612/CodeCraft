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

    /**
     * 更新服务器配置（动态 SQL：null 字段保持原值）
     *
     * <p>为什么不能全量覆盖：{@code headers} 含 Authorization 等密钥，列表接口（{@code McpServerVO}）
     * 只回显 {@code hasHeaders} 布尔值，前端编辑时拿不到明文，留空即表示「保持原值」。
     * 若这里用全量 UPDATE，{@code headers=null} 会把已配置的密钥清空，导致原本能连上的
     * 服务器在改个名字后突然鉴权失败，属于静默数据丢失。</p>
     *
     * <p>需要真正清空 headers 时，前端传空对象字符串 {@code "{}"}（非 null）即可覆盖。</p>
     */
    @Update("<script>" +
            "UPDATE mcp_server SET " +
            "<if test='name != null'>name = #{name}, </if>" +
            "<if test='type != null'>type = #{type}, </if>" +
            "<if test='url != null'>url = #{url}, </if>" +
            "<if test='command != null'>command = #{command}, </if>" +
            "<if test='headers != null'>headers = #{headers}, </if>" +
            "<if test='toolPrefix != null'>tool_prefix = #{toolPrefix}, </if>" +
            "<if test='permissionLevel != null'>permission_level = #{permissionLevel}, </if>" +
            "<if test='enabled != null'>enabled = #{enabled}, </if>" +
            "<if test='autoRegister != null'>auto_register = #{autoRegister}, </if>" +
            "updated_at = #{updatedAt} " +
            "WHERE id = #{id}" +
            "</script>")
    int update(McpServerConfig config);

    @Delete("DELETE FROM mcp_server WHERE id = #{id}")
    int delete(Long id);
}
