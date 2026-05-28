package com.example.agentdeepseek.mapper;

import com.example.agentdeepseek.model.entity.P2pChatMessage;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.List;

@Mapper
@Repository
public interface P2pChatMessageMapper {

    /** 动态建表（含新字段：message_type, agent_config_id, agent_name） */
    @Update("CREATE TABLE IF NOT EXISTS p2p_chat_message (" +
            "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
            "peer_id VARCHAR(64) NOT NULL, " +
            "sender_name VARCHAR(100) DEFAULT '', " +
            "content TEXT NOT NULL, " +
            "direction VARCHAR(10) NOT NULL, " +
            "message_type VARCHAR(20) DEFAULT 'chat', " +
            "agent_config_id BIGINT DEFAULT NULL, " +
            "agent_name VARCHAR(100) DEFAULT NULL, " +
            "created_at DATETIME NOT NULL)")
    void createTableIfNotExists();

    /** 动态建索引 */
    @Update("CREATE INDEX IF NOT EXISTS idx_p2p_msg_peer ON p2p_chat_message(peer_id)")
    void createIndexPeer();

    @Update("CREATE INDEX IF NOT EXISTS idx_p2p_msg_time ON p2p_chat_message(created_at)")
    void createIndexTime();

    @Insert("INSERT INTO p2p_chat_message (peer_id, sender_name, content, direction, message_type, agent_config_id, agent_name, created_at) " +
            "VALUES (#{peerId}, #{senderName}, #{content}, #{direction}, #{messageType}, #{agentConfigId}, #{agentName}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(P2pChatMessage message);

    @Select("SELECT * FROM p2p_chat_message WHERE peer_id = #{peerId} ORDER BY created_at ASC LIMIT #{limit} OFFSET #{offset}")
    @Results(id = "p2pMsgResult", value = {
        @Result(property = "id", column = "id"),
        @Result(property = "peerId", column = "peer_id"),
        @Result(property = "senderName", column = "sender_name"),
        @Result(property = "content", column = "content"),
        @Result(property = "direction", column = "direction"),
        @Result(property = "messageType", column = "message_type"),
        @Result(property = "agentConfigId", column = "agent_config_id"),
        @Result(property = "agentName", column = "agent_name"),
        @Result(property = "createdAt", column = "created_at")
    })
    List<P2pChatMessage> findByPeerId(@Param("peerId") String peerId, @Param("offset") int offset, @Param("limit") int limit);

    @Delete("DELETE FROM p2p_chat_message WHERE peer_id = #{peerId}")
    int deleteByPeerId(@Param("peerId") String peerId);
}
