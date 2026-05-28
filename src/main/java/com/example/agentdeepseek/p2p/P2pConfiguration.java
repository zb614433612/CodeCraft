package com.example.agentdeepseek.p2p;

import com.example.agentdeepseek.p2p.address.Ipv6AddressCollector;
import com.example.agentdeepseek.p2p.config.P2pConfig;
import com.example.agentdeepseek.p2p.connection.P2pConnectionManager;
import com.example.agentdeepseek.p2p.message.ChatMessageHandler;
import com.example.agentdeepseek.mapper.P2pKnownPeerMapper;
import com.example.agentdeepseek.p2p.message.DisconnectNotifyHandler;
import com.example.agentdeepseek.p2p.message.HandshakeHandler;
import com.example.agentdeepseek.p2p.service.P2pChatService;
import com.example.agentdeepseek.p2p.security.TlsHelper;
import com.example.agentdeepseek.p2p.agent.P2pAgentService;
import com.example.agentdeepseek.p2p.agent.AgentAuthGrantHandler;
import com.example.agentdeepseek.p2p.agent.AgentAuthCancelHandler;
import com.example.agentdeepseek.p2p.agent.AgentInvokeHandler;
import com.example.agentdeepseek.p2p.agent.AgentResponseHandler;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * P2P 模块 Spring Bean 配置
 */
@Configuration
@ConditionalOnProperty(name = "p2p.enabled", havingValue = "true", matchIfMissing = true)
public class P2pConfiguration {

    private static final Logger log = LoggerFactory.getLogger(P2pConfiguration.class);

    private P2pConnectionManager connectionManager;

    @Bean
    public Ipv6AddressCollector ipv6AddressCollector(P2pConfig config) {
        return new Ipv6AddressCollector(config.getPort());
    }

    @Bean
    public TlsHelper tlsHelper() {
        return new TlsHelper();
    }

    @Bean
    public P2pConnectionManager p2pConnectionManager(P2pConfig config,
                                                      Ipv6AddressCollector addressCollector,
                                                      TlsHelper tlsHelper,
                                                      P2pChatService chatService,
                                                      P2pKnownPeerMapper knownPeerMapper,
                                                      P2pAgentService agentService) {
        connectionManager = new P2pConnectionManager(config, addressCollector, tlsHelper);

        // 注册默认消息处理器
        connectionManager.getMessageRouter().registerHandler(new HandshakeHandler(connectionManager, knownPeerMapper));
        connectionManager.getMessageRouter().registerHandler(new DisconnectNotifyHandler(connectionManager));
        connectionManager.getMessageRouter().registerHandler(new ChatMessageHandler(connectionManager, chatService));

        // 注册 Agent 相关消息处理器
        connectionManager.getMessageRouter().registerHandler(new AgentAuthGrantHandler(agentService));
        connectionManager.getMessageRouter().registerHandler(new AgentAuthCancelHandler(agentService));
        connectionManager.getMessageRouter().registerHandler(new AgentInvokeHandler(agentService));
        connectionManager.getMessageRouter().registerHandler(new AgentResponseHandler(agentService));

        // 启动 P2P 服务
        try {
            connectionManager.start();
        } catch (Exception e) {
            log.error("[P2P] Failed to start P2P connection manager", e);
        }

        return connectionManager;
    }

    @PreDestroy
    public void shutdown() {
        if (connectionManager != null && connectionManager.isRunning()) {
            connectionManager.stop();
        }
    }
}
