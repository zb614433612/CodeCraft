package com.example.agentdeepseek.p2p.signaling;

/**
 * 信令交换数据
 * <p>
 * 包含对端连接所需的所有信息，通过二维码或连接串传递。
 * </p>
 */
public class SignalingData {

    /** 对端 Peer ID */
    private final String peerId;

    /** IPv6 地址（含端口），格式 [2001:db8::1]:9527 */
    private final String address;

    /** 证书指纹（TOFU 校验） */
    private final String certFingerprint;

    /** AES 密钥（Base64，可选） */
    private final String aesKey;

    /** 自定义名称 */
    private final String name;

    public SignalingData(String peerId, String address, String certFingerprint, String aesKey, String name) {
        this.peerId = peerId;
        this.address = address;
        this.certFingerprint = certFingerprint;
        this.aesKey = aesKey;
        this.name = name;
    }

    public SignalingData(String peerId, String address, String certFingerprint) {
        this(peerId, address, certFingerprint, null, null);
    }

    public SignalingData(String peerId, String address, String certFingerprint, String name) {
        this(peerId, address, certFingerprint, null, name);
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

    public String getAesKey() {
        return aesKey;
    }

    public String getName() {
        return name;
    }
}
