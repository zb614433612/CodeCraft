package com.example.agentdeepseek.service.files;

import com.example.agentdeepseek.mapper.FileAssetMapper;
import com.example.agentdeepseek.model.entity.FileAsset;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 图像理解服务（M3）：把文件资产转换为 LLM 消息内容块（vision 注入链路）
 *
 * <h3>职责</h3>
 * <ol>
 *   <li>{@link #buildFileBlocks}：fileAssetIds → 内容块（支持 Files API → {@code file} 块；
 *       不支持 → 本地副本 base64 降级 {@code image_url} 块）；含数量/大小限额与缓存</li>
 *   <li>{@link #injectBlocksIntoLastUserMessage}：把块注入最后一条 user 消息（content 数组化，幂等）</li>
 *   <li>{@link #appendVisionWarnings}：失效/跳过项的文本提示（不静默丢图）</li>
 *   <li>{@link #supportsVisionModel}：模型 vision 能力判定（后端防线，M4 前端拦截的兜底）</li>
 * </ol>
 *
 * <h3>注入顺序铁律</h3>
 * 数组化必须在所有"字符串注入"（技能/附件提示/语言指令/detour）<b>之后</b>执行——
 * 上述注入均以 {@code (String) msg.get("content")} 强转方式拼接文本，数组化后再调用会 ClassCastException。
 *
 * <h3>块格式（DeepSeek / OpenAI 兼容）</h3>
 * <ul>
 *   <li>file 块：{@code {"type":"file","file_id":"file-api-..."}}（F1：经 file_id 引用不受 32MiB 单图限制）</li>
 *   <li>降级块：{@code {"type":"image_url","image_url":{"url":"data:<mime>;base64,...","detail":"auto"}}}
 *       （备选：DeepSeek 亦支持 file 块 file_data 内联形态，当前按通用 OpenAI 风格实现）</li>
 * </ul>
 */
@Slf4j
@Service
public class FileVisionService {

    /** 单次请求最多注入的图片数 */
    public static final int MAX_IMAGE_COUNT = 8;

    /** 降级路径（base64）总大小上限：32MiB（估算值 = 原文件大小 × base64 膨胀系数） */
    public static final long MAX_DEGRADED_BASE64_BYTES = 32L * 1024 * 1024;

    /** base64 膨胀系数（4/3 ≈ 1.34，取 1.37 留余量） */
    private static final double BASE64_OVERHEAD = 1.37;

    /** 支持的图片 MIME（对齐 Files API：JPEG/PNG/GIF/WebP） */
    public static final Set<String> SUPPORTED_IMAGE_MIME = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp");

    private final FileAssetMapper fileAssetMapper;
    private final FileAssetStore fileAssetStore;
    private final FilesApiClientManager filesApiClientManager;

    /** 块缓存：providerCode::fileAssetId → block（防工具循环/后续轮次重复 IO 与编码） */
    private final Cache<String, Map<String, Object>> blockCache = Caffeine.newBuilder()
            .maximumSize(16)
            .expireAfterWrite(15, TimeUnit.MINUTES)
            .build();

    public FileVisionService(FileAssetMapper fileAssetMapper,
                             FileAssetStore fileAssetStore,
                             FilesApiClientManager filesApiClientManager) {
        this.fileAssetMapper = fileAssetMapper;
        this.fileAssetStore = fileAssetStore;
        this.filesApiClientManager = filesApiClientManager;
    }

    // ==================== 块构建 ====================

    /**
     * 构建图像内容块列表（按 Provider 能力分路）
     *
     * @param fileAssetIds 本地文件资产ID列表（当轮上传 + 历史引用）
     * @param userId       当前用户ID（"仅本人"校验）
     * @param providerCode 目标 Provider code
     * @return 块列表 + 警告列表（失效/超限项不静默丢弃）
     */
    public FileBlocksResult buildFileBlocks(List<Long> fileAssetIds, Long userId, String providerCode) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (fileAssetIds == null || fileAssetIds.isEmpty()) {
            return new FileBlocksResult(blocks, warnings);
        }
        boolean supportsFiles = filesApiClientManager.supportsFilesApi(providerCode);
        long degradedBytesAccum = 0;
        int accepted = 0;
        Set<Long> seen = new HashSet<>();

        for (Long assetId : fileAssetIds) {
            if (assetId == null || !seen.add(assetId)) {
                continue;
            }
            if (accepted >= MAX_IMAGE_COUNT) {
                warnings.add("图片数量超过上限（最多 " + MAX_IMAGE_COUNT + " 张），多余图片已忽略");
                break;
            }
            FileAsset asset = fileAssetMapper.selectById(assetId);
            if (asset == null || !asset.getUserId().equals(userId)) {
                warnings.add("图片 #" + assetId + " 不存在或无权访问，已跳过");
                continue;
            }
            String name = displayNameOf(asset);
            if (!"active".equals(asset.getStatus())) {
                warnings.add("图片「" + name + "」已失效（" + asset.getStatus() + "），已跳过");
                continue;
            }
            if (asset.getMimeType() == null || !SUPPORTED_IMAGE_MIME.contains(asset.getMimeType())) {
                warnings.add("图片「" + name + "」格式不支持（仅 JPEG/PNG/GIF/WebP），已跳过");
                continue;
            }

            String cacheKey = providerCode + "::" + assetId;
            Map<String, Object> cached = blockCache.getIfPresent(cacheKey);
            if (cached != null) {
                blocks.add(cached);
                accepted++;
                continue;
            }

            Map<String, Object> block;
            if (supportsFiles) {
                // 路径一：Provider 支持 Files API → file 块（经 file_id 引用）
                block = new LinkedHashMap<>();
                block.put("type", "file");
                block.put("file_id", asset.getFileId());
            } else {
                // 路径二：降级 → 本地副本 base64 → image_url 块
                Path path = fileAssetStore.resolve(asset.getLocalPath());
                if (path == null || !Files.exists(path)) {
                    warnings.add("图片「" + name + "」本地副本缺失，已跳过");
                    continue;
                }
                long estimated = (asset.getSize() == null ? 0 : (long) (asset.getSize() * BASE64_OVERHEAD)) + 128;
                if (degradedBytesAccum + estimated > MAX_DEGRADED_BASE64_BYTES) {
                    warnings.add("图片总大小超过降级上限（32MB），剩余图片已忽略");
                    break;
                }
                try {
                    byte[] bytes = Files.readAllBytes(path);
                    String base64 = Base64.getEncoder().encodeToString(bytes);
                    block = new LinkedHashMap<>();
                    block.put("type", "image_url");
                    Map<String, Object> imageUrl = new LinkedHashMap<>();
                    imageUrl.put("url", "data:" + asset.getMimeType() + ";base64," + base64);
                    imageUrl.put("detail", "auto");
                    block.put("image_url", imageUrl);
                    degradedBytesAccum += estimated;
                    log.info("图像降级注入（base64）: assetId={}, name={}, mime={}, bytes={}",
                            assetId, name, asset.getMimeType(), asset.getSize());
                } catch (IOException e) {
                    warnings.add("图片「" + name + "」读取失败，已跳过");
                    log.warn("读取图片本地副本失败: assetId={}, path={} - {}", assetId, path, e.getMessage());
                    continue;
                }
            }
            blockCache.put(cacheKey, block);
            blocks.add(block);
            accepted++;
            if (supportsFiles) {
                log.info("图像注入（file 块）: assetId={}, name={}, fileId={}", assetId, name, asset.getFileId());
            }
        }
        if (!blocks.isEmpty() || !warnings.isEmpty()) {
            log.info("图像块构建完成: provider={}, filesApi={}, blocks={}, warnings={}",
                    providerCode, supportsFiles, blocks.size(), warnings.size());
        }
        return new FileBlocksResult(blocks, warnings);
    }

    // ==================== 注入（纯函数，供两处调用点复用） ====================

    /**
     * 把图像块注入最后一条 user 消息（content 数组化）
     * <p>
     * 幂等：content 已是数组且含图片类块时跳过；否则追加缺失块。
     * 顺序铁律：必须在所有字符串注入之后调用（详见类注释）。
     * </p>
     *
     * @param messages 消息列表（就地修改）
     * @param blocks   图像块（可空/空则 no-op）
     */
    @SuppressWarnings("unchecked")
    public static void injectBlocksIntoLastUserMessage(List<Map<String, Object>> messages,
                                                       List<Map<String, Object>> blocks) {
        if (messages == null || messages.isEmpty() || blocks == null || blocks.isEmpty()) {
            return;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            if (!"user".equals(msg.get("role"))) {
                continue;
            }
            Object content = msg.get("content");
            if (content instanceof String text) {
                // 字符串 → 数组化：[{type:text,text:原文}, ...图像块]
                List<Object> parts = new ArrayList<>();
                Map<String, Object> textBlock = new LinkedHashMap<>();
                textBlock.put("type", "text");
                textBlock.put("text", text);
                parts.add(textBlock);
                parts.addAll(blocks);
                msg.put("content", parts);
            } else if (content instanceof List<?> list) {
                List<Object> parts = (List<Object>) list;
                boolean hasImageBlock = parts.stream().anyMatch(FileVisionService::isImageBlock);
                if (!hasImageBlock) {
                    parts.addAll(blocks);
                }
            }
            break; // 只处理最后一条 user 消息
        }
    }

    /**
     * 把图片处理警告以文本提示形式附加到最后一条 user 消息（不静默丢图）
     * <p>兼容 content 为 String（尾部追加）与数组（追加 text 块）两种形态。</p>
     */
    @SuppressWarnings("unchecked")
    public static void appendVisionWarnings(List<Map<String, Object>> messages, List<String> warnings) {
        if (messages == null || messages.isEmpty() || warnings == null || warnings.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder("\n\n---\n[系统提示] 图片处理警告：\n");
        for (String w : warnings) {
            sb.append("- ").append(w).append("\n");
        }
        sb.append("---\n");
        String text = sb.toString();

        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            if (!"user".equals(msg.get("role"))) {
                continue;
            }
            Object content = msg.get("content");
            if (content instanceof String existing) {
                msg.put("content", existing + text);
            } else if (content instanceof List<?> list) {
                List<Object> parts = (List<Object>) list;
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("type", "text");
                block.put("text", text);
                parts.add(block);
            }
            break;
        }
    }

    /**
     * 判定内容块是否为图片类块（file / image_url / image）
     */
    private static boolean isImageBlock(Object item) {
        if (!(item instanceof Map<?, ?> block)) {
            return false;
        }
        Object type = block.get("type");
        return "file".equals(type) || "image_url".equals(type) || "image".equals(type);
    }

    // ==================== 模型能力判定 ====================

    /**
     * 模型 vision 能力判定（后端防线：带图但模型不支持 → 调用方抛明确异常）
     * <p>
     * 规则：<br>
     * - deepseek 官方：flash / vision / vl 系列支持图像理解；chat / reasoner 暂不支持；<br>
     * - 火山方舟（volcengine / ark / doubao）：方舟上的 DeepSeek 文本模型不支持图像输入；
     *   豆包 seed 系列、vision / vl 系列支持；其余未知模型宽松放行（由 API 层兜底报错）；<br>
     * - 小米 MiMo：v2.5 系列原生多模态（图片/音频/视频），更早的纯文本模型不支持；<br>
     * - MiniMax：M3 与 VL / abab-vl 系列支持视觉，M2.x 为纯文本模型；<br>
     * - 其他 Provider：暂不限制（宽松放行，由 API 层兜底报错；未来按需扩展）；<br>
     * - 模型名为空：放行（未知即不拦，避免误伤）。
     * </p>
     */
    public static boolean supportsVisionModel(String providerCode, String model) {
        if (model == null || model.isBlank()) {
            return true;
        }
        String m = model.toLowerCase();
        String code = providerCode == null ? "" : providerCode.toLowerCase();
        if ("deepseek".equals(code)) {
            return m.contains("flash") || m.contains("vision") || m.contains("-vl");
        }
        if ("volcengine".equals(code) || "ark".equals(code) || "doubao".equals(code)) {
            return supportsArkVisionModel(m);
        }
        if ("mimo".equals(code) || "xiaomi-mimo".equals(code)) {
            // MiMo v2.5 / v2.5-pro 原生多模态；早期纯文本模型（如 mimo-7b）不带视觉塔
            return m.contains("v2.5") || m.contains("vision") || m.contains("-vl");
        }
        if (code.startsWith("minimax")) {
            // M2 / M2.1 / M2.5 / M2.7 为纯文本；M3 与 VL 系列支持图像输入
            if (m.contains("-m2") || m.startsWith("m2")) {
                return false;
            }
            return m.contains("m3") || m.contains("vision") || m.contains("-vl") || m.contains("abab");
        }
        if ("qwen".equals(code) || "dashscope".equals(code) || "tongyi".equals(code)) {
            // Qwen-VL / Qwen3-VL / Omni 系列支持图像输入；qwen-plus / qwen-max 等纯文本模型不支持
            return m.contains("-vl") || m.contains("vl-") || m.contains("omni") || m.contains("vision");
        }
        return true;
    }

    /**
     * 火山方舟模型的图像理解能力判定。
     * <p>
     * 方舟上的 deepseek 系文本模型（deepseek-v4-pro / flash / v3 等）不支持图像输入；
     * 豆包 seed 系列原生多模态，vision / vl 命名同样支持；
     * 其余模型（glm / kimi 等）宽松放行，由方舟 API 返回明确错误。
     * </p>
     */
    private static boolean supportsArkVisionModel(String modelLowerCase) {
        if (modelLowerCase.startsWith("deepseek")) {
            return false;
        }
        if (modelLowerCase.contains("vision") || modelLowerCase.contains("-vl") || modelLowerCase.contains("vlm")) {
            return true;
        }
        // 豆包 seed 系列（1.6 / 2.0 / 2.1 等）原生多模态
        return true;
    }

    // ==================== 内部工具 ====================

    private static String displayNameOf(FileAsset asset) {
        String name = asset.getDisplayName();
        if (name == null || name.isBlank()) {
            name = asset.getFilename();
        }
        return (name == null || name.isBlank()) ? ("#" + asset.getId()) : name;
    }

    /**
     * 块构建结果（blocks + warnings）
     */
    public record FileBlocksResult(List<Map<String, Object>> blocks, List<String> warnings) {
    }
}
