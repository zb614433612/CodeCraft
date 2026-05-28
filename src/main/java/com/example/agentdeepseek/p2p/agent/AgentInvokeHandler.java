package com.example.agentdeepseek.p2p.agent;

import com.example.agentdeepseek.p2p.message.MessageHandler;
import com.example.agentdeepseek.p2p.protocol.MessageFrame;
import com.example.agentdeepseek.p2p.protocol.MessageType;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * Agent 调用消息处理器
 * 处理对方发来的 AGENT_INVOKE 消息，执行 AI 并返回结果
 * <p>
 * 注意：AI 执行是阻塞的（up to 180s），但 Netty 的 channelRead 在 NioEventLoop 中执行。
 * 为了不阻塞 Netty IO 线程，这里将 AI 执行提交到独立线程池。
 * </p>
 */
public class AgentInvokeHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentInvokeHandler.class);

    private final P2pAgentService agentService;
    private final java.util.concurrent.ExecutorService executor;

    public AgentInvokeHandler(P2pAgentService agentService) {
        this.agentService = agentService;
        this.executor = java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "p2p-agent-invoke-");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public MessageType getSupportedType() {
        return MessageType.AGENT_INVOKE;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, String peerId, MessageFrame frame) {
        String payload = frame.getPayloadAsString();
        log.info("[P2P-Agent] Received AGENT_INVOKE from {}: {}", peerId, payload);

        // 解析参数
        String requestId = P2pAgentService.parseStringField(payload, "requestId");
        Long agentConfigId = P2pAgentService.parseLongField(payload, "agentConfigId");
        String agentName = P2pAgentService.parseStringField(payload, "agentName");
        String message = P2pAgentService.parseStringField(payload, "message");
        Long fromUserId = P2pAgentService.parseLongField(payload, "userId");

        if (requestId == null || agentConfigId == null || message == null) {
            log.warn("[P2P-Agent] Invalid AGENT_INVOKE from {}: missing required fields", peerId);
            return;
        }

        // 异步执行 AI（避免阻塞 Netty IO 线程）
        CompletableFuture.runAsync(() -> {
            try {
                agentService.handleIncomingInvoke(peerId, requestId, agentConfigId, agentName, message, fromUserId);
            } catch (Exception e) {
                log.error("[P2P-Agent] Error handling AGENT_INVOKE from {}", peerId, e);
            }
        }, executor);
    }
}
