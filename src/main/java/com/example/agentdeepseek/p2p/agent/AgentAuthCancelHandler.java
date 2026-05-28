package com.example.agentdeepseek.p2p.agent;

import com.example.agentdeepseek.p2p.message.MessageHandler;
import com.example.agentdeepseek.p2p.protocol.MessageFrame;
import com.example.agentdeepseek.p2p.protocol.MessageType;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 取消 Agent 授权消息处理器
 * 处理对方发来的 AGENT_AUTH_CANCEL 消息
 */
public class AgentAuthCancelHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentAuthCancelHandler.class);

    private final P2pAgentService agentService;

    public AgentAuthCancelHandler(P2pAgentService agentService) {
        this.agentService = agentService;
    }

    @Override
    public MessageType getSupportedType() {
        return MessageType.AGENT_AUTH_CANCEL;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, String peerId, MessageFrame frame) {
        String payload = frame.getPayloadAsString();
        log.info("[P2P-Agent] Received AGENT_AUTH_CANCEL from {}: {}", peerId, payload);

        try {
            List<Long> agentIds = P2pAgentService.parseAgentIdsFromPayload(payload);
            if (!agentIds.isEmpty()) {
                agentService.handleIncomingCancel(peerId, agentIds);
            }
        } catch (Exception e) {
            log.error("[P2P-Agent] Failed to handle AGENT_AUTH_CANCEL from {}", peerId, e);
        }
    }
}
