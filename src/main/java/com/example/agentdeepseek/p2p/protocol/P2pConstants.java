package com.example.agentdeepseek.p2p.protocol;

/**
 * P2P 协议常量定义
 */
public final class P2pConstants {

    private P2pConstants() {
        // 工具类禁止实例化
    }

    // ==================== 协议头 ====================

    /** 魔数：0xCCCC（CodeCraft 前缀） */
    public static final short MAGIC = (short) 0xCCCC;

    /** 协议版本 */
    public static final byte PROTOCOL_VERSION = 0x01;

    /** 最大帧长度：10MB（防止内存溢出） */
    public static final int MAX_FRAME_LENGTH = 10 * 1024 * 1024;

    // ==================== 默认端口 ====================

    /** 默认 P2P 监听端口 */
    public static final int DEFAULT_PORT = 9527;

    // ==================== 心跳 ====================

    /** 心跳间隔：15 秒 */
    public static final int HEARTBEAT_INTERVAL_SECONDS = 15;

    /** 心跳超时：60 秒（超时判定断开） */
    public static final int HEARTBEAT_TIMEOUT_SECONDS = 60;

    // ==================== 重连 ====================

    /** 重连基础延迟：1 秒 */
    public static final int RECONNECT_BASE_DELAY_SECONDS = 1;

    /** 重连最大延迟：60 秒 */
    public static final int RECONNECT_MAX_DELAY_SECONDS = 60;

    // ==================== 信令 ====================

    /** 连接串协议前缀 */
    public static final String CONNECTION_STRING_PREFIX = "p2p://";

    /** 二维码图片宽/高（像素） */
    public static final int QR_CODE_SIZE = 300;

    // ==================== 安全 ====================

    /** 证书存储目录（相对于项目根目录） */
    public static final String CERT_DIR = "data/p2p/certs";

    /** 自签名证书有效期（天） */
    public static final int CERT_VALIDITY_DAYS = 365;

    /** AES 密钥长度（256 位） */
    public static final int AES_KEY_SIZE = 256;

    /** AES GCM 认证标签长度（128 位） */
    public static final int GCM_TAG_LENGTH = 128;

    /** AES GCM IV 长度（96 位 / 12 字节，推荐值） */
    public static final int GCM_IV_LENGTH = 12;

    // ==================== 文件传输 ====================

    /** 接收文件存储目录（相对于项目根目录，与 snapshots/、data/ 同级） */
    public static final String RECEIVED_DIR = "data/p2p/received";

    /** 文件分块大小：1MB */
    public static final int FILE_CHUNK_SIZE = 1024 * 1024;

    /** 图片最大大小（走 image 通道）：20MB */
    public static final long MAX_IMAGE_SIZE = 20L * 1024 * 1024;

    /** 文件最大大小：2GB */
    public static final long MAX_FILE_SIZE = 2L * 1024 * 1024 * 1024;

    /** CHUNK 帧最小头部：transferIdLen(1) + transferId(36) + chunkIndex(4) = 41 */
    public static final int CHUNK_HEADER_SIZE = 41;

    /** 传输超时（秒）：传输中超过此时间未收到新块则标记失败 */
    public static final int TRANSFER_TIMEOUT_SECONDS = 120;

    /** 缩略图最大宽度（像素） */
    public static final int THUMBNAIL_MAX_WIDTH = 400;

    // ==================== Peer 信息 ====================

    /** Peer ID 长度（SHA-256 十六进制字符串） */
    public static final int PEER_ID_LENGTH = 64;

    /** 在线节点缓存过期时间（分钟） */
    public static final int PEER_CACHE_EXPIRE_MINUTES = 5;
}
