package com.example.agentdeepseek.p2p.message;

import com.example.agentdeepseek.mapper.P2pKnownPeerMapper;
import com.example.agentdeepseek.model.entity.P2pKnownPeer;
import com.example.agentdeepseek.p2p.connection.P2pConnectionManager;
import com.example.agentdeepseek.p2p.connection.PeerInfo;
import com.example.agentdeepseek.p2p.protocol.MessageFrame;
import com.example.agentdeepseek.p2p.protocol.MessageType;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 握手消息处理器
 * <p>
 * 收到对方握手 → 注册对方信息 → 回发本机握手（双向交换名称）
 * </p>
 */
public class HandshakeHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(HandshakeHandler.class);

    private final P2pConnectionManager connectionManager;
    private final P2pKnownPeerMapper knownPeerMapper;

    public HandshakeHandler(P2pConnectionManager connectionManager, P2pKnownPeerMapper knownPeerMapper) {
        this.connectionManager = connectionManager;
        this.knownPeerMapper = knownPeerMapper;
        knownPeerMapper.createTableIfNotExists();
    }

    @Override
    public MessageType getSupportedType() {
        return MessageType.HANDSHAKE;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, String peerId, MessageFrame frame) {
        String payload = frame.getPayloadAsString();
        log.debug("[P2P] Received handshake from {}: {}", peerId, payload);

        HandshakeData data = HandshakeData.fromJson(payload);
        if (data == null) {
            log.warn("[P2P] Invalid handshake payload from {}", peerId);
            return;
        }

        // 注册对方信息
        Channel channel = ctx.channel();
        PeerInfo peerInfo = new PeerInfo(data.getPeerId(), data.getAddress(), data.getCertFingerprint(), data.getName());
        connectionManager.getConnectionPool().register(data.getPeerId(), channel, peerInfo);
        log.debug("[P2P] Peer handshake completed: {}", peerInfo);

        // 写入机器特征记忆表
        try {
            P2pKnownPeer known = knownPeerMapper.findByPeerId(data.getPeerId());
            if (known == null) {
                P2pKnownPeer newPeer = new P2pKnownPeer();
                newPeer.setPeerId(data.getPeerId());
                newPeer.setName(data.getName() != null ? data.getName() : "");
                newPeer.setAddress(data.getAddress());
                newPeer.setCertFingerprint(data.getCertFingerprint());
                knownPeerMapper.upsertOnHandshake(newPeer);
            } else {
                knownPeerMapper.updateConnectTime(data.getPeerId());
                if (data.getName() != null && !data.getName().isEmpty()) {
                    knownPeerMapper.updateName(data.getPeerId(), data.getName());
                }
            }
        } catch (Exception e) {
            log.warn("[P2P] Failed to update known peer: {}", e.getMessage());
        }

        // 回发本机握手（让对方也知道本机名称）
        String myAddress = connectionManager.getAddressCollector().getBestAddressString()
                .orElse("[::1]:" + connectionManager.getConfig().getPort());
        MessageFrame reply = createHandshakeFrame(
                connectionManager.getAddressCollector().getPeerId(),
                myAddress,
                connectionManager.getTlsHelper().getCertFingerprint(),
                connectionManager.getMyName());
        channel.writeAndFlush(reply);
        log.debug("[P2P] Replied handshake to peer: {}", data.getPeerId());
    }

    /**
     * 构建握手消息帧（客户端连接后发送）
     */
    public static MessageFrame createHandshakeFrame(String peerId, String address, String certFingerprint, String name) {
        HandshakeData data = new HandshakeData(peerId, address, certFingerprint, name);
        return new MessageFrame(MessageType.HANDSHAKE, data.toJson());
    }

    /**
     * 握手数据结构
     */
    public static class HandshakeData {
        private final String peerId;
        private final String address;
        private final String certFingerprint;
        private final String name;

        public HandshakeData(String peerId, String address, String certFingerprint, String name) {
            this.peerId = peerId;
            this.address = address;
            this.certFingerprint = certFingerprint;
            this.name = name;
        }

        public String getPeerId() { return peerId; }
        public String getAddress() { return address; }
        public String getCertFingerprint() { return certFingerprint; }
        public String getName() { return name; }

        public String toJson() {
            return "{\"peerId\":\"" + peerId + "\",\"address\":\"" + address
                    + "\",\"fingerprint\":\"" + certFingerprint
                    + "\",\"name\":\"" + (name != null ? name : "") + "\"}";
        }

        public static HandshakeData fromJson(String json) {
            try {
                String peerId = extractValue(json, "peerId");
                String address = extractValue(json, "address");
                String fingerprint = extractValue(json, "fingerprint");
                String name = extractValue(json, "name");
                if (peerId == null || address == null || fingerprint == null) return null;
                return new HandshakeData(peerId, address, fingerprint, name != null ? name : "");
            } catch (Exception e) {
                return null;
            }
        }

        private static String extractValue(String json, String key) {
            String searchKey = "\"" + key + "\":\"";
            int start = json.indexOf(searchKey);
            if (start < 0) return null;
            start += searchKey.length();
            int end = json.indexOf('"', start);
            if (end < 0) return null;
            return json.substring(start, end);
        }
    }
}
