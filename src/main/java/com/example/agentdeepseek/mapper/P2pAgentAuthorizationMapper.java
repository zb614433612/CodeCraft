package com.example.agentdeepseek.mapper;

import com.example.agentdeepseek.model.entity.P2pAgentAuthorization;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * P2P Agent 授权记录 Mapper
 */
@Mapper
@Repository
public interface P2pAgentAuthorizationMapper {

    /**
     * 动态建表。
     * <p>
     * ⚠️ agent_avatar 必须是 VARCHAR(255)：Agent 头像既可能是 emoji（几个字符），
     * 也可能是一张图片的 URL（如 /api/avatar/file/xxx.jpg，实测 53 字符）。
     * 历史上这里是 VARCHAR(20)，导致「头像用图片」的 Agent 授权时
     * MERGE 写入报 Value too long → 接口 500。对齐 agent_config.avatar / sys_user.avatar 的 255。
     * </p>
     */
    @Update("CREATE TABLE IF NOT EXISTS p2p_agent_authorization (" +
            "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
            "peer_id VARCHAR(64) NOT NULL, " +
            "agent_config_id BIGINT NOT NULL, " +
            "agent_name VARCHAR(100) DEFAULT '', " +
            "agent_description VARCHAR(500) DEFAULT '', " +
            "agent_avatar VARCHAR(255) DEFAULT '🤖', " +
            "user_id BIGINT DEFAULT NULL, " +
            "direction VARCHAR(10) NOT NULL, " +
            "status VARCHAR(10) NOT NULL DEFAULT 'active', " +
            "created_at DATETIME NOT NULL, " +
            "cancelled_at DATETIME)")
    void createTableIfNotExists();

    /**
     * 老库列宽扩容：agent_avatar VARCHAR(20) → VARCHAR(255)。
     * <p>
     * CREATE TABLE IF NOT EXISTS 对已存在的表不生效，所以老用户升级后仍是窄列，
     * 授权接口会继续 500。这里用幂等 ALTER 兜底：H2/MySQL 下重复执行
     * 「改成同样的宽度」不会报错，可安全地在每次启动时调用。
     * </p>
     */
    @Update("ALTER TABLE p2p_agent_authorization ALTER COLUMN agent_avatar VARCHAR(255)")
    void widenAgentAvatar();

    /** 建索引 */
    @Update("CREATE INDEX IF NOT EXISTS idx_paa_peer_dir ON p2p_agent_authorization(peer_id, direction)")
    void createIndexPeerDir();

    @Update("CREATE INDEX IF NOT EXISTS idx_paa_peer_agent ON p2p_agent_authorization(peer_id, agent_config_id)")
    void createIndexPeerAgent();

    @Update("CREATE UNIQUE INDEX IF NOT EXISTS uk_paa_peer_agent_dir ON p2p_agent_authorization(peer_id, agent_config_id, direction)")
    void createUniqueIndex();

    /** 插入或激活授权（MERGE 语义：存在则更新为 active，否则插入） */
    @Insert("MERGE INTO p2p_agent_authorization (peer_id, agent_config_id, agent_name, agent_description, agent_avatar, user_id, direction, status, created_at, cancelled_at) " +
            "KEY(peer_id, agent_config_id, direction) " +
            "VALUES (#{peerId}, #{agentConfigId}, #{agentName}, #{agentDescription}, #{agentAvatar}, #{userId}, #{direction}, #{status}, #{createdAt}, #{cancelledAt})")
    int upsert(P2pAgentAuthorization auth);

    /** 取消授权（更新状态为 cancelled） */
    @Update("UPDATE p2p_agent_authorization SET status = 'cancelled', cancelled_at = NOW() " +
            "WHERE peer_id = #{peerId} AND agent_config_id = #{agentConfigId} AND direction = #{direction}")
    int cancel(@Param("peerId") String peerId, @Param("agentConfigId") Long agentConfigId,
               @Param("direction") String direction);

    /** 查询指定方向的授权列表（仅 active） */
    @Select("SELECT * FROM p2p_agent_authorization WHERE peer_id = #{peerId} AND direction = #{direction} AND status = 'active'")
    @Results(id = "authResult", value = {
        @Result(property = "id", column = "id"),
        @Result(property = "peerId", column = "peer_id"),
        @Result(property = "agentConfigId", column = "agent_config_id"),
        @Result(property = "agentName", column = "agent_name"),
        @Result(property = "agentDescription", column = "agent_description"),
        @Result(property = "agentAvatar", column = "agent_avatar"),
        @Result(property = "userId", column = "user_id"),
        @Result(property = "direction", column = "direction"),
        @Result(property = "status", column = "status"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "cancelledAt", column = "cancelled_at")
    })
    List<P2pAgentAuthorization> findActiveByPeerAndDirection(@Param("peerId") String peerId,
                                                              @Param("direction") String direction);

    /** 查询所有 active 授权 */
    @Select("SELECT * FROM p2p_agent_authorization WHERE status = 'active'")
    @ResultMap("authResult")
    List<P2pAgentAuthorization> findAllActive();

    /** 检查特定授权是否 active */
    @Select("SELECT * FROM p2p_agent_authorization WHERE peer_id = #{peerId} AND agent_config_id = #{agentConfigId} " +
            "AND direction = #{direction} AND status = 'active'")
    @ResultMap("authResult")
    P2pAgentAuthorization findActive(@Param("peerId") String peerId, @Param("agentConfigId") Long agentConfigId,
                                      @Param("direction") String direction);
}
