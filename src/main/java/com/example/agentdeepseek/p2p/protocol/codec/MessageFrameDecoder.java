package com.example.agentdeepseek.p2p.protocol.codec;

import com.example.agentdeepseek.p2p.protocol.MessageFrame;
import com.example.agentdeepseek.p2p.protocol.MessageType;
import com.example.agentdeepseek.p2p.protocol.P2pConstants;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * MessageFrame 入站解码器（含粘包/拆包处理）
 * ByteBuf → MessageFrame
 */
public class MessageFrameDecoder extends ByteToMessageDecoder {

    private static final Logger log = LoggerFactory.getLogger(MessageFrameDecoder.class);

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        // 等待完整的头部到达
        if (in.readableBytes() < MessageFrame.HEADER_SIZE) {
            return;
        }

        // 标记当前读位置，用于回退
        in.markReaderIndex();

        // 读取并验证魔数
        short magic = in.readShort();
        if (magic != P2pConstants.MAGIC) {
            log.warn("[P2P] Invalid magic number: 0x{}, closing connection", Integer.toHexString(magic & 0xFFFF));
            ctx.close();
            return;
        }

        // 读取并验证版本
        byte version = in.readByte();
        if (version != P2pConstants.PROTOCOL_VERSION) {
            log.warn("[P2P] Unsupported protocol version: {}, closing connection", version);
            ctx.close();
            return;
        }

        // 读取消息类型
        byte typeCode = in.readByte();
        MessageType type;
        try {
            type = MessageType.fromCode(typeCode);
        } catch (IllegalArgumentException e) {
            log.warn("[P2P] Unknown message type: 0x{}, closing connection", Integer.toHexString(typeCode & 0xFF));
            ctx.close();
            return;
        }

        // 读取 payload 长度
        int length = in.readInt();
        if (length < 0 || length > P2pConstants.MAX_FRAME_LENGTH) {
            log.warn("[P2P] Invalid payload length: {}, closing connection", length);
            ctx.close();
            return;
        }

        // 等待完整 payload 到达
        if (in.readableBytes() < length) {
            in.resetReaderIndex(); // 回退，等待更多数据
            return;
        }

        // 读取 payload
        byte[] payload = new byte[length];
        if (length > 0) {
            in.readBytes(payload);
        }

        // 构建 MessageFrame 并传递给下一个 Handler
        MessageFrame frame = new MessageFrame(type, payload);
        out.add(frame);

        if (log.isTraceEnabled()) {
            log.trace("[P2P] Decoded frame: type={}, payloadSize={}", type, length);
        }
    }
}
