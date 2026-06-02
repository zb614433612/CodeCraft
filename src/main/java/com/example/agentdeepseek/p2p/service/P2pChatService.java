package com.example.agentdeepseek.p2p.service;

import com.example.agentdeepseek.mapper.P2pChatMessageMapper;
import com.example.agentdeepseek.model.entity.P2pChatMessage;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * P2P 聊天记录服务
 */
@Service
public class P2pChatService {

    private static final Logger log = LoggerFactory.getLogger(P2pChatService.class);

    @Autowired
    private P2pChatMessageMapper mapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 启动时自动建表 + 兼容旧表结构迁移（不删旧数据）
     */
    @PostConstruct
    public void initTable() {
        mapper.createTableIfNotExists();
        mapper.createIndexPeer();
        mapper.createIndexTime();
        // 兼容旧表结构：尝试添加新列（忽略列已存在的错误）
        migrateTable();
        log.info("[P2P] Chat message table and indexes ensured");
    }

    /**
     * 兼容旧表迁移：如果表是旧结构（缺少 message_type 等列），补充新列。
     * 每个 ALTER 用 try-catch 包裹，避免列已存在时报错。
     */
    private void migrateTable() {
        String[] alters = {
            "ALTER TABLE p2p_chat_message ADD COLUMN message_type VARCHAR(20) DEFAULT 'chat'",
            "ALTER TABLE p2p_chat_message ADD COLUMN agent_config_id BIGINT DEFAULT NULL",
            "ALTER TABLE p2p_chat_message ADD COLUMN agent_name VARCHAR(100) DEFAULT NULL",
            "ALTER TABLE p2p_chat_message ADD COLUMN file_name VARCHAR(255) DEFAULT NULL",
            "ALTER TABLE p2p_chat_message ADD COLUMN file_size BIGINT DEFAULT NULL",
            "ALTER TABLE p2p_chat_message ADD COLUMN mime_type VARCHAR(100) DEFAULT NULL",
            "ALTER TABLE p2p_chat_message ADD COLUMN file_category VARCHAR(20) DEFAULT NULL",
            "ALTER TABLE p2p_chat_message ADD COLUMN transfer_id VARCHAR(36) DEFAULT NULL",
            "ALTER TABLE p2p_chat_message ADD COLUMN file_status VARCHAR(20) DEFAULT NULL",
            "ALTER TABLE p2p_chat_message ADD COLUMN local_path VARCHAR(500) DEFAULT NULL"
        };
        for (String sql : alters) {
            try {
                jdbcTemplate.execute(sql);
                log.info("[P2P] Migrated: {}", sql);
            } catch (Exception e) {
                // 列已存在时会报错，忽略即可
                log.debug("[P2P] Migration skipped (column likely exists): {}", e.getMessage());
            }
        }
    }

    /**
     * 保存消息
     */
    public void saveMessage(String peerId, String senderName, String content, String direction) {
        saveMessage(peerId, senderName, content, direction, "chat", null, null);
    }

    /**
     * 保存消息（带消息类型和Agent关联）
     */
    public void saveMessage(String peerId, String senderName, String content, String direction,
                            String messageType, Long agentConfigId, String agentName) {
        P2pChatMessage msg = new P2pChatMessage(peerId, senderName, content, direction,
                messageType, agentConfigId, agentName, LocalDateTime.now());
        mapper.insert(msg);
    }

    /**
     * 保存文件传输消息（带完整文件元信息）
     */
    public void saveFileMessage(String peerId, String senderName, String content, String direction,
                                String messageType, String fileName, Long fileSize, String mimeType,
                                String fileCategory, String transferId, String fileStatus, String localPath) {
        P2pChatMessage msg = new P2pChatMessage(peerId, senderName, content, direction,
                messageType, null, null, LocalDateTime.now());
        msg.setFileName(fileName);
        msg.setFileSize(fileSize);
        msg.setMimeType(mimeType);
        msg.setFileCategory(fileCategory);
        msg.setTransferId(transferId);
        msg.setFileStatus(fileStatus);
        msg.setLocalPath(localPath);
        mapper.insert(msg);
    }

    /**
     * 获取历史消息（分页，最新50条）
     */
    public List<P2pChatMessage> getHistory(String peerId, int offset, int limit) {
        return mapper.findByPeerId(peerId, offset, limit);
    }

    /**
     * 删除指定节点的聊天记录
     */
    public void deleteByPeerId(String peerId) {
        int count = mapper.deleteByPeerId(peerId);
        log.info("[P2P] Deleted {} chat messages for peer: {}", count, peerId);
    }

    /**
     * 按 transferId 查找文件消息（用于获取本地存储路径）
     */
    public P2pChatMessage findByTransferId(String transferId) {
        return mapper.findByTransferId(transferId);
    }
}
