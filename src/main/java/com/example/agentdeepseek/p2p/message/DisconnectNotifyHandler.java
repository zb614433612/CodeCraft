package com.example.agentdeepseek.p2p.message;

import com.example.agentdeepseek.p2p.connection.P2pConnectionManager;
import com.example.agentdeepseek.p2p.protocol.MessageFrame;
import com.example.agentdeepseek.p2p.protocol.MessageType;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 断开通知处理器
 * <p>
 * 收到对端的 DISCONNECT_NOTIFY 后，取消本端的自动重连。
 * </p>
 */
public class DisconnectNotifyHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(DisconnectNotifyHandler.class);

    private final P2pConnectionManager connectionManager;

    public DisconnectNotifyHandler(P2pConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    public MessageType getSupportedType() {
        return MessageType.DISCONNECT_NOTIFY;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, String peerId, MessageFrame frame) {
        log.info("[P2P] Received disconnect notify from peer: {}", peerId);
        // 提前移除 peerInfo：后续 channelInactive 中找不到 → 不触发重连
        connectionManager.getConnectionPool().remove(peerId);
        connectionManager.cancelReconnect(peerId);
        connectionManager.clearMessages(peerId);
    }
}
