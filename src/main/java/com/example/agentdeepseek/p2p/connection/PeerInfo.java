package com.example.agentdeepseek.p2p.connection;

/**
 * 对端节点信息
 */
public class PeerInfo {

    /** 对端 Peer ID（SHA-256 十六进制，64字符） */
    private final String peerId;

    /** IPv6 地址（含端口），格式：[2001:db8::1]:9527 */
    private final String address;

    /** 证书指纹（TOFU 校验） */
    private final String certFingerprint;

    /** 对端自定义名称（如"张兵的电脑"） */
    private final String name;

    /** 连接建立时间 */
    private final long connectedAt;

    /** 最后一次心跳时间 */
    private volatile long lastHeartbeat;

    public PeerInfo(String peerId, String address, String certFingerprint, String name) {
        this.peerId = peerId;
        this.address = address;
        this.certFingerprint = certFingerprint;
        this.name = name != null ? name : "";
        this.connectedAt = System.currentTimeMillis();
        this.lastHeartbeat = this.connectedAt;
    }

    public void updateHeartbeat() {
        this.lastHeartbeat = System.currentTimeMillis();
    }

    // ==================== Getters ====================

    public String getPeerId() {
        return peerId;
    }

    public String getAddress() {
        return address;
    }

    public String getCertFingerprint() {
        return certFingerprint;
    }

    public String getName() {
        return name;
    }

    public long getConnectedAt() {
        return connectedAt;
    }

    public long getLastHeartbeat() {
        return lastHeartbeat;
    }

    @Override
    public String toString() {
        return "PeerInfo{peerId=" + peerId.substring(0, Math.min(8, peerId.length())) + "..., address=" + address + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PeerInfo that)) return false;
        return peerId.equals(that.peerId);
    }

    @Override
    public int hashCode() {
        return peerId.hashCode();
    }
}
