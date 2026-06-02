package com.example.agentdeepseek.p2p.message;

import com.example.agentdeepseek.p2p.connection.P2pConnectionManager;
import com.example.agentdeepseek.p2p.protocol.MessageFrame;
import com.example.agentdeepseek.p2p.protocol.MessageType;
import com.example.agentdeepseek.p2p.service.FileTransferManager;
import com.example.agentdeepseek.p2p.service.P2pChatService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 文件传输消息处理器
 * <p>
 * 处理 FILE_TRANSFER 消息：META（JSON）、CHUNK（二进制）、COMPLETE/ERROR（JSON）。
 * 接收方：接收 META → 创建会话 → 接收 CHUNK → 写入磁盘 → 收到 COMPLETE → 完成。
 * </p>
 */
public class FileTransferHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(FileTransferHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FileTransferManager transferManager;
    private final P2pChatService chatService;
    private final P2pConnectionManager connectionManager;
    /** 本机名称（由外部设置） */
    private volatile String myName = "";

    public FileTransferHandler(FileTransferManager transferManager, P2pChatService chatService,
                               P2pConnectionManager connectionManager) {
        this.transferManager = transferManager;
        this.chatService = chatService;
        this.connectionManager = connectionManager;
    }

    public void setMyName(String myName) {
        this.myName = myName != null ? myName : "";
    }

    @Override
    public MessageType getSupportedType() {
        return MessageType.FILE_TRANSFER;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, String peerId, MessageFrame frame) {
        byte[] payload = frame.getPayload();
        if (payload == null || payload.length == 0) return;

        if (FileTransferManager.isJsonPayload(payload)) {
            // JSON 类型：META / COMPLETE / ERROR
            handleJson(peerId, frame.getPayloadAsString());
        } else {
            // 二进制类型：CHUNK
            handleChunk(peerId, payload);
        }
    }

    private void handleJson(String peerId, String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            String subType = node.has("subType") ? node.get("subType").asText() : "";
            String transferId = node.has("transferId") ? node.get("transferId").asText() : "";

            switch (subType) {
                case "META" -> handleMeta(peerId, transferId, node);
                case "COMPLETE" -> handleComplete(peerId, transferId);
                case "ERROR" -> handleError(peerId, transferId,
                        node.has("message") ? node.get("message").asText() : "未知错误");
                default -> log.warn("[FT] Unknown FILE_TRANSFER subType: {} from {}", subType, peerId);
            }
        } catch (Exception e) {
            log.error("[FT] Failed to parse FILE_TRANSFER JSON from {}: {}", peerId, e.getMessage());
        }
    }

    private void handleMeta(String peerId, String transferId, JsonNode node) {
        String fileName = node.has("fileName") ? node.get("fileName").asText() : "unknown";
        long fileSize = node.has("fileSize") ? node.get("fileSize").asLong() : 0;
        String mimeType = node.has("mimeType") ? node.get("mimeType").asText() : "application/octet-stream";
        String category = node.has("category") ? node.get("category").asText() : "file";
        int totalChunks = node.has("totalChunks") ? node.get("totalChunks").asInt() : 0;
        int chunkSize = node.has("chunkSize") ? node.get("chunkSize").asInt() : 1024 * 1024;

        FileTransferManager.TransferSession session = transferManager.createSession(
                peerId, transferId, fileName, fileSize, mimeType, category, totalChunks, chunkSize);

        if ("failed".equals(session.status)) {
            log.error("[FT] Failed to create session for {} from {}", transferId, peerId);
            return;
        }

        log.info("[FT] Received FILE_META from {}: {} ({} bytes, {} chunks, category={})",
                peerId, fileName, fileSize, totalChunks, category);
    }

    private void handleChunk(String peerId, byte[] payload) {
        FileTransferManager.ChunkData chunk = FileTransferManager.decodeChunkPayload(payload);
        if (chunk == null) {
            log.warn("[FT] Invalid chunk payload from {}", peerId);
            return;
        }

        boolean ok = transferManager.writeChunk(chunk.transferId, chunk.chunkIndex, chunk.data);
        if (!ok) {
            log.warn("[FT] Failed to write chunk {} for {} from {}",
                    chunk.chunkIndex, chunk.transferId, peerId);
        }
    }

    private void handleComplete(String peerId, String transferId) {
        FileTransferManager.TransferSession session = transferManager.completeTransfer(transferId);
        if (session == null) {
            log.warn("[FT] Failed to complete transfer {} from {}", transferId, peerId);
            return;
        }

        // 存入聊天记录
        String content = "[" + ("image".equals(session.category) ? "图片" : "文件") + "] " + session.fileName;
        String senderName = myName.isEmpty() ? "对方" : myName;
        chatService.saveFileMessage(
                peerId, senderName, content, "received",
                "file_transfer",
                session.fileName, session.fileSize, session.mimeType,
                session.category, session.transferId,
                session.status, session.finalFilePath);

        // 构造消息帧入队，供前端轮询（用 StringBuilder 代替 String.format，避免文件名中的 % 导致格式化异常）
        try {
            String fileMsgJson = "{\"messageType\":\"file_transfer\",\"name\":\"" + escapeJson(senderName) +
                    "\",\"content\":\"" + escapeJson(content) + "\"," +
                    "\"direction\":\"received\"," +
                    "\"fileName\":\"" + escapeJson(session.fileName) + "\"," +
                    "\"fileSize\":" + session.fileSize + "," +
                    "\"mimeType\":\"" + escapeJson(session.mimeType) + "\"," +
                    "\"fileCategory\":\"" + escapeJson(session.category) + "\"," +
                    "\"transferId\":\"" + escapeJson(session.transferId) + "\"," +
                    "\"fileStatus\":\"" + escapeJson(session.status) + "\"}";
            MessageFrame notifyFrame = new MessageFrame(MessageType.FILE_TRANSFER, fileMsgJson);
            connectionManager.storeMessage(peerId, notifyFrame);
        } catch (Exception e) {
            log.warn("[FT] Failed to enqueue file message for {}", transferId, e);
        }

        log.info("[FT] Transfer {} completed and saved to chat: {}", transferId, session.fileName);
    }

    private void handleError(String peerId, String transferId, String message) {
        transferManager.failTransfer(transferId);
        log.warn("[FT] Transfer {} failed from {}: {}", transferId, peerId, message);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
