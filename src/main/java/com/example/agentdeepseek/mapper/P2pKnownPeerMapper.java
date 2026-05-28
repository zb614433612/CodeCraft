package com.example.agentdeepseek.mapper;

import com.example.agentdeepseek.model.entity.P2pKnownPeer;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.List;

@Mapper
@Repository
public interface P2pKnownPeerMapper {

    /** 动态建表 */
    @Update("CREATE TABLE IF NOT EXISTS p2p_known_peer (" +
            "peer_id VARCHAR(64) PRIMARY KEY, " +
            "name VARCHAR(100) DEFAULT '', " +
            "remark VARCHAR(100) DEFAULT '', " +
            "address VARCHAR(200) DEFAULT '', " +
            "cert_fingerprint VARCHAR(255) DEFAULT '', " +
            "first_connected_at DATETIME, " +
            "last_connected_at DATETIME, " +
            "connect_count INT DEFAULT 0)")
    void createTableIfNotExists();

    /** 插入或更新（握手时调用） */
    @Insert("MERGE INTO p2p_known_peer (peer_id, name, address, cert_fingerprint, first_connected_at, last_connected_at, connect_count) " +
            "KEY(peer_id) VALUES (#{peerId}, #{name}, #{address}, #{certFingerprint}, NOW(), NOW(), 1)")
    int upsertOnHandshake(P2pKnownPeer peer);

    /** 仅更新最后连接时间和次数（重复连接时） */
    @Update("UPDATE p2p_known_peer SET last_connected_at = NOW(), connect_count = connect_count + 1 WHERE peer_id = #{peerId}")
    int updateConnectTime(@Param("peerId") String peerId);

    /** 更新名称（握手带过来的AI配置名称） */
    @Update("UPDATE p2p_known_peer SET name = #{name} WHERE peer_id = #{peerId}")
    int updateName(@Param("peerId") String peerId, @Param("name") String name);

    /** 设置/更新备注 */
    @Update("UPDATE p2p_known_peer SET remark = #{remark} WHERE peer_id = #{peerId}")
    int updateRemark(@Param("peerId") String peerId, @Param("remark") String remark);

    /** 查询单个节点 */
    @Select("SELECT * FROM p2p_known_peer WHERE peer_id = #{peerId}")
    @Results(id = "knownPeerResult", value = {
        @Result(property = "peerId", column = "peer_id"),
        @Result(property = "name", column = "name"),
        @Result(property = "remark", column = "remark"),
        @Result(property = "address", column = "address"),
        @Result(property = "certFingerprint", column = "cert_fingerprint"),
        @Result(property = "firstConnectedAt", column = "first_connected_at"),
        @Result(property = "lastConnectedAt", column = "last_connected_at"),
        @Result(property = "connectCount", column = "connect_count")
    })
    P2pKnownPeer findByPeerId(@Param("peerId") String peerId);

    /** 查询所有已知节点 */
    @Select("SELECT * FROM p2p_known_peer ORDER BY last_connected_at DESC")
    @ResultMap("knownPeerResult")
    List<P2pKnownPeer> findAll();
}
