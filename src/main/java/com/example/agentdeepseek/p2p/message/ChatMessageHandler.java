package com.example.agentdeepseek.p2p.message;

import com.example.agentdeepseek.p2p.connection.P2pConnectionManager;
import com.example.agentdeepseek.p2p.protocol.MessageFrame;
import com.example.agentdeepseek.p2p.protocol.MessageType;
import com.example.agentdeepseek.p2p.service.P2pChatService;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 聊天消息处理器
 * <p>
 * 收到消息后存入 P2pConnectionManager 的消息队列，供前端轮询获取。
 * </p>
 */
public class ChatMessageHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageHandler.class);

    private final P2pConnectionManager connectionManager;
    private final P2pChatService chatService;

    public ChatMessageHandler(P2pConnectionManager connectionManager, P2pChatService chatService) {
        this.connectionManager = connectionManager;
        this.chatService = chatService;
    }

    @Override
    public MessageType getSupportedType() {
        return MessageType.CHAT_MESSAGE;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, String peerId, MessageFrame frame) {
        String message = frame.getPayloadAsString();
        log.info("[P2P] Chat message from {}: {}", peerId, message);
        connectionManager.storeMessage(peerId, frame);
        // 存入数据库
        String name = extractJsonValue(message, "name");
        String content = extractJsonValue(message, "content");
        chatService.saveMessage(peerId, name != null ? name : "未知", content != null ? content : message, "received");
    }

    private static String extractJsonValue(String json, String key) {
        if (json == null) return null;
        String searchKey = "\"" + key + "\":\"";
        int start = json.indexOf(searchKey);
        if (start < 0) return null;
        start += searchKey.length();
        int end = json.indexOf('"', start);
        if (end < 0) return null;
        return json.substring(start, end).replace("\\\"", "\"");
    }
}
