package com.example.agentdeepseek.p2p.message;

import com.example.agentdeepseek.p2p.connection.ConnectionPool;
import com.example.agentdeepseek.p2p.protocol.MessageFrame;
import com.example.agentdeepseek.p2p.protocol.MessageType;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * 消息路由器
 * <p>
 * 根据消息类型将消息分发给对应的 MessageHandler。
 * 同时提供 Netty Handler 用于接入 pipeline。
 * </p>
 */
@ChannelHandler.Sharable
public class MessageRouter extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(MessageRouter.class);

    /** 消息类型 → Handler */
    private final Map<MessageType, MessageHandler> handlers = new ConcurrentHashMap<>();

    /** 连接池（用于从 Channel 查找 PeerId） */
    private final ConnectionPool connectionPool;

    /** 未注册类型的消息监听器（兜底） */
    private BiConsumer<String, MessageFrame> fallbackListener;

    public MessageRouter(ConnectionPool connectionPool) {
        this.connectionPool = connectionPool;
    }

    /**
     * 注册消息处理器
     */
    public void registerHandler(MessageHandler handler) {
        MessageType type = handler.getSupportedType();
        MessageHandler old = handlers.put(type, handler);
        if (old != null) {
            log.info("[P2P] Replaced handler for type: {}", type);
        } else {
            log.info("[P2P] Registered handler for type: {}", type);
        }
    }

    /**
     * 注销消息处理器
     */
    public void unregisterHandler(MessageType type) {
        handlers.remove(type);
    }

    /**
     * 设置兜底监听器（处理未注册类型的消息）
     */
    public void setFallbackListener(BiConsumer<String, MessageFrame> listener) {
        this.fallbackListener = listener;
    }

    // ==================== Netty Handler ====================

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof MessageFrame frame)) {
            ctx.fireChannelRead(msg);
            return;
        }

        // 心跳消息由 IdleStateHandler 处理，这里跳过
        if (frame.getType() == MessageType.HEARTBEAT) {
            return;
        }

        // 查找 PeerId
        String peerId = connectionPool.getPeerId(ctx.channel()).orElse("unknown");

        // 分发给对应 Handler
        MessageHandler handler = handlers.get(frame.getType());
        if (handler != null) {
            try {
                handler.handle(ctx, peerId, frame);
            } catch (Exception e) {
                log.error("[P2P] Handler error for type={}, peer={}", frame.getType(), peerId, e);
            }
        } else if (fallbackListener != null) {
            fallbackListener.accept(peerId, frame);
        } else {
            log.warn("[P2P] No handler for message type: {}, peer: {}", frame.getType(), peerId);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("[P2P] MessageRouter error: {}", ctx.channel().remoteAddress(), cause);
        ctx.close();
    }
}
