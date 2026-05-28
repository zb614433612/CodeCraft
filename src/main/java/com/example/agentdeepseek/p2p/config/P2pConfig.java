package com.example.agentdeepseek.p2p.config;

import com.example.agentdeepseek.p2p.protocol.P2pConstants;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * P2P 配置类（绑定 application.yml 中的 p2p 配置段）
 */
@Component
@ConfigurationProperties(prefix = "p2p")
public class P2pConfig {

    /** 是否启用 P2P 功能 */
    private boolean enabled = true;

    /** P2P 监听端口 */
    private int port = P2pConstants.DEFAULT_PORT;

    /** 心跳间隔（秒） */
    private int heartbeatInterval = P2pConstants.HEARTBEAT_INTERVAL_SECONDS;

    /** 心跳超时（秒） */
    private int heartbeatTimeout = P2pConstants.HEARTBEAT_TIMEOUT_SECONDS;

    /** 重连基础延迟（秒） */
    private int reconnectBaseDelay = P2pConstants.RECONNECT_BASE_DELAY_SECONDS;

    /** 重连最大延迟（秒） */
    private int reconnectMaxDelay = P2pConstants.RECONNECT_MAX_DELAY_SECONDS;

    /** 最大帧长度（字节） */
    private int maxFrameLength = P2pConstants.MAX_FRAME_LENGTH;

    /** 自签名证书有效期（天） */
    private int certValidityDays = P2pConstants.CERT_VALIDITY_DAYS;

    /** 证书存储目录 */
    private String certDir = P2pConstants.CERT_DIR;

    /** 在线节点缓存过期时间（分钟） */
    private int peerCacheExpireMinutes = P2pConstants.PEER_CACHE_EXPIRE_MINUTES;

    // ==================== Getters & Setters ====================

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(int heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

    public int getHeartbeatTimeout() {
        return heartbeatTimeout;
    }

    public void setHeartbeatTimeout(int heartbeatTimeout) {
        this.heartbeatTimeout = heartbeatTimeout;
    }

    public int getReconnectBaseDelay() {
        return reconnectBaseDelay;
    }

    public void setReconnectBaseDelay(int reconnectBaseDelay) {
        this.reconnectBaseDelay = reconnectBaseDelay;
    }

    public int getReconnectMaxDelay() {
        return reconnectMaxDelay;
    }

    public void setReconnectMaxDelay(int reconnectMaxDelay) {
        this.reconnectMaxDelay = reconnectMaxDelay;
    }

    public int getMaxFrameLength() {
        return maxFrameLength;
    }

    public void setMaxFrameLength(int maxFrameLength) {
        this.maxFrameLength = maxFrameLength;
    }

    public int getCertValidityDays() {
        return certValidityDays;
    }

    public void setCertValidityDays(int certValidityDays) {
        this.certValidityDays = certValidityDays;
    }

    public String getCertDir() {
        return certDir;
    }

    public void setCertDir(String certDir) {
        this.certDir = certDir;
    }

    public int getPeerCacheExpireMinutes() {
        return peerCacheExpireMinutes;
    }

    public void setPeerCacheExpireMinutes(int peerCacheExpireMinutes) {
        this.peerCacheExpireMinutes = peerCacheExpireMinutes;
    }
}
