package com.example.agentdeepseek.p2p.connection;

import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P2P 连接池
 * <p>
 * 管理所有对端连接（PeerId → Channel 映射 + PeerInfo 元数据）
 * </p>
 */
public class ConnectionPool {

    private static final Logger log = LoggerFactory.getLogger(ConnectionPool.class);

    /** PeerId → Channel */
    private final Map<String, Channel> channelMap = new ConcurrentHashMap<>();

    /** PeerId → PeerInfo */
    private final Map<String, PeerInfo> peerInfoMap = new ConcurrentHashMap<>();

    /** Channel → PeerId 反向索引（O(1) 查找） */
    private final Map<Channel, String> reverseMap = new ConcurrentHashMap<>();

    /**
     * 注册新连接
     */
    public void register(String peerId, Channel channel, PeerInfo peerInfo) {
        Channel old = channelMap.put(peerId, channel);
        if (old != null && old != channel) {
            reverseMap.remove(old);
            old.close();
            log.info("[P2P] Replaced old connection for peer: {}", peerInfo);
        }
        reverseMap.put(channel, peerId);
        peerInfoMap.put(peerId, peerInfo);
        log.debug("[P2P] Peer registered: {} (total: {})", peerInfo, channelMap.size());
    }

    /**
     * 移除连接
     */
    public void remove(String peerId) {
        Channel channel = channelMap.remove(peerId);
        if (channel != null) {
            reverseMap.remove(channel);
        }
        peerInfoMap.remove(peerId);
        log.debug("[P2P] Peer removed: {} (total: {})", peerId, channelMap.size());
    }

    /**
     * 根据 PeerId 获取 Channel
     */
    public Optional<Channel> getChannel(String peerId) {
        return Optional.ofNullable(channelMap.get(peerId));
    }

    /**
     * 根据 Channel 查找 PeerId（O(1) 反向查找）
     */
    public Optional<String> getPeerId(Channel channel) {
        return Optional.ofNullable(reverseMap.get(channel));
    }

    /**
     * 获取 PeerInfo
     */
    public Optional<PeerInfo> getPeerInfo(String peerId) {
        return Optional.ofNullable(peerInfoMap.get(peerId));
    }

    /**
     * 更新心跳时间
     */
    public void updateHeartbeat(String peerId) {
        PeerInfo info = peerInfoMap.get(peerId);
        if (info != null) {
            info.updateHeartbeat();
        }
    }

    /**
     * 获取所有在线 PeerInfo 列表
     */
    public List<PeerInfo> getAllPeers() {
        return new ArrayList<>(peerInfoMap.values());
    }

    /**
     * 获取在线节点数量
     */
    public int getPeerCount() {
        return channelMap.size();
    }

    /**
     * 检查是否有指定 Peer 的连接
     */
    public boolean hasPeer(String peerId) {
        return channelMap.containsKey(peerId);
    }

    /**
     * 获取所有活跃的 Channel
     */
    public Collection<Channel> getAllChannels() {
        return new ArrayList<>(channelMap.values());
    }

    /**
     * 关闭所有连接
     */
    public void closeAll() {
        log.info("[P2P] Closing all connections ({} peers)...", channelMap.size());
        for (Channel channel : channelMap.values()) {
            try {
                channel.close();
            } catch (Exception e) {
                log.warn("[P2P] Error closing channel", e);
            }
        }
        channelMap.clear();
        peerInfoMap.clear();
        reverseMap.clear();
    }
}
