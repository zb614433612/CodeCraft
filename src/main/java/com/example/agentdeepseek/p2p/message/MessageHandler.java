package com.example.agentdeepseek.p2p.message;

import com.example.agentdeepseek.p2p.protocol.MessageFrame;
import com.example.agentdeepseek.p2p.protocol.MessageType;
import io.netty.channel.ChannelHandlerContext;

/**
 * 消息处理器接口
 * <p>
 * 每种消息类型对应一个实现，由 MessageRouter 统一调度。
 * </p>
 */
public interface MessageHandler {

    /**
     * 返回此处理器支持的消息类型
     */
    MessageType getSupportedType();

    /**
     * 处理收到的消息
     *
     * @param ctx     Netty ChannelHandlerContext（可获取 Channel 用于回复等操作）
     * @param peerId  发送方的 Peer ID（可能为 "unknown" 表示尚未注册）
     * @param frame   消息帧
     */
    void handle(ChannelHandlerContext ctx, String peerId, MessageFrame frame);
}
