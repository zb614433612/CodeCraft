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

    /**
     * 历史消息单次最多返回条数（2026-11-16 新增）。
     *
     * <p>原 Controller 的 {@code limit} 默认 50 且无上限，一次传百万级会把整张表
     * 读进内存再构造 Map 列表返回。这里以 200 为上限：前端一次只展示一屏，
     * 需要更早的历史应通过 offset 分页，而不是一次全取。</p>
     */
    private static final int MAX_PAGE_LIMIT = 200;

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
     * 获取历史消息（分页，默认最新 50 条）。
     *
     * <p><b>⚠️ 必须钳制参数（2026-11-16 修复）</b>：offset / limit 由 Controller
     * 直接取自 {@code @RequestParam}，此前无任何上限。风险两点：</p>
     * <ul>
     *   <li>{@code limit} 传极大值（如 1000000）→ 一次把整张表捞进内存，构造成 Map 列表
     *       再序列化返回，属于可被外部触发的内存/带宽放大（Peer 通道是可连入的入口）；</li>
     *   <li>{@code offset} 传负数或极大值 → SQL 报错或返回不可预期结果。</li>
     * </ul>
     * <p>这里统一收敛：offset 至少 0，limit 夹到 [1, MAX_PAGE_LIMIT]。放在 Service 层
     * 而不是 Controller，是因为其他调用方（如未来新增的回溯接口）也走同一入口。</p>
     */
    public List<P2pChatMessage> getHistory(String peerId, int offset, int limit) {
        int safeOffset = Math.max(offset, 0);
        int safeLimit = Math.max(1, Math.min(limit, MAX_PAGE_LIMIT));
        return mapper.findByPeerId(peerId, safeOffset, safeLimit);
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
