package com.example.agentdeepseek.p2p.protocol;

/**
 * P2P 消息类型枚举
 */
public enum MessageType {

    /** 心跳消息 */
    HEARTBEAT((byte) 0x00),

    /** 握手消息（HELLO / HELLO_ACK） */
    HANDSHAKE((byte) 0x01),

    /** 文件传输 */
    FILE_TRANSFER((byte) 0x02),

    /** 即时聊天消息 */
    CHAT_MESSAGE((byte) 0x03),

    /** 代码片段同步 */
    CODE_SYNC((byte) 0x04),

    /** 远程命令执行 */
    REMOTE_SHELL((byte) 0x05),

    /** 快照分享 */
    SNAPSHOT_SHARE((byte) 0x06),

    /** 断开通知（主动断开前告知对方取消重连） */
    DISCONNECT_NOTIFY((byte) 0x07),

    /** Agent 授权 */
    AGENT_AUTH_GRANT((byte) 0x08),

    /** 取消 Agent 授权 */
    AGENT_AUTH_CANCEL((byte) 0x09),

    /** 调用 Agent */
    AGENT_INVOKE((byte) 0x0A),

    /** Agent 返回结果 */
    AGENT_RESPONSE((byte) 0x0B);

    private final byte code;

    MessageType(byte code) {
        this.code = code;
    }

    public byte getCode() {
        return code;
    }

    /**
     * 根据字节码查找对应的消息类型
     */
    public static MessageType fromCode(byte code) {
        for (MessageType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown message type code: 0x" + Integer.toHexString(code & 0xFF));
    }
}
