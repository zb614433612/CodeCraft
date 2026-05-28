package com.example.agentdeepseek.p2p.agent;

import com.example.agentdeepseek.p2p.message.MessageHandler;
import com.example.agentdeepseek.p2p.protocol.MessageFrame;
import com.example.agentdeepseek.p2p.protocol.MessageType;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent 响应消息处理器
 * 处理对方发来的 AGENT_RESPONSE 消息（AI 执行结果）
 */
public class AgentResponseHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentResponseHandler.class);

    private final P2pAgentService agentService;

    public AgentResponseHandler(P2pAgentService agentService) {
        this.agentService = agentService;
    }

    @Override
    public MessageType getSupportedType() {
        return MessageType.AGENT_RESPONSE;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, String peerId, MessageFrame frame) {
        String payload = frame.getPayloadAsString();
        log.info("[P2P-Agent] Received AGENT_RESPONSE from {}: {}", peerId, payload);

        try {
            String requestId = P2pAgentService.parseStringField(payload, "requestId");
            Long agentConfigId = P2pAgentService.parseLongField(payload, "agentConfigId");
            String agentName = P2pAgentService.parseStringField(payload, "agentName");
            String content = P2pAgentService.parseStringField(payload, "content");
            String status = P2pAgentService.parseStringField(payload, "status");

            agentService.handleIncomingResponse(peerId, requestId, agentConfigId, agentName, content, status);
        } catch (Exception e) {
            log.error("[P2P-Agent] Failed to handle AGENT_RESPONSE from {}", peerId, e);
        }
    }
}
