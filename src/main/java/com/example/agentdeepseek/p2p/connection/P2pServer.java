package com.example.agentdeepseek.p2p.connection;

import com.example.agentdeepseek.p2p.message.MessageRouter;
import com.example.agentdeepseek.p2p.protocol.MessageFrame;
import com.example.agentdeepseek.p2p.protocol.MessageType;
import com.example.agentdeepseek.p2p.protocol.codec.MessageFrameDecoder;
import com.example.agentdeepseek.p2p.protocol.codec.MessageFrameEncoder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Netty TCP Server（IPv6 监听）
 * <p>
 * 监听指定端口，等待对端 P2P 连接
 * </p>
 */
public class P2pServer {

    private static final Logger log = LoggerFactory.getLogger(P2pServer.class);

    private final int port;
    private final SslContext sslContext;
    private final int heartbeatInterval;
    private final int heartbeatTimeout;
    private final ConnectionPool connectionPool;
    private final MessageRouter messageRouter;
    private final Consumer<ConnectionEvent> eventListener;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public P2pServer(int port, SslContext sslContext, int heartbeatInterval, int heartbeatTimeout,
                     ConnectionPool connectionPool, MessageRouter messageRouter,
                     Consumer<ConnectionEvent> eventListener) {
        this.port = port;
        this.sslContext = sslContext;
        this.heartbeatInterval = heartbeatInterval;
        this.heartbeatTimeout = heartbeatTimeout;
        this.connectionPool = connectionPool;
        this.messageRouter = messageRouter;
        this.eventListener = eventListener;
    }

    /**
     * 启动 Server
     */
    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();

                        // TLS（如果提供）
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
                        pipeline.addLast("serverHandler", new ServerChannelHandler());
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true);

        // 绑定到 IPv6 通配地址 "::"
        InetSocketAddress bindAddr = new InetSocketAddress("::", port);
        serverChannel = bootstrap.bind(bindAddr).sync().channel();
        log.info("[P2P] Server started on [::]:{} (IPv6)", port);
    }

    /**
     * 停止 Server
     */
    public void stop() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        log.info("[P2P] Server stopped");
    }

    /**
     * 连接事件
     */
    public static class ConnectionEvent {
        public enum Type { CONNECTED, DISCONNECTED }
        private final Type type;
        private final Channel channel;

        public ConnectionEvent(Type type, Channel channel) {
            this.type = type;
            this.channel = channel;
        }
        public Type getType() { return type; }
        public Channel getChannel() { return channel; }
    }

    /**
     * 服务端 Channel 处理器
     */
    private class ServerChannelHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            Channel channel = ctx.channel();
            log.info("[P2P] New inbound connection from: {}", channel.remoteAddress());
            eventListener.accept(new ConnectionEvent(ConnectionEvent.Type.CONNECTED, channel));
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            Channel channel = ctx.channel();
            log.info("[P2P] Connection closed: {}", channel.remoteAddress());
            // 不在这里 remove，交给 onConnectionEvent 统一处理重连逻辑
            eventListener.accept(new ConnectionEvent(ConnectionEvent.Type.DISCONNECTED, channel));
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof IdleStateEvent idleEvent) {
                if (idleEvent.state() == IdleState.READER_IDLE) {
                    // 读超时：对端心跳丢失，断开连接
                    log.warn("[P2P] Heartbeat timeout, closing connection: {}", ctx.channel().remoteAddress());
                    ctx.close();
                } else if (idleEvent.state() == IdleState.WRITER_IDLE) {
                    // 写空闲：发送心跳
                    ctx.writeAndFlush(new MessageFrame(MessageType.HEARTBEAT, new byte[0]));
                }
            } else {
                ctx.fireUserEventTriggered(evt);
            }
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            // 消息转发给下一层（T9 消息路由模块处理）
            ctx.fireChannelRead(msg);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("[P2P] Server channel error: {}", ctx.channel().remoteAddress(), cause);
            ctx.close();
        }
    }
}
