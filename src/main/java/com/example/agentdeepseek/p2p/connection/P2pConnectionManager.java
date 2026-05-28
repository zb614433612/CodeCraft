package com.example.agentdeepseek.p2p.connection;

import com.example.agentdeepseek.p2p.address.Ipv6AddressCollector;
import com.example.agentdeepseek.p2p.config.P2pConfig;
import com.example.agentdeepseek.p2p.message.HandshakeHandler;
import com.example.agentdeepseek.p2p.message.MessageRouter;
import com.example.agentdeepseek.p2p.protocol.MessageFrame;
import com.example.agentdeepseek.p2p.protocol.MessageType;
import com.example.agentdeepseek.p2p.security.TlsHelper;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.ssl.SslContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * P2P 连接管理器
 * <p>
 * 管理 P2P Server/Client 生命周期、连接池、心跳保活、自动重连。
 * </p>
 */
public class P2pConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(P2pConnectionManager.class);

    private final P2pConfig config;
    private final Ipv6AddressCollector addressCollector;
    private final TlsHelper tlsHelper;
    private final ConnectionPool connectionPool;
    private final MessageRouter messageRouter;

    private P2pServer server;
    private P2pClient client;

    /** 本机自定义名称 */
    private volatile String myName = "";

    /** 消息队列：PeerId → 收到的消息列表（线程安全） */
    private final Map<String, ConcurrentLinkedDeque<MessageFrame>> messageQueues = new ConcurrentHashMap<>();

    /** 重连任务调度器 */
    private final ScheduledExecutorService reconnectScheduler = Executors.newScheduledThreadPool(1);

    /** 正在重连的 Peer → 重连次数 */
    private final Map<String, Integer> reconnectAttempts = new ConcurrentHashMap<>();

    /** 重连 Future */
    private final Map<String, ScheduledFuture<?>> reconnectFutures = new ConcurrentHashMap<>();

    /** 是否已启动 */
    private volatile boolean running = false;

    public P2pConnectionManager(P2pConfig config, Ipv6AddressCollector addressCollector, TlsHelper tlsHelper) {
        this.config = config;
        this.addressCollector = addressCollector;
        this.tlsHelper = tlsHelper;
        this.connectionPool = new ConnectionPool();
        this.messageRouter = new MessageRouter(connectionPool);
    }

    // ==================== 生命周期 ====================

    /**
     * 启动 P2P 服务
     */
    public void start() throws Exception {
        if (!config.isEnabled()) {
            log.info("[P2P] P2P is disabled, skipping start");
            return;
        }

        log.info("[P2P] Starting P2P connection manager...");

        // 启动 TLS
        tlsHelper.init();

        // 启动 Server（使用 Server 专用 SslContext）
        server = new P2pServer(config.getPort(), tlsHelper.getServerSslContext(),
                config.getHeartbeatInterval(), config.getHeartbeatTimeout(),
                connectionPool, messageRouter, this::onConnectionEvent);
        server.start();

        // 启动 Client（不预置 SslContext，每次连接时根据指纹动态创建）
        client = new P2pClient(config.getHeartbeatInterval(),
                config.getHeartbeatTimeout(), messageRouter, this::onConnectionEvent);

        running = true;
        log.info("[P2P] P2P connection manager started. PeerId={}, Port={}",
                addressCollector.getPeerId(), config.getPort());
    }

    /**
     * 停止 P2P 服务
     */
    public void stop() {
        running = false;

        // 优雅关闭重连调度器
        reconnectScheduler.shutdown();
        try {
            if (!reconnectScheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                reconnectScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            reconnectScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // 取消所有重连任务
        reconnectFutures.values().forEach(f -> f.cancel(false));

        // 关闭所有连接
        connectionPool.closeAll();

        // 停止 Server 和 Client
        if (server != null) server.stop();
        if (client != null) client.stop();

        log.info("[P2P] P2P connection manager stopped");
    }

    // ==================== 连接管理 ====================

    /**
     * 主动连接到对端
     *
     * @param peerId          对端 Peer ID
     * @param address         对端 IPv6 地址 + 端口
     * @param certFingerprint 对端证书指纹（用于 TOFU 校验）
     * @param peerName        对端名称（可选）
     */
    public CompletableFuture<Channel> connect(String peerId, String address, String certFingerprint, String peerName) {
        if (!running) {
            CompletableFuture<Channel> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalStateException("P2P not started"));
            return f;
        }

        log.info("[P2P] Connecting to peer: {} at {}", peerId, address);

        // 根据对端证书指纹创建 TOFU 校验 SslContext
        SslContext clientSslCtx;
        try {
            clientSslCtx = TlsHelper.createTrustByFingerprintContext(certFingerprint);
        } catch (Exception e) {
            CompletableFuture<Channel> f = new CompletableFuture<>();
            f.completeExceptionally(new RuntimeException("Failed to create TLS context for peer: " + peerId, e));
            return f;
        }

        return client.connect(address, clientSslCtx).thenApply(channel -> {
            PeerInfo peerInfo = new PeerInfo(peerId, address, certFingerprint, peerName);
            connectionPool.register(peerId, channel, peerInfo);
            log.info("[P2P] Successfully connected to peer: {}", peerInfo);

            // 发送握手帧（告知对端本机信息）
            String myAddress = addressCollector.getBestAddressString()
                    .orElse("[::1]:" + config.getPort());
            MessageFrame handshake = HandshakeHandler.createHandshakeFrame(
                    addressCollector.getPeerId(), myAddress, tlsHelper.getCertFingerprint(), myName);
            channel.writeAndFlush(handshake);

            // 重置重连计数
            reconnectAttempts.remove(peerId);
            return channel;
        });
    }

    /**
     * 断开与指定 Peer 的连接
     */
    public void disconnect(String peerId) {
        cancelReconnect(peerId);
        clearMessages(peerId);

        Channel channel = connectionPool.getChannel(peerId).orElse(null);
        connectionPool.remove(peerId);

        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(new MessageFrame(MessageType.DISCONNECT_NOTIFY, new byte[0]));
            channel.eventLoop().schedule((Runnable) channel::close, 200, TimeUnit.MILLISECONDS);
        } else if (channel != null) {
            channel.close();
        }
        log.info("[P2P] Disconnected peer: {}", peerId);
    }

    /**
     * 向指定 Peer 发送消息帧
     */
    public CompletableFuture<Void> send(String peerId, MessageFrame frame) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        connectionPool.getChannel(peerId).ifPresentOrElse(channel -> {
            if (channel.isActive()) {
                ChannelFuture cf = channel.writeAndFlush(frame);
                cf.addListener((ChannelFutureListener) f -> {
                    if (f.isSuccess()) {
                        future.complete(null);
                    } else {
                        future.completeExceptionally(f.cause());
                    }
                });
            } else {
                future.completeExceptionally(
                        new IllegalStateException("Channel not active for peer: " + peerId));
            }
        }, () -> {
            future.completeExceptionally(
                    new IllegalArgumentException("Peer not connected: " + peerId));
        });

        return future;
    }

    /**
     * 向所有在线 Peer 广播消息
     */
    public void broadcast(MessageFrame frame) {
        for (Channel channel : connectionPool.getAllChannels()) {
            if (channel.isActive()) {
                channel.writeAndFlush(frame);
            }
        }
    }

    // ==================== 重连机制 ====================

    /**
     * 开始重连
     */
    private void startReconnect(PeerInfo peerInfo) {
        String peerId = peerInfo.getPeerId();

        // 如果已有重连任务，先取消
        cancelReconnect(peerId);

        int attempt = reconnectAttempts.getOrDefault(peerId, 0);
        long delay = Math.min(
                config.getReconnectBaseDelay() * (1L << attempt),  // 指数退避：1, 2, 4, 8...
                config.getReconnectMaxDelay()
        );

        log.info("[P2P] Scheduling reconnect to {} in {}s (attempt {})",
                peerInfo.getPeerId(), delay, attempt + 1);

        ScheduledFuture<?> future = reconnectScheduler.schedule(() -> {
            try {
                connect(peerInfo.getPeerId(), peerInfo.getAddress(), peerInfo.getCertFingerprint(), peerInfo.getName())
                        .whenComplete((channel, error) -> {
                            if (error != null) {
                                reconnectAttempts.merge(peerId, 1, Integer::sum);
                            }
                        });
            } catch (Exception e) {
                log.error("[P2P] Reconnect attempt failed for {}", peerId, e);
                reconnectAttempts.merge(peerId, 1, Integer::sum);
            }
        }, delay, TimeUnit.SECONDS);

        reconnectFutures.put(peerId, future);
        reconnectAttempts.put(peerId, attempt + 1);
    }

    /**
     * 取消重连（可由 DisconnectNotifyHandler 调用）
     */
    public void cancelReconnect(String peerId) {
        ScheduledFuture<?> future = reconnectFutures.remove(peerId);
        if (future != null && !future.isDone()) {
            future.cancel(false);
        }
        reconnectAttempts.remove(peerId);
    }

    // ==================== 事件处理 ====================

    /**
     * 连接事件回调
     */
    private void onConnectionEvent(P2pServer.ConnectionEvent event) {
        Channel channel = event.getChannel();

        if (event.getType() == P2pServer.ConnectionEvent.Type.DISCONNECTED) {
            connectionPool.getPeerId(channel).ifPresent(peerId -> {
                PeerInfo peerInfo = connectionPool.getPeerInfo(peerId).orElse(null);
                // 延迟重连，给 DISCONNECT_NOTIFY 到达留时间（对方主动断开时不应重连）
                if (running && peerInfo != null) {
                    reconnectScheduler.schedule(() -> {
                        // 800ms 后检查：peerInfo 还在才重连（被 DISCONNECT_NOTIFY 清理了就跳过）
                        if (connectionPool.getPeerInfo(peerId).isPresent()) {
                            startReconnect(peerInfo);
                        }
                    }, 800, TimeUnit.MILLISECONDS);
                }
                // remove 放最后，不影响上面 peerInfo 的获取
                connectionPool.remove(peerId);
            });
        }
    }

    // ==================== Getters & Setters ====================

    public Ipv6AddressCollector getAddressCollector() { return addressCollector; }
    public TlsHelper getTlsHelper() { return tlsHelper; }
    public ConnectionPool getConnectionPool() { return connectionPool; }
    public MessageRouter getMessageRouter() { return messageRouter; }
    public P2pConfig getConfig() { return config; }
    public boolean isRunning() { return running; }

    public String getMyName() { return myName; }
    public void setMyName(String name) { this.myName = name != null ? name : ""; }

    // ==================== 消息队列 ====================

    /**
     * 存储收到的消息到队列
     */
    public void storeMessage(String peerId, MessageFrame frame) {
        messageQueues.computeIfAbsent(peerId, k -> new ConcurrentLinkedDeque<>()).add(frame);
    }

    /**
     * 拉取并清空指定 Peer 的消息队列
     */
    public List<MessageFrame> pollMessages(String peerId) {
        ConcurrentLinkedDeque<MessageFrame> queue = messageQueues.get(peerId);
        if (queue == null || queue.isEmpty()) {
            return List.of();
        }
        List<MessageFrame> messages = new java.util.ArrayList<>();
        MessageFrame msg;
        while ((msg = queue.poll()) != null) {
            messages.add(msg);
        }
        return messages;
    }

    /**
     * 清除断开节点的消息队列
     */
    public void clearMessages(String peerId) {
        messageQueues.remove(peerId);
    }
}
