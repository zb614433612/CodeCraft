package com.example.agentdeepseek.mapper;

import com.example.agentdeepseek.model.entity.P2pAgentConversation;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

/**
 * P2P Agent 会话映射 Mapper
 */
@Mapper
@Repository
public interface P2pAgentConversationMapper {

    /** 动态建表 */
    @Update("CREATE TABLE IF NOT EXISTS p2p_agent_conversation (" +
            "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
            "peer_id VARCHAR(64) NOT NULL, " +
            "agent_config_id BIGINT NOT NULL, " +
            "conversation_id BIGINT NOT NULL, " +
            "created_at DATETIME NOT NULL, " +
            "updated_at DATETIME NOT NULL)")
    void createTableIfNotExists();

    /** 建索引 */
    @Update("CREATE INDEX IF NOT EXISTS idx_pac_peer_agent ON p2p_agent_conversation(peer_id, agent_config_id)")
    void createIndexPeerAgent();

    @Update("CREATE INDEX IF NOT EXISTS idx_pac_conv ON p2p_agent_conversation(conversation_id)")
    void createIndexConv();

    @Update("CREATE UNIQUE INDEX IF NOT EXISTS uk_pac_peer_agent ON p2p_agent_conversation(peer_id, agent_config_id)")
    void createUniqueIndex();

    /** 插入新映射 */
    @Insert("INSERT INTO p2p_agent_conversation (peer_id, agent_config_id, conversation_id, created_at, updated_at) " +
            "VALUES (#{peerId}, #{agentConfigId}, #{conversationId}, #{createdAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(P2pAgentConversation mapping);

    /** 根据 peerId + agentConfigId 查找映射 */
    @Select("SELECT * FROM p2p_agent_conversation WHERE peer_id = #{peerId} AND agent_config_id = #{agentConfigId}")
    @Results(id = "convMappingResult", value = {
        @Result(property = "id", column = "id"),
        @Result(property = "peerId", column = "peer_id"),
        @Result(property = "agentConfigId", column = "agent_config_id"),
        @Result(property = "conversationId", column = "conversation_id"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    P2pAgentConversation findByPeerAndAgent(@Param("peerId") String peerId,
                                             @Param("agentConfigId") Long agentConfigId);

    /** 更新会话映射的 updatedAt */
    @Update("UPDATE p2p_agent_conversation SET updated_at = NOW() WHERE id = #{id}")
    int updateTime(@Param("id") Long id);

    /** 根据 peerId 查询所有映射 */
    @Select("SELECT * FROM p2p_agent_conversation WHERE peer_id = #{peerId}")
    @ResultMap("convMappingResult")
    java.util.List<P2pAgentConversation> findByPeerId(@Param("peerId") String peerId);
}
