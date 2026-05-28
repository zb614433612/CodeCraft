package com.example.agentdeepseek.p2p.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * P2P 消息帧
 * <pre>
 * ┌──────┬────────┬──────────┬──────────┬──────────────┐
 * │ Magic│Version│  Type    │  Length  │   Payload     │
 * │ 2Byte│ 1Byte │  1Byte   │  4Byte   │   N Byte      │
 * │0xCCCC│ 0x01  │          │          │  (JSON/二进制) │
 * └──────┴────────┴──────────┴──────────┴──────────────┘
 * </pre>
 */
public class MessageFrame {

    public static final int HEADER_SIZE = 8;  // 2 + 1 + 1 + 4

    private final MessageType type;
    private final byte[] payload;

    public MessageFrame(MessageType type, byte[] payload) {
        this.type = type;
        this.payload = payload != null ? payload : new byte[0];
    }

    public MessageFrame(MessageType type, String jsonPayload) {
        this(type, jsonPayload != null ? jsonPayload.getBytes(StandardCharsets.UTF_8) : new byte[0]);
    }

    public MessageType getType() {
        return type;
    }

    public byte[] getPayload() {
        return payload;
    }

    public String getPayloadAsString() {
        return new String(payload, StandardCharsets.UTF_8);
    }

    public int getPayloadLength() {
        return payload.length;
    }

    /**
     * 将消息帧编码为字节数组（用于网络发送）
     */
    public byte[] encode() {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + payload.length);
        buffer.putShort(P2pConstants.MAGIC);
        buffer.put(P2pConstants.PROTOCOL_VERSION);
        buffer.put(type.getCode());
        buffer.putInt(payload.length);
        buffer.put(payload);
        return buffer.array();
    }

    /**
     * 从字节数组解码为消息帧（用于网络接收）
     * 调用方需确保 buffer 至少包含 HEADER_SIZE 字节
     */
    public static MessageFrame decode(ByteBuffer buffer) {
        if (buffer.remaining() < HEADER_SIZE) {
            throw new IllegalArgumentException("Buffer too small for header: " + buffer.remaining());
        }

        short magic = buffer.getShort();
        if (magic != P2pConstants.MAGIC) {
            throw new IllegalArgumentException("Invalid magic number: 0x" + Integer.toHexString(magic & 0xFFFF));
        }

        byte version = buffer.get();
        if (version != P2pConstants.PROTOCOL_VERSION) {
            throw new IllegalArgumentException("Unsupported protocol version: " + version);
        }

        byte typeCode = buffer.get();
        MessageType type = MessageType.fromCode(typeCode);

        int length = buffer.getInt();
        if (length < 0 || length > P2pConstants.MAX_FRAME_LENGTH) {
            throw new IllegalArgumentException("Invalid payload length: " + length);
        }

        byte[] payload = new byte[length];
        if (length > 0) {
            buffer.get(payload);
        }

        return new MessageFrame(type, payload);
    }

    @Override
    public String toString() {
        return "MessageFrame{type=" + type + ", payloadSize=" + payload.length + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MessageFrame that)) return false;
        return type == that.type && Arrays.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        int result = type.hashCode();
        result = 31 * result + Arrays.hashCode(payload);
        return result;
    }
}
