package com.example.agentdeepseek.p2p.connection;

import com.example.agentdeepseek.p2p.message.MessageRouter;
import com.example.agentdeepseek.p2p.protocol.MessageFrame;
import com.example.agentdeepseek.p2p.protocol.MessageType;
import com.example.agentdeepseek.p2p.protocol.codec.MessageFrameDecoder;
import com.example.agentdeepseek.p2p.protocol.codec.MessageFrameEncoder;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Netty TCP Client（主动连接对端）
 */
public class P2pClient {

    private static final Logger log = LoggerFactory.getLogger(P2pClient.class);

    private final int heartbeatInterval;
    private final int heartbeatTimeout;
    private final MessageRouter messageRouter;
    private final Consumer<P2pServer.ConnectionEvent> eventListener;

    private EventLoopGroup group;

    public P2pClient(int heartbeatInterval, int heartbeatTimeout,
                     MessageRouter messageRouter,
                     Consumer<P2pServer.ConnectionEvent> eventListener) {
        this.heartbeatInterval = heartbeatInterval;
        this.heartbeatTimeout = heartbeatTimeout;
        this.messageRouter = messageRouter;
        this.eventListener = eventListener;
        this.group = new NioEventLoopGroup();
    }

    /**
     * 连接到指定 Peer
     *
     * @param address     IPv6 地址 + 端口，格式 [2001:db8::1]:9527
     * @param sslContext  该连接的 SslContext（可为 null 表示不启用 TLS）
     * @return Channel Future
     */
    public CompletableFuture<Channel> connect(String address, SslContext sslContext) {
        CompletableFuture<Channel> future = new CompletableFuture<>();

        // 解析地址
        InetSocketAddress socketAddress = parseAddress(address);

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();

                        // TLS
                        if (sslContext != null) {
                            pipeline.addLast("ssl", sslContext.newHandler(ch.alloc()));
                        }

                        // 编解码器
                        pipeline.addLast("frameDecoder", new MessageFrameDecoder());
                        pipeline.addLast("frameEncoder", new MessageFrameEncoder());

                        // 心跳
                        pipeline.addLast("idleStateHandler",
                                new IdleStateHandler(heartbeatTimeout, heartbeatInterval, 0, TimeUnit.SECONDS));

                        // 消息路由
                        pipeline.addLast("messageRouter", messageRouter);

                        // 业务处理器
                        pipeline.addLast("clientHandler", new ClientChannelHandler(future));
                    }
                })
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true);

        ChannelFuture connectFuture = bootstrap.connect(socketAddress);
        connectFuture.addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                future.completeExceptionally(f.cause());
                log.error("[P2P] Failed to connect to {}", address, f.cause());
            }
        });

        return future;
    }

    /**
     * 停止 Client
     */
    public void stop() {
        if (group != null) {
            group.shutdownGracefully();
        }
        log.info("[P2P] Client stopped");
    }

    /**
     * 解析 IPv6 地址字符串
     */
    private InetSocketAddress parseAddress(String address) {
        // 支持格式：[2001:db8::1]:9527 或 2001:db8::1:9527
        if (address.startsWith("[")) {
            int closeBracket = address.lastIndexOf(']');
            if (closeBracket < 0) {
                throw new IllegalArgumentException("Invalid IPv6 address: " + address);
            }
            String host = address.substring(1, closeBracket);
            int port = Integer.parseInt(address.substring(closeBracket + 2)); // skip "]:"
            return new InetSocketAddress(host, port);
        }
        // 无括号格式（不推荐但兼容）
        int lastColon = address.lastIndexOf(':');
        if (lastColon < 0) {
            throw new IllegalArgumentException("Invalid address, missing port: " + address);
        }
        String host = address.substring(0, lastColon);
        int port = Integer.parseInt(address.substring(lastColon + 1));
        return new InetSocketAddress(host, port);
    }

    /**
     * 客户端 Channel 处理器
     */
    private class ClientChannelHandler extends ChannelInboundHandlerAdapter {

        private final CompletableFuture<Channel> connectFuture;

        ClientChannelHandler(CompletableFuture<Channel> connectFuture) {
            this.connectFuture = connectFuture;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            Channel channel = ctx.channel();
            log.info("[P2P] Connected to: {}", channel.remoteAddress());
            connectFuture.complete(channel);
            eventListener.accept(new P2pServer.ConnectionEvent(
                    P2pServer.ConnectionEvent.Type.CONNECTED, channel));
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            Channel channel = ctx.channel();
            log.info("[P2P] Disconnected from: {}", channel.remoteAddress());
            eventListener.accept(new P2pServer.ConnectionEvent(
                    P2pServer.ConnectionEvent.Type.DISCONNECTED, channel));
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof IdleStateEvent idleEvent) {
                if (idleEvent.state() == IdleState.READER_IDLE) {
                    log.warn("[P2P] Heartbeat timeout, closing connection: {}", ctx.channel().remoteAddress());
                    ctx.close();
                } else if (idleEvent.state() == IdleState.WRITER_IDLE) {
                    ctx.writeAndFlush(new MessageFrame(MessageType.HEARTBEAT, new byte[0]));
                }
            } else {
                ctx.fireUserEventTriggered(evt);
            }
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ctx.fireChannelRead(msg);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("[P2P] Client channel error: {}", ctx.channel().remoteAddress(), cause);
            ctx.close();
        }
    }
}
