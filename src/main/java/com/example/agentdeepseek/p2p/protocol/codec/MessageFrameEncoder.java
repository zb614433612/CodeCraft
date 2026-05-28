package com.example.agentdeepseek.p2p.protocol.codec;

import com.example.agentdeepseek.p2p.protocol.MessageFrame;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * MessageFrame 出站编码器
 * MessageFrame → ByteBuf（网络发送）
 */
public class MessageFrameEncoder extends MessageToByteEncoder<MessageFrame> {

    @Override
    protected void encode(ChannelHandlerContext ctx, MessageFrame frame, ByteBuf out) {
        byte[] encoded = frame.encode();
        out.writeBytes(encoded);
    }
}
