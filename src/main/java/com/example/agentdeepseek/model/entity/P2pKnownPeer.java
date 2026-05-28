package com.example.agentdeepseek.model.entity;

import java.time.LocalDateTime;

/**
 * P2P 已知节点实体（机器特征记忆）
 */
public class P2pKnownPeer {

    private String peerId;
    private String name;
    private String remark;
    private String address;
    private String certFingerprint;
    private LocalDateTime firstConnectedAt;
    private LocalDateTime lastConnectedAt;
    private int connectCount;

    public P2pKnownPeer() {}

    public String getPeerId() { return peerId; }
    public void setPeerId(String peerId) { this.peerId = peerId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getCertFingerprint() { return certFingerprint; }
    public void setCertFingerprint(String certFingerprint) { this.certFingerprint = certFingerprint; }
    public LocalDateTime getFirstConnectedAt() { return firstConnectedAt; }
    public void setFirstConnectedAt(LocalDateTime firstConnectedAt) { this.firstConnectedAt = firstConnectedAt; }
    public LocalDateTime getLastConnectedAt() { return lastConnectedAt; }
    public void setLastConnectedAt(LocalDateTime lastConnectedAt) { this.lastConnectedAt = lastConnectedAt; }
    public int getConnectCount() { return connectCount; }
    public void setConnectCount(int connectCount) { this.connectCount = connectCount; }
}
