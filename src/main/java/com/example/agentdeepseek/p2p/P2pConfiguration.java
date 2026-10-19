package com.example.agentdeepseek.p2p;

import com.example.agentdeepseek.p2p.address.Ipv6AddressCollector;
import com.example.agentdeepseek.p2p.config.P2pConfig;
import com.example.agentdeepseek.p2p.connection.P2pConnectionManager;
import com.example.agentdeepseek.p2p.message.ChatMessageHandler;
import com.example.agentdeepseek.p2p.message.FileTransferHandler;
import com.example.agentdeepseek.mapper.P2pKnownPeerMapper;
import com.example.agentdeepseek.p2p.message.DisconnectNotifyHandler;
import com.example.agentdeepseek.p2p.message.HandshakeHandler;
import com.example.agentdeepseek.p2p.service.P2pChatService;
import com.example.agentdeepseek.p2p.service.FileTransferManager;
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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import java.util.concurrent.CompletableFuture;

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
                                                      P2pAgentService agentService,
                                                      FileTransferManager transferManager) {
        connectionManager = new P2pConnectionManager(config, addressCollector, tlsHelper);

        // 注册默认消息处理器
        connectionManager.getMessageRouter().registerHandler(new HandshakeHandler(connectionManager, knownPeerMapper));
        connectionManager.getMessageRouter().registerHandler(new DisconnectNotifyHandler(connectionManager));
        connectionManager.getMessageRouter().registerHandler(new ChatMessageHandler(connectionManager, chatService));

        // 注册文件传输处理器
        FileTransferHandler fileTransferHandler = new FileTransferHandler(transferManager, chatService, connectionManager);
        fileTransferHandler.setMyName(connectionManager.getMyName());
        connectionManager.getMessageRouter().registerHandler(fileTransferHandler);

        // 注册 Agent 相关消息处理器
        connectionManager.getMessageRouter().registerHandler(new AgentAuthGrantHandler(agentService));
        connectionManager.getMessageRouter().registerHandler(new AgentAuthCancelHandler(agentService));
        connectionManager.getMessageRouter().registerHandler(new AgentInvokeHandler(agentService));
        connectionManager.getMessageRouter().registerHandler(new AgentResponseHandler(agentService));

        // ⚠️ 不要在这里调用 connectionManager.start()！
        // 曾经的写法让 TLS 初始化（实测 3.3s）+ Netty 绑定端口（5.3s）全部计入 Spring 容器刷新，
        // 主界面必须等 P2P 起完才能打开。P2P 是可选协作功能，改为应用就绪后异步启动，见 startP2pAsync()。
        return connectionManager;
    }

    /**
     * 应用就绪后异步启动 P2P。
     * <p>
     * 把 {@code P2pConnectionManager.start()} 从 {@code @Bean} 工厂方法挪到这里，是为了把
     * 「TLS 上下文初始化 + Netty Server 绑定端口」从启动关键路径上摘掉：
     * 慢机器实测这两步合计约 8.6 秒，而绝大多数用户从不使用 P2P 协作。
     * </p>
     * <p>
     * 异步化的可见影响：应用刚启动的几秒内 {@code GET /api/p2p/status} 会返回
     * {@code running=false}，前端轮询/刷新后自动转为可用；P2P 启动失败也只记日志，
     * 不会拖垮主功能（与改造前 try-catch 的容错语义一致）。
     * </p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void startP2pAsync() {
        final P2pConnectionManager manager = this.connectionManager;
        if (manager == null || manager.isRunning()) {
            return;
        }
        // 用 commonPool：无需自建线程池，且线程为 daemon，JVM 退出时不会阻塞
        CompletableFuture.runAsync(() -> {
            try {
                manager.start();
            } catch (Exception e) {
                log.error("[P2P] 异步启动失败（不影响主功能）: {}", e.toString(), e);
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        if (connectionManager != null && connectionManager.isRunning()) {
            connectionManager.stop();
        }
    }
}
