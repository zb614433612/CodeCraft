package com.example.agentdeepseek.service;

import com.example.agentdeepseek.mapper.McpServerMapper;
import com.example.agentdeepseek.mcp.client.McpClientManager;
import com.example.agentdeepseek.mcp.client.McpConnection;
import com.example.agentdeepseek.model.entity.McpServerConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP 外部服务器配置服务
 * 提供 mcp_server 配置的 CRUD，并在创建/更新/删除时联动 McpClientManager
 * 完成连接的建立、重连与断开（enabled=1 且 autoRegister=1 时自动注册工具）。
 */
@Slf4j
@Service
public class McpServerService {

    private final McpServerMapper mcpServerMapper;
    private final McpClientManager mcpClientManager;

    public McpServerService(McpServerMapper mcpServerMapper, McpClientManager mcpClientManager) {
        this.mcpServerMapper = mcpServerMapper;
        this.mcpClientManager = mcpClientManager;
    }

    // ==================== 查询 ====================

    /**
     * 查询全部服务器配置（合并连接状态）
     */
    public List<McpServerVO> listAll() {
        List<McpServerConfig> configs = mcpServerMapper.selectAll();
        List<McpServerVO> result = new ArrayList<>();
        for (McpServerConfig config : configs) {
            result.add(toVO(config, mcpClientManager.getConnection(config.getId())));
        }
        return result;
    }

    /**
     * 查询单个服务器配置（合并连接状态）
     */
    public McpServerVO getById(Long id) {
        McpServerConfig config = mcpServerMapper.selectById(id);
        if (config == null) {
            return null;
        }
        return toVO(config, mcpClientManager.getConnection(id));
    }

    // ==================== 写操作 ====================

    /**
     * 新增服务器配置；enabled=1 且 autoRegister=1 时立即尝试连接。
     * 注意：不加 @Transactional——连接/工具注册属于不可回滚的副作用，
     * 若包裹在事务内，事务回滚时会出现「配置不存在但连接已建立」的幽灵连接。
     */
    public McpServerVO create(McpServerConfig config) {
        validate(config, null);
        config.setId(null);
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        mcpServerMapper.insert(config);
        log.info("MCP 服务器配置已创建: id={}, name={}, type={}", config.getId(), config.getName(), config.getType());

        McpConnection conn = null;
        if (isAutoConnect(config)) {
            conn = mcpClientManager.connectAndRegister(config);
        }
        return toVO(config, conn);
    }

    /**
     * 更新服务器配置；启用状态变化时自动重连/断开。
     * 同 create：不加 @Transactional（连接副作用不可回滚）。
     */
    public McpServerVO update(Long id, McpServerConfig config) {
        McpServerConfig existing = mcpServerMapper.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("MCP 服务器配置不存在: " + id);
        }
        validate(config, id);
        config.setId(id);
        config.setCreatedAt(existing.getCreatedAt());
        config.setUpdatedAt(LocalDateTime.now());
        mcpServerMapper.update(config);
        log.info("MCP 服务器配置已更新: id={}, name={}", id, config.getName());

        // 同步连接状态：原连接与目标状态不一致时重连
        McpConnection conn = mcpClientManager.getConnection(id);
        boolean wasConnected = conn != null && conn.getClient() != null;
        if (wasConnected && !isAutoConnect(config)) {
            mcpClientManager.disconnect(id);
            return toVO(config, null);
        }
        if (isAutoConnect(config)) {
            if (wasConnected) {
                mcpClientManager.disconnect(id);
            }
            conn = mcpClientManager.connectAndRegister(config);
        }
        return toVO(config, conn);
    }

    /**
     * 删除服务器配置（先断开连接再删除）。
     * 同 create/update：不加 @Transactional（断开副作用不可回滚）。
     */
    public void delete(Long id) {
        McpServerConfig existing = mcpServerMapper.selectById(id);
        if (existing == null) {
            return;
        }
        mcpClientManager.removeConnection(id);
        mcpServerMapper.delete(id);
        log.info("MCP 服务器配置已删除: id={}, name={}", id, existing.getName());
    }

    // ==================== 连接操作 ====================

    /**
     * 手动连接指定服务器
     */
    public McpServerVO connect(Long id) {
        McpServerConfig config = mcpServerMapper.selectById(id);
        if (config == null) {
            throw new IllegalArgumentException("MCP 服务器配置不存在: " + id);
        }
        McpConnection conn = mcpClientManager.connectAndRegister(config);
        return toVO(config, conn);
    }

    /**
     * 手动断开指定服务器
     */
    public McpServerVO disconnect(Long id) {
        McpServerConfig config = mcpServerMapper.selectById(id);
        if (config == null) {
            throw new IllegalArgumentException("MCP 服务器配置不存在: " + id);
        }
        mcpClientManager.disconnect(id);
        return toVO(config, null);
    }

    /**
     * 重新拉取指定服务器的工具列表（断开后重连）
     */
    public McpServerVO refresh(Long id) {
        McpServerConfig config = mcpServerMapper.selectById(id);
        if (config == null) {
            throw new IllegalArgumentException("MCP 服务器配置不存在: " + id);
        }
        mcpClientManager.refreshTools(id);
        return toVO(config, mcpClientManager.getConnection(id));
    }

    // ==================== 内部方法 ====================

    private boolean isAutoConnect(McpServerConfig config) {
        return Integer.valueOf(1).equals(config.getEnabled())
                && Integer.valueOf(1).equals(config.getAutoRegister());
    }

    private void validate(McpServerConfig config, Long excludeId) {
        if (config == null) {
            throw new IllegalArgumentException("配置不能为空");
        }
        if (!StringUtils.hasText(config.getName())) {
            throw new IllegalArgumentException("服务器名称不能为空");
        }
        String type = config.getType() == null ? "" : config.getType().toLowerCase();
        if (!"http".equals(type) && !"stdio".equals(type)) {
            throw new IllegalArgumentException("传输类型必须为 http 或 stdio");
        }
        if ("http".equals(type) && !StringUtils.hasText(config.getUrl())) {
            throw new IllegalArgumentException("http 类型必须配置 url");
        }
        if ("stdio".equals(type) && !StringUtils.hasText(config.getCommand())) {
            throw new IllegalArgumentException("stdio 类型必须配置 command");
        }
        config.setType(type);
        // 默认值
        if (config.getEnabled() == null) {
            config.setEnabled(0);
        }
        if (config.getAutoRegister() == null) {
            config.setAutoRegister(0);
        }
        if (!StringUtils.hasText(config.getPermissionLevel())) {
            config.setPermissionLevel("SAFE");
        }
        // 名称唯一性校验
        for (McpServerConfig c : mcpServerMapper.selectAll()) {
            if (c.getName().equalsIgnoreCase(config.getName()) && !c.getId().equals(excludeId)) {
                throw new IllegalArgumentException("服务器名称已存在: " + config.getName());
            }
        }
    }

    /**
     * 合并配置与连接状态为展示 VO
     */
    private McpServerVO toVO(McpServerConfig config, McpConnection conn) {
        McpServerVO vo = new McpServerVO();
        vo.id = config.getId();
        vo.name = config.getName();
        vo.type = config.getType();
        vo.url = config.getUrl();
        vo.command = config.getCommand();
        // 敏感信息不回显明文：仅暴露是否已配置请求头（避免 Authorization 等密钥泄露）
        vo.hasHeaders = StringUtils.hasText(config.getHeaders());
        vo.toolPrefix = config.getToolPrefix();
        vo.permissionLevel = config.getPermissionLevel();
        vo.enabled = config.getEnabled();
        vo.autoRegister = config.getAutoRegister();
        vo.createdAt = config.getCreatedAt();
        vo.updatedAt = config.getUpdatedAt();

        if (conn != null) {
            vo.status = conn.getStatus().name();
            vo.errorMessage = conn.getErrorMessage();
            vo.connectedAt = conn.getConnectedAt();
            vo.toolCount = conn.getTools().size();
            vo.registeredToolCount = conn.getRegisteredToolNames().size();
        } else {
            vo.status = "DISABLED";
            vo.toolCount = 0;
            vo.registeredToolCount = 0;
        }
        return vo;
    }

    /**
     * MCP 服务器配置 + 连接状态展示对象
     */
    public static class McpServerVO {
        private Long id;
        private String name;
        private String type;
        private String url;
        private String command;
        /** 是否已配置请求头（不回显明文，避免密钥泄露） */
        private boolean hasHeaders;
        private String toolPrefix;
        private String permissionLevel;
        private Integer enabled;
        private Integer autoRegister;
        private java.time.LocalDateTime createdAt;
        private java.time.LocalDateTime updatedAt;
        /** 连接状态：CONNECTED / FAILED / DISABLED */
        private String status;
        private String errorMessage;
        private java.time.LocalDateTime connectedAt;
        /** 拉取到的外部工具数量 */
        private int toolCount;
        /** 实际注册进 ToolRegistry 的工具数量 */
        private int registeredToolCount;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }
        public boolean isHasHeaders() { return hasHeaders; }
        public void setHasHeaders(boolean hasHeaders) { this.hasHeaders = hasHeaders; }
        public String getToolPrefix() { return toolPrefix; }
        public void setToolPrefix(String toolPrefix) { this.toolPrefix = toolPrefix; }
        public String getPermissionLevel() { return permissionLevel; }
        public void setPermissionLevel(String permissionLevel) { this.permissionLevel = permissionLevel; }
        public Integer getEnabled() { return enabled; }
        public void setEnabled(Integer enabled) { this.enabled = enabled; }
        public Integer getAutoRegister() { return autoRegister; }
        public void setAutoRegister(Integer autoRegister) { this.autoRegister = autoRegister; }
        public java.time.LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(java.time.LocalDateTime createdAt) { this.createdAt = createdAt; }
        public java.time.LocalDateTime getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(java.time.LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        public java.time.LocalDateTime getConnectedAt() { return connectedAt; }
        public void setConnectedAt(java.time.LocalDateTime connectedAt) { this.connectedAt = connectedAt; }
        public int getToolCount() { return toolCount; }
        public void setToolCount(int toolCount) { this.toolCount = toolCount; }
        public int getRegisteredToolCount() { return registeredToolCount; }
        public void setRegisteredToolCount(int registeredToolCount) { this.registeredToolCount = registeredToolCount; }
    }
}
