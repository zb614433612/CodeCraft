package com.example.agentdeepseek.p2p.agent;

import com.example.agentdeepseek.p2p.message.MessageHandler;
import com.example.agentdeepseek.p2p.protocol.MessageFrame;
import com.example.agentdeepseek.p2p.protocol.MessageType;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Agent 授权消息处理器
 * 处理对方发来的 AGENT_AUTH_GRANT 消息
 */
public class AgentAuthGrantHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentAuthGrantHandler.class);

    private final P2pAgentService agentService;

    public AgentAuthGrantHandler(P2pAgentService agentService) {
        this.agentService = agentService;
    }

    @Override
    public MessageType getSupportedType() {
        return MessageType.AGENT_AUTH_GRANT;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, String peerId, MessageFrame frame) {
        String payload = frame.getPayloadAsString();
        log.info("[P2P-Agent] Received AGENT_AUTH_GRANT from {}: {}", peerId, payload);

        try {
            List<Map<String, Object>> agents = P2pAgentService.parseAgentsFromPayload(payload);
            Long fromUserId = P2pAgentService.parseLongField(payload, "userId");
            if (!agents.isEmpty()) {
                agentService.handleIncomingGrant(peerId, agents, fromUserId);
            }
        } catch (Exception e) {
            log.error("[P2P-Agent] Failed to handle AGENT_AUTH_GRANT from {}", peerId, e);
        }
    }
}
