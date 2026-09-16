package com.example.agentdeepseek.service.files;

import com.example.agentdeepseek.mapper.FileAssetMapper;
import com.example.agentdeepseek.mapper.FileReferenceMapper;
import com.example.agentdeepseek.model.entity.FileAsset;
import com.example.agentdeepseek.model.entity.FileReference;
import com.example.agentdeepseek.model.vo.FileAssetVO;
import com.example.agentdeepseek.service.files.model.FileObject;
import com.example.agentdeepseek.service.files.model.FileUploadRequest;
import com.example.agentdeepseek.service.llm.LLMClientManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * 文件资产服务：上传 / 列表 / 查询 / 重命名 / 删除 + 引用关联 + 引用归零级联清理
 *
 * <h3>流程要点</h3>
 * <ul>
 *   <li>上传：本地副本先落盘 → 远端 Files API 上传（永久保存，不传 expires_after）→ 写记录；任一步失败做补偿清理</li>
 *   <li>删除：远端删除（404 视为成功）→ 删引用 + 记录 + 本地副本</li>
 *   <li>级联：会话删除后对受影响文件做"引用归零"判定；归零才删远端/本地/记录，远端失败标记 orphan 待补偿</li>
 *   <li>远端网络调用不进事务（F3 决策 4）：含远端 IO 的方法不加 @Transactional，由调用链保证顺序</li>
 * </ul>
 *
 * <h3>异常语义（Controller 统一转换）</h3>
 * <ul>
 *   <li>{@link NoSuchElementException} → 404（不存在或无权访问）</li>
 *   <li>{@link IllegalArgumentException} → 400（参数/校验失败）</li>
 *   <li>{@link IllegalStateException} → 500（服务状态/远端失败）</li>
 * </ul>
 */
@Slf4j
@Service
public class FileAssetService {

    /** 单文件上限：64MB（对齐 DeepSeek Files API 单文件上限 64 MiB） */
    public static final long MAX_FILE_SIZE = 64L * 1024 * 1024;

    /** 文件名/显示名最大长度 */
    public static final int MAX_FILENAME_LENGTH = 512;

    /**
     * 文档扩展名白名单（M7，本地存储）
     * <p>与 Parser 支持范围对齐（PdfParser / WordParser / ExcelParser / TextParser），
     * 保证上传的文档均可被 chat_attachment 工具解析读取。</p>
     */
    public static final Set<String> DOC_EXTENSIONS = Set.of(
            "pdf",
            "doc", "docx",
            "xls", "xlsx", "csv",
            "txt", "md", "log", "json", "yml", "yaml", "xml",
            "properties", "cfg", "ini", "toml",
            "ts", "tsx", "vue", "js", "jsx", "css", "scss", "less",
            "html", "htm", "svg",
            "java", "kt", "groovy", "py", "go", "rb", "php", "rs", "swift",
            "c", "cpp", "h", "hpp", "sh", "bat", "ps1", "sql", "gradle", "m",
            "proto", "graphql", "dockerfile");

    /** 列表单页条数上限 */
    private static final int MAX_PAGE_SIZE = 200;

    /** 列表默认条数 */
    private static final int DEFAULT_PAGE_SIZE = 50;

    /** orphan 补偿单批扫描条数（防单次任务过长；剩余批次由下一轮调度继续） */
    private static final int ORPHAN_CLEANUP_BATCH_SIZE = 100;

    /** orphan 补偿任务开关（默认启用；测试/排障可关） */
    @Value("${file-asset.orphan-cleanup.enabled:true}")
    private boolean orphanCleanupEnabled;

    /** orphan 补偿重试告警阈值（连续失败超过该次数后升级为 ERROR 告警并保留记录） */
    @Value("${file-asset.orphan-cleanup.max-retries:5}")
    private int orphanCleanupMaxRetries;

    private final FileAssetMapper fileAssetMapper;
    private final FileReferenceMapper fileReferenceMapper;
    private final FilesApiClientManager filesApiClientManager;
    private final FileAssetStore fileAssetStore;
    private final LLMClientManager llmClientManager;

    public FileAssetService(FileAssetMapper fileAssetMapper,
                            FileReferenceMapper fileReferenceMapper,
                            FilesApiClientManager filesApiClientManager,
                            FileAssetStore fileAssetStore,
                            LLMClientManager llmClientManager) {
        this.fileAssetMapper = fileAssetMapper;
        this.fileReferenceMapper = fileReferenceMapper;
        this.filesApiClientManager = filesApiClientManager;
        this.fileAssetStore = fileAssetStore;
        this.llmClientManager = llmClientManager;
    }

    // ==================== 上传 ====================

    /**
     * 上传文件资产（按类型分流：图片 → 云端 Files API；文档 → 本地存储）
     * <ul>
     *   <li>图片（魔数 JPEG/PNG/GIF/WebP）：本地副本 + 远端 Files API + 记录（storage_type=cloud）</li>
     *   <li>文档（白名单扩展名，如 pdf/word/excel/文本/代码）：仅本地存储 + 记录（storage_type=local，无远端）</li>
     * </ul>
     *
     * @param file         上传文件（multipart）
     * @param userId       当前用户ID
     * @param providerCode 目标 Provider（可空：缺省用首个 Provider；仅图片使用）
     * @param source       来源（upload/paste，其他值收敛为 upload）
     * @return 资产视图（含 previewUrl）
     */
    public FileAssetVO upload(MultipartFile file, Long userId, String providerCode, String source) {
        // 1. 基础校验
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("文件大小超过上限（最大 " + (MAX_FILE_SIZE / 1024 / 1024) + "MB）");
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            originalName = "unnamed";
        }
        if (originalName.length() > MAX_FILENAME_LENGTH) {
            throw new IllegalArgumentException("文件名过长（最大 " + MAX_FILENAME_LENGTH + " 字符）");
        }

        // 2. 魔数检测分流（M7）：图片 → 云端 Files API 流程；非图片 → 文档本地存储流程
        String mimeType = detectImageMimeType(file);
        if (mimeType == null) {
            return uploadLocalDocument(file, userId, source, originalName);
        }

        // 3. Provider 解析（缺省首个 Provider；未适配返回 null → 明确报错，不静默降级）
        String code = (providerCode == null || providerCode.isBlank()) ? llmClientManager.getFirstProviderCode() : providerCode;
        if (code == null || code.isBlank()) {
            throw new IllegalStateException("没有可用的 LLM Provider，无法上传");
        }
        FilesApiClient client = filesApiClientManager.resolveClientByCode(code);
        if (client == null) {
            throw new IllegalStateException("当前 Provider（" + code + "）不支持 Files API，无法上传");
        }

        // 4. 本地副本先落盘（流拷贝；失败即返回，不做远端调用）
        String storedName;
        try {
            storedName = fileAssetStore.save(file, extensionOf(mimeType));
        } catch (IOException e) {
            throw new IllegalStateException("本地保存失败: " + e.getMessage(), e);
        }

        // 5. 远端上传（以本地副本为源，避免 MultipartFile 二次读取问题；失败清理本地副本）
        FileObject remote;
        try {
            FileUploadRequest request = FileUploadRequest.of(fileAssetStore.resolve(storedName));
            request.setFilename(originalName);
            remote = client.upload(request);
        } catch (Exception e) {
            fileAssetStore.deleteQuietly(storedName);
            throw new IllegalStateException("远端上传失败: " + e.getMessage(), e);
        }

        // 6. 写记录（失败时补偿：尽力删除远端 + 本地，避免孤儿）
        FileAsset asset = new FileAsset();
        asset.setFileId(remote.getFileId());
        asset.setProviderCode(code);
        asset.setUserId(userId);
        asset.setFilename(originalName);
        asset.setMimeType(mimeType);
        asset.setSize(file.getSize());
        asset.setLocalPath(storedName);
        asset.setStorageType("cloud");
        asset.setSource("paste".equalsIgnoreCase(source) ? "paste" : "upload");
        asset.setStatus("active");
        asset.setCreatedAt(LocalDateTime.now());
        try {
            fileAssetMapper.insert(asset);
        } catch (Exception e) {
            try {
                client.delete(remote.getFileId());
            } catch (Exception ex) {
                log.warn("补偿删除远端文件失败: fileId={} - {}", remote.getFileId(), ex.getMessage());
            }
            fileAssetStore.deleteQuietly(storedName);
            throw new IllegalStateException("资产记录保存失败: " + e.getMessage(), e);
        }

        log.info("文件资产上传成功: id={}, fileId={}, filename={}, size={}, provider={}, source={}",
                asset.getId(), asset.getFileId(), originalName, file.getSize(), code, asset.getSource());
        return toVO(asset);
    }

    // ==================== 上传（文档：本地存储，M7） ====================

    /**
     * 上传文档资产（仅本地存储，无远端调用）
     * <p>白名单校验 → 本地落盘 → 写记录（storage_type=local，file_id/provider 为 null）；
     * 写记录失败补偿删本地副本。</p>
     *
     * @param file         上传文件
     * @param userId       当前用户ID
     * @param source       来源（upload/paste）
     * @param originalName 原始文件名（调用方已保证非空）
     * @return 资产视图（含 previewUrl）
     */
    private FileAssetVO uploadLocalDocument(MultipartFile file, Long userId, String source, String originalName) {
        // 1. 扩展名白名单校验（非图片一律按文档处理；白名单外明确拒绝）
        String ext = extensionOfFilename(originalName);
        if (ext.isEmpty() || !DOC_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("不支持的文件格式：图片支持 JPEG/PNG/GIF/WebP，文档支持 "
                    + "pdf/word/excel/文本/代码等常见格式");
        }

        // 2. 本地落盘（失败即返回，不做记录）
        String storedName;
        try {
            storedName = fileAssetStore.save(file, "." + ext);
        } catch (IOException e) {
            throw new IllegalStateException("本地保存失败: " + e.getMessage(), e);
        }

        // 3. 写记录（失败补偿删本地副本）
        FileAsset asset = new FileAsset();
        asset.setFileId(null);
        asset.setProviderCode(null);
        asset.setUserId(userId);
        asset.setFilename(originalName);
        asset.setMimeType(docMimeOf(ext));
        asset.setSize(file.getSize());
        asset.setLocalPath(storedName);
        asset.setStorageType("local");
        asset.setSource("paste".equalsIgnoreCase(source) ? "paste" : "upload");
        asset.setStatus("active");
        asset.setCreatedAt(LocalDateTime.now());
        try {
            fileAssetMapper.insert(asset);
        } catch (Exception e) {
            fileAssetStore.deleteQuietly(storedName);
            throw new IllegalStateException("资产记录保存失败: " + e.getMessage(), e);
        }

        log.info("文档资产上传成功（本地存储）: id={}, filename={}, size={}, ext={}, source={}",
                asset.getId(), originalName, file.getSize(), ext, asset.getSource());
        return toVO(asset);
    }

    // ==================== 上传（服务端内部调用：本地文件/字节） ====================

    /**
     * 上传字节内容为图片资产（服务端内部调用入口，截图工具等场景使用）
     * <p>与 {@link #upload} 的图片路径同构：魔数校验 → 本地副本落盘 → 远端 Files API（永久保存）→ 写记录；
     * 任一步失败做补偿清理。{@code source} 原样记录（如 tool_capture），不做 upload/paste 收敛。</p>
     *
     * @param bytes        图片字节（JPEG/PNG/GIF/WebP）
     * @param filename     文件名（展示用；空则 unnamed）
     * @param userId       当前用户ID
     * @param providerCode 目标 Provider（可空：缺省用首个 Provider）
     * @param source       来源（tool_capture 等；空则 upload）
     * @return 资产视图（含 previewUrl）
     */
    public FileAssetVO uploadLocalBytes(byte[] bytes, String filename, Long userId,
                                        String providerCode, String source) {
        // 1. 基础校验
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("上传内容为空");
        }
        if (bytes.length > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("文件大小超过上限（最大 " + (MAX_FILE_SIZE / 1024 / 1024) + "MB）");
        }
        String originalName = (filename == null || filename.isBlank()) ? "unnamed" : filename;
        if (originalName.length() > MAX_FILENAME_LENGTH) {
            throw new IllegalArgumentException("文件名过长（最大 " + MAX_FILENAME_LENGTH + " 字符）");
        }

        // 2. 魔数检测（服务端内部入口仅支持图片：截图/导出等场景）
        String mimeType = detectImageMimeType(bytes);
        if (mimeType == null) {
            throw new IllegalArgumentException("不支持的文件格式（仅 JPEG/PNG/GIF/WebP）");
        }

        // 3. Provider 解析（与 upload 一致：缺省首个 Provider；未适配明确报错，不静默降级）
        String code = (providerCode == null || providerCode.isBlank())
                ? llmClientManager.getFirstProviderCode() : providerCode;
        if (code == null || code.isBlank()) {
            throw new IllegalStateException("没有可用的 LLM Provider，无法上传");
        }
        FilesApiClient client = filesApiClientManager.resolveClientByCode(code);
        if (client == null) {
            throw new IllegalStateException("当前 Provider（" + code + "）不支持 Files API，无法上传");
        }

        // 4. 本地副本落盘（失败即返回，不做远端调用）
        String storedName;
        try {
            storedName = fileAssetStore.saveBytes(bytes, extensionOf(mimeType));
        } catch (IOException e) {
            throw new IllegalStateException("本地保存失败: " + e.getMessage(), e);
        }

        // 5. 远端上传（以本地副本为源；失败清理本地副本）
        FileObject remote;
        try {
            FileUploadRequest request = FileUploadRequest.of(fileAssetStore.resolve(storedName));
            request.setFilename(originalName);
            remote = client.upload(request);
        } catch (Exception e) {
            fileAssetStore.deleteQuietly(storedName);
            throw new IllegalStateException("远端上传失败: " + e.getMessage(), e);
        }

        // 6. 写记录（失败时补偿：尽力删除远端 + 本地，避免孤儿）
        FileAsset asset = new FileAsset();
        asset.setFileId(remote.getFileId());
        asset.setProviderCode(code);
        asset.setUserId(userId);
        asset.setFilename(originalName);
        asset.setMimeType(mimeType);
        asset.setSize((long) bytes.length);
        asset.setLocalPath(storedName);
        asset.setStorageType("cloud");
        asset.setSource((source == null || source.isBlank()) ? "upload" : source);
        asset.setStatus("active");
        asset.setCreatedAt(LocalDateTime.now());
        try {
            fileAssetMapper.insert(asset);
        } catch (Exception e) {
            try {
                client.delete(remote.getFileId());
            } catch (Exception ex) {
                log.warn("补偿删除远端文件失败: fileId={} - {}", remote.getFileId(), ex.getMessage());
            }
            fileAssetStore.deleteQuietly(storedName);
            throw new IllegalStateException("资产记录保存失败: " + e.getMessage(), e);
        }

        log.info("本地字节上传成功（服务端内部）: id={}, fileId={}, filename={}, size={}, provider={}, source={}",
                asset.getId(), asset.getFileId(), originalName, bytes.length, code, asset.getSource());
        return toVO(asset);
    }

    /**
     * 上传本地文件为图片资产（服务端内部调用入口；薄封装：读文件后委托 {@link #uploadLocalBytes}）
     *
     * @param path         本地文件路径
     * @param filename     文件名（展示用；空则 unnamed）
     * @param userId       当前用户ID
     * @param providerCode 目标 Provider（可空：缺省用首个 Provider）
     * @param source       来源（tool_capture 等；空则 upload）
     * @return 资产视图（含 previewUrl）
     */
    public FileAssetVO uploadLocalFile(Path path, String filename, Long userId,
                                       String providerCode, String source) {
        if (path == null || !Files.exists(path)) {
            throw new IllegalArgumentException("本地文件不存在: " + path);
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new IllegalStateException("读取本地文件失败: " + e.getMessage(), e);
        }
        return uploadLocalBytes(bytes, filename, userId, providerCode, source);
    }

    // ==================== 列表 ====================

    /**
     * 列出当前用户的文件（跨会话、含未引用；"仅本人"数据隔离）
     *
     * @param userId         用户ID
     * @param afterId        游标（上一页最后一条 id，可空）
     * @param limit          条数（默认 50，上限 200）
     * @param order          排序（asc/desc，缺省 desc=最新在前）
     * @param keyword        文件名/显示名模糊筛选（可空）
     * @param status         状态筛选（可空）
     * @param storageType    存储类型筛选：cloud/local（可空）
     * @param conversationId 按引用会话筛选（可空）
     * @param withRefs       是否附带引用信息
     * @return 资产视图列表
     */
    public List<FileAssetVO> list(Long userId, Long afterId, Integer limit, String order,
                                  String keyword, String status, String storageType,
                                  Long conversationId, boolean withRefs) {
        int pageSize = (limit == null || limit <= 0) ? DEFAULT_PAGE_SIZE : Math.min(limit, MAX_PAGE_SIZE);
        boolean orderDesc = !"asc".equalsIgnoreCase(order);
        List<FileAsset> assets = fileAssetMapper.selectByUser(userId, afterId, pageSize, orderDesc,
                (keyword != null && !keyword.isBlank()) ? keyword.trim() : null,
                (status != null && !status.isBlank()) ? status.trim() : null,
                (storageType != null && !storageType.isBlank()) ? storageType.trim() : null,
                conversationId);
        List<FileAssetVO> result = new ArrayList<>(assets.size());
        for (FileAsset asset : assets) {
            FileAssetVO vo = toVO(asset);
            if (withRefs) {
                vo.setReferences(fileReferenceMapper.selectByAssetId(asset.getId()));
            }
            result.add(vo);
        }
        return result;
    }

    // ==================== 查询 / 重命名 ====================

    /**
     * 查询单个资产（含引用信息）
     */
    public FileAssetVO get(Long id, Long userId) {
        FileAsset asset = requireOwnedAsset(id, userId);
        FileAssetVO vo = toVO(asset);
        vo.setReferences(fileReferenceMapper.selectByAssetId(id));
        return vo;
    }

    /**
     * 重命名（更新本地显示名；Files API 无远端 update 端点，U 以本地实现）
     */
    public FileAssetVO rename(Long id, Long userId, String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("显示名不能为空");
        }
        String trimmed = displayName.trim();
        if (trimmed.length() > MAX_FILENAME_LENGTH) {
            throw new IllegalArgumentException("显示名过长（最大 " + MAX_FILENAME_LENGTH + " 字符）");
        }
        FileAsset asset = requireOwnedAsset(id, userId);
        fileAssetMapper.updateDisplayName(id, trimmed);
        asset.setDisplayName(trimmed);
        return toVO(asset);
    }

    // ==================== 删除 ====================

    /**
     * 删除文件资产（远端 404 视为成功；DB 改动在远端成功后执行）
     * <p>local 资产（文档）无远端，仅删引用 + 记录 + 本地副本。</p>
     */
    public void delete(Long id, Long userId) {
        FileAsset asset = requireOwnedAsset(id, userId);
        // 远端删除（仅云端资产；失败抛异常：记录保留，用户可重试）
        if (!isLocalStorage(asset)) {
            deleteRemoteOrThrow(asset);
        }
        // 删引用 + 记录 + 本地副本
        fileReferenceMapper.deleteByAssetId(id);
        fileAssetMapper.deleteById(id);
        fileAssetStore.deleteQuietly(asset.getLocalPath());
        log.info("文件资产已删除: id={}, fileId={}, storageType={}", id, asset.getFileId(), asset.getStorageType());
    }

    // ==================== 能力查询 ====================

    /**
     * 当前 Provider 是否支持 Files API（供前端能力判断）
     */
    public Map<String, Object> supported(String providerCode) {
        String code = (providerCode == null || providerCode.isBlank()) ? llmClientManager.getFirstProviderCode() : providerCode;
        boolean ok = code != null && filesApiClientManager.supportsFilesApi(code);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("supported", ok);
        result.put("providerCode", code);
        return result;
    }

    // ==================== 聊天链路辅助（M7） ====================

    /**
     * 批量查询归属当前用户的资产（保持输入顺序，去重；无效项跳过）
     * <p>供聊天发送链路按"图片/文档"分流（图片 → vision 内容块；文档 → 附件提示段）。</p>
     *
     * @param ids    资产ID列表（可重复/可空）
     * @param userId 当前用户ID
     * @return 有效资产列表（可能为空）
     */
    public List<FileAsset> getOwnedAssets(List<Long> ids, Long userId) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<FileAsset> result = new ArrayList<>(ids.size());
        Set<Long> seen = new HashSet<>();
        for (Long id : ids) {
            if (id == null || !seen.add(id)) {
                continue;
            }
            FileAsset asset = fileAssetMapper.selectById(id);
            if (asset == null || !asset.getUserId().equals(userId)) {
                continue;
            }
            result.add(asset);
        }
        return result;
    }

    /**
     * 解析资产本地副本路径（供 chat_attachment 工具 read_by_file_asset 读取）
     * <p>校验：资产存在 + status=active +（userId 非空时）归属校验 + 本地副本存在。</p>
     *
     * @param assetId 资产ID
     * @param userId  当前用户ID（工具上下文可空；非空时做归属校验）
     * @return 本地副本路径；不满足条件返回 null
     */
    public Path resolveLocalFileForTool(Long assetId, Long userId) {
        FileAsset asset = (assetId == null) ? null : fileAssetMapper.selectById(assetId);
        if (asset == null || !"active".equals(asset.getStatus())) {
            return null;
        }
        if (userId != null && !userId.equals(asset.getUserId())) {
            return null;
        }
        Path path = fileAssetStore.resolve(asset.getLocalPath());
        if (path == null || !Files.exists(path)) {
            return null;
        }
        return path;
    }

    // ==================== 引用创建（发送消息时） ====================

    /**
     * 为消息建立文件引用（当轮新上传 + 历史引用两类）
     * <p>校验：资产存在、归属当前用户、status=active；失效项跳过并记日志（不阻塞发送）。</p>
     *
     * @param fileAssetIds   资产ID列表（可重复，内部去重）
     * @param userId         当前用户ID
     * @param conversationId 会话ID
     * @param messageId      用户消息ID
     * @return 实际建立引用条数
     */
    @Transactional
    public int linkToMessage(List<Long> fileAssetIds, Long userId, Long conversationId, Long messageId) {
        if (fileAssetIds == null || fileAssetIds.isEmpty()) {
            return 0;
        }
        int linked = 0;
        Set<Long> seen = new HashSet<>();
        for (Long assetId : fileAssetIds) {
            if (assetId == null || !seen.add(assetId)) {
                continue;
            }
            FileAsset asset = fileAssetMapper.selectById(assetId);
            if (asset == null || !asset.getUserId().equals(userId)) {
                log.warn("跳过无效文件引用: assetId={}（不存在或非本人）", assetId);
                continue;
            }
            if (!"active".equals(asset.getStatus())) {
                log.warn("跳过失效文件引用: assetId={}, status={}", assetId, asset.getStatus());
                continue;
            }
            FileReference reference = new FileReference();
            reference.setFileAssetId(assetId);
            reference.setConversationId(conversationId);
            reference.setMessageId(messageId);
            reference.setCreatedAt(LocalDateTime.now());
            fileReferenceMapper.insert(reference);
            linked++;
        }
        if (linked > 0) {
            log.info("建立文件引用 {} 条: conversationId={}, messageId={}", linked, conversationId, messageId);
        }
        return linked;
    }

    // ==================== 级联清理（会话删除后调用） ====================

    /**
     * 引用归零判定与级联清理（会话删除事务提交后调用，best-effort）
     * <ul>
     *   <li>仍有其他会话引用 → 保留文件本体</li>
     *   <li>归零 → 远端删除（失败标记 orphan 待补偿）+ 本地副本 + 记录删除</li>
     * </ul>
     *
     * @param fileAssetId 受影响资产ID
     */
    public void cleanupIfUnreferenced(Long fileAssetId) {
        if (fileAssetId == null) {
            return;
        }
        FileAsset asset = fileAssetMapper.selectById(fileAssetId);
        if (asset == null) {
            return; // 已被其他路径删除
        }
        int refCount;
        try {
            refCount = fileReferenceMapper.countByAssetId(fileAssetId);
        } catch (Exception e) {
            log.warn("查询引用计数失败: assetId={} - {}", fileAssetId, e.getMessage());
            return;
        }
        if (refCount > 0) {
            log.debug("文件仍被 {} 处引用，保留: assetId={}", refCount, fileAssetId);
            return;
        }
        // local 资产（文档）：无远端，直接删记录 + 本地副本
        if (isLocalStorage(asset)) {
            fileAssetMapper.deleteById(fileAssetId);
            fileAssetStore.deleteQuietly(asset.getLocalPath());
            log.info("引用归零，本地文档已级联清理: assetId={}, filename={}", fileAssetId, asset.getFilename());
            return;
        }
        // 云端资产：引用归零 → 删远端；失败标记 orphan（保留记录与本地副本，等待补偿任务）
        try {
            deleteRemoteOrThrow(asset);
        } catch (Exception e) {
            log.warn("远端删除失败，标记 orphan 待补偿: assetId={}, fileId={} - {}",
                    fileAssetId, asset.getFileId(), e.getMessage());
            fileAssetMapper.updateStatus(fileAssetId, "orphan");
            return;
        }
        fileAssetMapper.deleteById(fileAssetId);
        fileAssetStore.deleteQuietly(asset.getLocalPath());
        log.info("引用归零，文件已级联清理: assetId={}, fileId={}", fileAssetId, asset.getFileId());
    }

    // ==================== orphan 补偿（P3） ====================

    /**
     * orphan 补偿清理任务（P3）：定时扫描 status=orphan 的资产，重试远端删除。
     * <ul>
     *   <li>成功（含 404 幂等 / Provider 不可用跳过）→ 删记录 + 本地副本；</li>
     *   <li>失败 → orphan_retry_count +1；连续失败达到 max-retries 后升级为 ERROR 告警
     *       （保留记录，下一轮继续尝试，不做自动放弃）；</li>
     *   <li>全程 try-catch（调度线程安全）；单批最多 {@link #ORPHAN_CLEANUP_BATCH_SIZE} 条
     *       （剩余条目由下一轮调度继续处理）。</li>
     * </ul>
     * 调度：默认每 30 分钟执行一次（可配置），首次延迟 2 分钟。
     */
    @Scheduled(fixedDelayString = "${file-asset.orphan-cleanup.interval-ms:1800000}",
            initialDelayString = "${file-asset.orphan-cleanup.initial-delay-ms:120000}")
    public void retryOrphanCleanup() {
        if (!orphanCleanupEnabled) {
            return;
        }
        try {
            List<FileAsset> orphans = fileAssetMapper.selectByStatus("orphan", ORPHAN_CLEANUP_BATCH_SIZE);
            if (orphans.isEmpty()) {
                return;
            }
            log.info("orphan 补偿任务开始：待处理 {} 条", orphans.size());
            int cleaned = 0;
            int retried = 0;
            for (FileAsset asset : orphans) {
                try {
                    deleteRemoteOrThrow(asset);
                    fileAssetMapper.deleteById(asset.getId());
                    fileAssetStore.deleteQuietly(asset.getLocalPath());
                    cleaned++;
                    log.info("orphan 补偿成功：assetId={}, fileId={}", asset.getId(), asset.getFileId());
                } catch (Exception e) {
                    retried++;
                    fileAssetMapper.incrementOrphanRetry(asset.getId());
                    int retries = (asset.getOrphanRetryCount() == null ? 0 : asset.getOrphanRetryCount()) + 1;
                    if (retries >= orphanCleanupMaxRetries) {
                        log.error("orphan 补偿连续失败 {} 次（≥{}），保留记录待人工处理：assetId={}, fileId={}, 原因={}",
                                retries, orphanCleanupMaxRetries, asset.getId(), asset.getFileId(), e.getMessage());
                    } else {
                        log.warn("orphan 补偿失败（第 {} 次）：assetId={}, fileId={} - {}",
                                retries, asset.getId(), asset.getFileId(), e.getMessage());
                    }
                }
            }
            log.info("orphan 补偿任务完成：成功 {} 条，失败 {} 条", cleaned, retried);
        } catch (Exception e) {
            log.warn("orphan 补偿任务异常（不影响主流程）: {}", e.getMessage());
        }
    }

    // ==================== 预览 ====================

    /**
     * 加载本地副本（预览端点用）
     *
     * @param id     资产ID
     * @param userId 当前用户ID
     * @return 预览数据（路径 + MIME）
     */
    public PreviewData loadPreview(Long id, Long userId) {
        FileAsset asset = requireOwnedAsset(id, userId);
        Path path = fileAssetStore.resolve(asset.getLocalPath());
        if (path == null || !Files.exists(path)) {
            throw new NoSuchElementException("本地副本不存在（文件可能已被清理）");
        }
        String mimeType = (asset.getMimeType() != null && !asset.getMimeType().isBlank())
                ? asset.getMimeType() : "application/octet-stream";
        return new PreviewData(path, mimeType);
    }

    /** 预览数据（本地副本路径 + MIME） */
    public record PreviewData(Path path, String mimeType) {
    }

    // ==================== 内部工具 ====================

    /**
     * 校验资产存在且归属当前用户（"仅本人"隔离）
     */
    private FileAsset requireOwnedAsset(Long id, Long userId) {
        FileAsset asset = (id == null) ? null : fileAssetMapper.selectById(id);
        if (asset == null || !asset.getUserId().equals(userId)) {
            throw new NoSuchElementException("文件不存在或无权访问");
        }
        return asset;
    }

    /**
     * 远端删除（失败向上抛 FilesApiException；Provider 不可用则跳过并记警告）
     */
    private void deleteRemoteOrThrow(FileAsset asset) {
        FilesApiClient client = filesApiClientManager.resolveClientByCode(asset.getProviderCode());
        if (client == null) {
            // Provider 已不可用：无法删除远端，跳过（避免记录永远删不掉）
            log.warn("Provider [{}] 不可用，跳过远端删除: assetId={}, fileId={}",
                    asset.getProviderCode(), asset.getId(), asset.getFileId());
            return;
        }
        client.delete(asset.getFileId());
    }

    /**
     * 魔数检测图片类型（仅 JPEG/PNG/GIF/WebP）
     *
     * @param file 上传文件
     * @return MIME 或 null（非支持格式/读取失败）
     */
    private String detectImageMimeType(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            byte[] h = new byte[12];
            int n = in.readNBytes(h, 0, 12);
            return detectImageMimeType(h, n);
        } catch (IOException e) {
            log.warn("读取上传文件头部失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 魔数检测图片类型（字节数组版：截图工具等服务端内部调用复用）
     *
     * @param bytes 图片字节
     * @return MIME 或 null（非支持格式）
     */
    private static String detectImageMimeType(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        int n = Math.min(bytes.length, 12);
        byte[] h = new byte[12];
        System.arraycopy(bytes, 0, h, 0, n);
        return detectImageMimeType(h, n);
    }

    /**
     * 魔数检测（头部字节 + 有效长度）
     */
    private static String detectImageMimeType(byte[] h, int n) {
        if (n >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (n >= 4 && (h[0] & 0xFF) == 0x89 && h[1] == 0x50 && h[2] == 0x4E && h[3] == 0x47) {
            return "image/png";
        }
        if (n >= 4 && h[0] == 0x47 && h[1] == 0x49 && h[2] == 0x46 && h[3] == 0x38) {
            return "image/gif";
        }
        if (n >= 12 && h[0] == 0x52 && h[1] == 0x49 && h[2] == 0x46 && h[3] == 0x46
                && h[8] == 0x57 && h[9] == 0x45 && h[10] == 0x42 && h[11] == 0x50) {
            return "image/webp";
        }
        return null;
    }

    /**
     * MIME → 扩展名
     */
    private String extensionOf(String mimeType) {
        return switch (mimeType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            default -> "";
        };
    }

    /**
     * 文件名 → 扩展名（小写、不含点；无扩展名返回空串）
     */
    private static String extensionOfFilename(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase();
    }

    /**
     * 文档扩展名 → MIME（常见类别精确映射，其余归 text/plain）
     */
    private static String docMimeOf(String ext) {
        return switch (ext) {
            case "pdf" -> "application/pdf";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls" -> "application/vnd.ms-excel";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "csv" -> "text/csv";
            default -> "text/plain";
        };
    }

    /**
     * 是否本地存储资产（local；null 视为 cloud——老数据兼容）
     */
    private static boolean isLocalStorage(FileAsset asset) {
        return "local".equals(asset.getStorageType());
    }

    /**
     * 实体 → 视图（含 previewUrl 组装）
     */
    private FileAssetVO toVO(FileAsset asset) {
        FileAssetVO vo = new FileAssetVO();
        vo.setId(asset.getId());
        vo.setFileId(asset.getFileId());
        vo.setFilename(asset.getFilename());
        vo.setDisplayName(asset.getDisplayName());
        vo.setMimeType(asset.getMimeType());
        vo.setStorageType(asset.getStorageType());
        vo.setSize(asset.getSize());
        vo.setSource(asset.getSource());
        vo.setStatus(asset.getStatus());
        vo.setProviderCode(asset.getProviderCode());
        vo.setCreatedAt(asset.getCreatedAt());
        vo.setPreviewUrl("/api/files/" + asset.getId() + "/preview");
        return vo;
    }
}
