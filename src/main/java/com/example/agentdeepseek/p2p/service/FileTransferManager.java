package com.example.agentdeepseek.p2p.service;

import com.example.agentdeepseek.p2p.protocol.P2pConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件传输管理器
 * <p>
 * 管理传输会话（TransferSession）：接收对方发来的 FILE_META / FILE_CHUNK / FILE_COMPLETE，
 * 将分块数据组装写入磁盘，完成后生成缩略图（仅图片）。
 * </p>
 */
@Component
public class FileTransferManager {

    private static final Logger log = LoggerFactory.getLogger(FileTransferManager.class);

    /** 传输会话：transferId → TransferSession */
    private final Map<String, TransferSession> sessions = new ConcurrentHashMap<>();

    /** 已完成的传输ID集合（用于快速判断是否已完成） */
    private final Set<String> completedTransfers = ConcurrentHashMap.newKeySet();

    // ==================== 接收方 API ====================

    /**
     * 收到 FILE_META 后创建传输会话
     */
    public TransferSession createSession(String peerId, String transferId, String fileName,
                                          long fileSize, String mimeType, String category,
                                          int totalChunks, int chunkSize) {
        TransferSession session = new TransferSession();
        session.transferId = transferId;
        session.peerId = peerId;
        session.fileName = fileName;
        session.fileSize = fileSize;
        session.mimeType = mimeType;
        session.category = category;
        session.totalChunks = totalChunks;
        session.chunkSize = chunkSize;
        session.receivedChunks = ConcurrentHashMap.newKeySet();
        session.createdAt = System.currentTimeMillis();
        session.lastChunkTime = System.currentTimeMillis(); // 初始化为当前时间，避免被误判为过期
        session.status = "transferring";

        // 确保接收目录存在
        Path peerDir = getPeerReceivedDir(peerId);
        try {
            Files.createDirectories(peerDir);
        } catch (IOException e) {
            log.error("[FT] Failed to create received dir: {}", peerDir, e);
            session.status = "failed";
            return session;
        }

        // 防御性清理文件名：去除路径分隔符和危险字符
        String safeName = sanitizeFileName(fileName);

        // 使用 transferId 子目录隔离，文件名保留原始名称
        Path transferDir = peerDir.resolve(transferId);
        try {
            Files.createDirectories(transferDir);
        } catch (IOException e) {
            log.error("[FT] Failed to create transfer dir: {}", transferDir, e);
            session.status = "failed";
            return session;
        }

        // 临时文件路径（传输中使用 .part 后缀）
        session.tempFilePath = transferDir.resolve(safeName + ".part").toString();
        session.finalFilePath = transferDir.resolve(safeName).toString();

        try {
            // 预分配文件空间（稀疏文件）
            try (RandomAccessFile raf = new RandomAccessFile(session.tempFilePath, "rw")) {
                raf.setLength(fileSize);
            }
        } catch (IOException e) {
            log.error("[FT] Failed to create temp file: {}", session.tempFilePath, e);
            session.status = "failed";
            return session;
        }

        sessions.put(transferId, session);
        log.info("[FT] Session created: {} ({} chunks, {} bytes) from peer {}",
                transferId, totalChunks, fileSize, peerId);
        return session;
    }

    /**
     * 收到 FILE_CHUNK 后写入数据块
     *
     * @return true=写入成功，false=失败
     */
    public boolean writeChunk(String transferId, int chunkIndex, byte[] data) {
        TransferSession session = sessions.get(transferId);
        if (session == null) {
            log.warn("[FT] Chunk for unknown transfer: {}", transferId);
            return false;
        }
        if ("failed".equals(session.status) || "completed".equals(session.status)) {
            log.warn("[FT] Chunk for {} transfer: {} (status={})", session.status, transferId, session.status);
            return false;
        }
        if (chunkIndex < 0 || chunkIndex >= session.totalChunks) {
            log.warn("[FT] Invalid chunkIndex {} for transfer {} (total={})", chunkIndex, transferId, session.totalChunks);
            return false;
        }

        try (RandomAccessFile raf = new RandomAccessFile(session.tempFilePath, "rw")) {
            long offset = (long) chunkIndex * session.chunkSize;
            raf.seek(offset);
            raf.write(data);
            session.receivedChunks.add(chunkIndex);
            session.lastChunkTime = System.currentTimeMillis();
            log.debug("[FT] Chunk {}/{} written for {}", chunkIndex + 1, session.totalChunks, transferId);
            return true;
        } catch (IOException e) {
            log.error("[FT] Failed to write chunk {} for {}: {}", chunkIndex, transferId, e.getMessage());
            return false;
        }
    }

    /**
     * 收到 FILE_COMPLETE 后完成传输
     *
     * @return TransferSession（含 finalFilePath），失败返回 null
     */
    public TransferSession completeTransfer(String transferId) {
        TransferSession session = sessions.get(transferId);
        if (session == null) {
            log.warn("[FT] Complete for unknown transfer: {}", transferId);
            return null;
        }

        // 校验所有分块是否收齐
        if (session.receivedChunks.size() != session.totalChunks) {
            log.warn("[FT] Transfer {} incomplete: received {}/{} chunks",
                    transferId, session.receivedChunks.size(), session.totalChunks);
            session.status = "failed";
            return null;
        }

        // 校验文件大小
        File tempFile = new File(session.tempFilePath);
        if (tempFile.length() != session.fileSize) {
            log.warn("[FT] Transfer {} size mismatch: expected {}, got {}",
                    transferId, session.fileSize, tempFile.length());
            session.status = "failed";
            return null;
        }

        // 重命名 .part → 最终文件名
        File finalFile = new File(session.finalFilePath);
        if (!tempFile.renameTo(finalFile)) {
            // renameTo 失败时尝试复制
            try {
                Files.copy(tempFile.toPath(), finalFile.toPath());
                tempFile.delete();
            } catch (IOException e) {
                log.error("[FT] Failed to rename temp file for {}", transferId, e);
                session.status = "failed";
                return null;
            }
        }

        session.status = "completed";
        completedTransfers.add(transferId);
        log.info("[FT] Transfer {} completed: {} ({} bytes)", transferId, session.finalFilePath, session.fileSize);

        // 如果是图片，生成缩略图
        if ("image".equals(session.category)) {
            Path transferDir = Paths.get(session.finalFilePath).getParent();
            session.thumbnailPath = generateThumbnail(session.finalFilePath, session.fileName, transferDir);
            log.info("[FT] Thumbnail generated for {}: {}", transferId, session.thumbnailPath);
        }

        return session;
    }

    /**
     * 取消/标记失败
     */
    public void failTransfer(String transferId) {
        TransferSession session = sessions.get(transferId);
        if (session != null) {
            session.status = "failed";
            // 清理临时文件
            try {
                Files.deleteIfExists(Paths.get(session.tempFilePath));
            } catch (IOException ignored) {}
        }
    }

    /**
     * 获取传输会话
     */
    public TransferSession getSession(String transferId) {
        return sessions.get(transferId);
    }

    /**
     * 获取传输进度
     */
    public Map<String, Object> getProgress(String transferId) {
        TransferSession session = sessions.get(transferId);
        if (session == null) {
            return Map.of("status", "unknown");
        }
        Map<String, Object> progress = new HashMap<>();
        progress.put("transferId", session.transferId);
        progress.put("totalChunks", session.totalChunks);
        progress.put("receivedChunks", session.receivedChunks.size());
        progress.put("status", session.status);
        return progress;
    }

    /**
     * 清理超时的传输会话（每 60 秒执行一次）
     */
    @Scheduled(fixedRate = 60000)
    public void cleanExpiredSessions() {
        long now = System.currentTimeMillis();
        long timeout = P2pConstants.TRANSFER_TIMEOUT_SECONDS * 1000L;
        List<String> expired = new ArrayList<>();
        for (TransferSession session : sessions.values()) {
            if ("transferring".equals(session.status)
                    && (now - session.lastChunkTime) > timeout) {
                expired.add(session.transferId);
            }
        }
        for (String tid : expired) {
            failTransfer(tid);
            log.warn("[FT] Transfer {} expired (timeout {}s)", tid, P2pConstants.TRANSFER_TIMEOUT_SECONDS);
        }
    }

    /**
     * 获取已完成的传输ID列表（用于发送方确认）
     */
    public boolean isCompleted(String transferId) {
        return completedTransfers.contains(transferId);
    }

    // ==================== 辅助方法 ====================

    /**
     * 获取指定 Peer 的接收目录
     */
    private Path getPeerReceivedDir(String peerId) {
        return Paths.get(P2pConstants.RECEIVED_DIR, peerId);
    }

    /**
     * 获取项目根目录下某个文件的完整路径
     */
    public static Path resolveReceivedPath(String peerId, String relativePath) {
        return Paths.get(P2pConstants.RECEIVED_DIR, peerId, relativePath);
    }

    /**
     * 清理文件名中的危险字符，防止路径遍历攻击
     */
    static String sanitizeFileName(String name) {
        if (name == null || name.isEmpty()) return "unknown";
        // 去除路径分隔符、当前目录、父目录标记
        return name.replaceAll("[\\\\/]+", "_")
                   .replaceAll("\\.{2,}", "_")
                   .replaceAll("[\\x00-\\x1f]", "") // 去除控制字符
                   .trim();
    }

    /**
     * 生成缩略图
     */
    private String generateThumbnail(String imagePath, String fileName, Path dir) {
        try {
            BufferedImage img = ImageIO.read(new File(imagePath));
            if (img == null) {
                log.warn("[FT] Cannot read image for thumbnail: {}", imagePath);
                return null;
            }

            int thumbWidth = P2pConstants.THUMBNAIL_MAX_WIDTH;
            int thumbHeight = (int) ((double) img.getHeight() / img.getWidth() * thumbWidth);
            if (thumbHeight < 1) thumbHeight = 1;

            BufferedImage thumb = new BufferedImage(thumbWidth, thumbHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = thumb.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(img, 0, 0, thumbWidth, thumbHeight, null);
            g.dispose();

            String thumbFileName = fileName + ".thumb.jpg";
            File thumbFile = dir.resolve(thumbFileName).toFile();
            ImageIO.write(thumb, "jpg", thumbFile);

            return thumbFile.getAbsolutePath();
        } catch (Exception e) {
            log.error("[FT] Failed to generate thumbnail for {}: {}", imagePath, e.getMessage());
            return null;
        }
    }

    // ==================== CHUNK 编解码 ====================

    /**
     * 编码 CHUNK 帧 payload：[transferIdLen(1)] [transferId(N)] [chunkIndex(4)] [data]
     */
    public static byte[] encodeChunkPayload(String transferId, int chunkIndex, byte[] data) {
        byte[] tidBytes = transferId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(1 + tidBytes.length + 4 + data.length);
        buf.put((byte) tidBytes.length);
        buf.put(tidBytes);
        buf.putInt(chunkIndex);
        buf.put(data);
        return buf.array();
    }

    /**
     * 解码 CHUNK 帧 payload，返回 ChunkData
     */
    public static ChunkData decodeChunkPayload(byte[] payload) {
        if (payload == null || payload.length < P2pConstants.CHUNK_HEADER_SIZE) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(payload);
        int tidLen = buf.get() & 0xFF;
        if (tidLen <= 0 || 1 + tidLen + 4 > payload.length) {
            return null;
        }
        byte[] tidBytes = new byte[tidLen];
        buf.get(tidBytes);
        String transferId = new String(tidBytes, StandardCharsets.UTF_8);
        int chunkIndex = buf.getInt();
        byte[] data = new byte[buf.remaining()];
        buf.get(data);
        return new ChunkData(transferId, chunkIndex, data);
    }

    /**
     * 判断 payload 是否为 JSON（META / COMPLETE / ERROR）
     */
    public static boolean isJsonPayload(byte[] payload) {
        return payload != null && payload.length > 0 && payload[0] == '{';
    }

    // ==================== 内部类 ====================

    /**
     * 传输会话
     */
    public static class TransferSession {
        public String transferId;
        public String peerId;
        public String fileName;
        public long fileSize;
        public String mimeType;
        public String category;        // "image" / "file"
        public int totalChunks;
        public int chunkSize;
        public Set<Integer> receivedChunks;
        public String tempFilePath;
        public String finalFilePath;
        public String thumbnailPath;
        public String status;          // "transferring" / "completed" / "failed"
        public long createdAt;
        public long lastChunkTime;
    }

    /**
     * 解码后的 CHUNK 数据
     */
    public static class ChunkData {
        public final String transferId;
        public final int chunkIndex;
        public final byte[] data;

        public ChunkData(String transferId, int chunkIndex, byte[] data) {
            this.transferId = transferId;
            this.chunkIndex = chunkIndex;
            this.data = data;
        }
    }
}
