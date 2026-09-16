package com.example.agentdeepseek.service.files;

import com.example.agentdeepseek.service.files.model.FileObject;
import com.example.agentdeepseek.service.files.model.FilePage;
import com.example.agentdeepseek.service.files.model.FileUploadRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;

/**
 * FilesApiClient 抽象基类
 * <p>
 * 提供跨厂商共享逻辑，子类只需关注端点路径与少量特有参数：
 * <ul>
 *   <li>multipart 请求体构建（file + purpose + 可选 expires_after）</li>
 *   <li>响应归一化：{@link FileObject} / {@link FilePage}（字段别名与时间格式兼容）</li>
 *   <li>错误统一转换：WebClientResponseException → {@link FilesApiException}（401 特殊提示）</li>
 *   <li>本地文件字节读取（供降级路径 / 校验场景复用）</li>
 * </ul>
 */
@Slf4j
public abstract class AbstractFilesApiClient implements FilesApiClient {

    protected final WebClient webClient;
    protected final ObjectMapper objectMapper;
    protected final String providerCode;

    /** 默认请求超时（列表 / 查询 / 删除等轻量操作） */
    protected static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    /**
     * 上传请求 block 等待上限（兜底防线程永久挂起）。
     * 注意：实际网络超时还受 WebClient 自身 responseTimeout 约束（LLMWebClientManager 既有 120s 配置）。
     */
    protected static final Duration UPLOAD_TIMEOUT = Duration.ofMinutes(10);

    protected AbstractFilesApiClient(WebClient webClient, ObjectMapper objectMapper, String providerCode) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.providerCode = providerCode;
    }

    @Override
    public String getProviderCode() {
        return this.providerCode;
    }

    @Override
    public WebClient getWebClient() {
        return this.webClient;
    }

    // ==================== 共享工具：请求构建 ====================

    /**
     * 构建上传 multipart 请求体（OpenAI 兼容风格：file + purpose + 可选 expires_after）
     *
     * @param request 上传请求（file / localPath 二选一）
     * @param purpose 用途（OpenAI 兼容风格固定 user_data；空则不带该部件）
     * @return multipart 部件
     */
    protected MultiValueMap<String, HttpEntity<?>> buildUploadMultipart(FileUploadRequest request, String purpose) {
        return buildUploadMultipart(request, purpose, true);
    }

    /**
     * 构建上传 multipart 请求体（全参版本：Anthropic 风格无 purpose / expires_after）
     *
     * @param request             上传请求（file / localPath 二选一）
     * @param purpose             用途（null/空 = 不带 purpose 部件）
     * @param includeExpiresAfter 是否允许携带 expires_after 部件（Anthropic 风格传 false）
     * @return multipart 部件
     */
    protected MultiValueMap<String, HttpEntity<?>> buildUploadMultipart(FileUploadRequest request,
                                                                       String purpose,
                                                                       boolean includeExpiresAfter) {
        Resource resource = toResource(request);
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", resource).filename(request.resolveFilename());
        if (purpose != null && !purpose.isBlank()) {
            builder.part("purpose", purpose);
        }
        // expires_after 仅在显式传正数时携带；业务默认 0 = 不传 = 永久保存
        if (includeExpiresAfter && request.getExpiresAfterSeconds() > 0) {
            builder.part("expires_after[anchor]", "created_at");
            builder.part("expires_after[seconds]", String.valueOf(request.getExpiresAfterSeconds()));
        }
        return builder.build();
    }

    /**
     * 将上传请求转换为可流式发送的 Resource（不整体载入内存）
     */
    private Resource toResource(FileUploadRequest request) {
        if (request.getFile() != null && !request.getFile().isEmpty()) {
            return request.getFile().getResource();
        }
        if (request.getLocalPath() != null) {
            return new FileSystemResource(request.getLocalPath());
        }
        throw new FilesApiException(0, "上传请求缺少文件来源（file / localPath 至少提供一个）", false);
    }

    /**
     * 读取上传请求对应的文件字节（供未来 base64 降级 / 校验场景复用）
     *
     * @param request 上传请求
     * @return 文件完整字节
     * @throws FilesApiException 缺少来源或读取失败
     */
    protected byte[] readFileBytes(FileUploadRequest request) {
        try {
            if (request.getFile() != null && !request.getFile().isEmpty()) {
                return request.getFile().getBytes();
            }
            if (request.getLocalPath() != null) {
                return Files.readAllBytes(request.getLocalPath());
            }
            throw new FilesApiException(0, "上传请求缺少文件来源（file / localPath 至少提供一个）", false);
        } catch (IOException e) {
            throw new FilesApiException(0, "读取本地文件失败: " + e.getMessage(), false, e);
        }
    }

    // ==================== 共享工具：响应归一化 ====================

    /**
     * 解析 JSON 响应体为 JsonNode
     */
    protected JsonNode readJsonTree(String body) {
        if (body == null || body.isBlank()) {
            throw new FilesApiException(0, "远端响应为空", false);
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node == null) {
                throw new FilesApiException(0, "远端响应解析为空", false);
            }
            return node;
        } catch (FilesApiException e) {
            throw e;
        } catch (Exception e) {
            throw new FilesApiException(0, "远端响应解析失败: " + e.getMessage(), false, e);
        }
    }

    /**
     * 解析单个文件对象（字段别名兼容：id/file_id、bytes/size_bytes；时间多格式归一）
     */
    protected FileObject parseFileObject(JsonNode node) {
        if (node == null || node.isNull()) {
            throw new FilesApiException(0, "远端响应缺少文件对象", false);
        }
        FileObject file = new FileObject();
        file.setFileId(firstText(node, "id", "file_id"));
        file.setFilename(firstText(node, "filename", "name"));
        file.setBytes(firstLong(node, "bytes", "size_bytes"));
        file.setCreatedAtEpoch(parseEpochSeconds(node.get("created_at")));
        file.setPurpose(firstText(node, "purpose"));
        file.setExpiresAtEpoch(parseEpochSeconds(node.get("expires_at")));
        return file;
    }

    /**
     * 解析文件分页对象（data / first_id / last_id / has_more）
     */
    protected FilePage parseFilePage(JsonNode node) {
        FilePage page = new FilePage();
        page.setData(new ArrayList<>());
        if (node == null || node.isNull()) {
            return page;
        }
        JsonNode data = node.path("data");
        if (data.isArray()) {
            for (JsonNode item : data) {
                page.getData().add(parseFileObject(item));
            }
        }
        page.setFirstId(firstText(node, "first_id", "firstId"));
        page.setLastId(firstText(node, "last_id", "lastId"));
        JsonNode hasMore = node.get("has_more");
        page.setHasMore(hasMore != null && hasMore.asBoolean(false));
        return page;
    }

    /**
     * 归一化时间字段为 Unix 秒：兼容 数字（Unix 秒）/ RFC3339 字符串 / 空值
     */
    protected Long parseEpochSeconds(JsonNode valueNode) {
        if (valueNode == null || valueNode.isNull() || valueNode.isMissingNode()) {
            return null;
        }
        if (valueNode.isNumber()) {
            return valueNode.asLong();
        }
        String text = valueNode.asText(null);
        if (text == null || text.isBlank()) {
            return null;
        }
        // 纯数字字符串（Unix 秒）
        if (text.chars().allMatch(Character::isDigit)) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        // RFC3339（如 Anthropic 风格的 "2026-09-16T10:00:00Z" / "+08:00" 偏移）
        try {
            return Instant.parse(text).getEpochSecond();
        } catch (Exception ignored) {
            try {
                return OffsetDateTime.parse(text).toEpochSecond();
            } catch (Exception ex) {
                log.debug("[{}] 无法解析时间字段: {}", providerCode, text);
                return null;
            }
        }
    }

    /**
     * 取第一个非空文本字段（字段别名兼容）
     */
    protected String firstText(JsonNode node, String... fieldNames) {
        for (String name : fieldNames) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull()) {
                String text = value.asText();
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    /**
     * 取第一个可解析为 long 的字段（字段别名兼容）
     */
    protected Long firstLong(JsonNode node, String... fieldNames) {
        for (String name : fieldNames) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull()) {
                if (value.isNumber()) {
                    return value.asLong();
                }
                try {
                    return Long.parseLong(value.asText());
                } catch (NumberFormatException ignored) {
                    // 尝试下一个字段
                }
            }
        }
        return null;
    }

    // ==================== 共享工具：错误转换 ====================

    /**
     * 统一错误转换：WebClientResponseException → FilesApiException
     * <p>401/403 给出 "API Key 无效或未配置" 的明确提示；429/5xx 标记 retryable。</p>
     */
    protected FilesApiException toFilesApiException(WebClientResponseException e, String action) {
        int status = e.getStatusCode().value();
        String body = e.getResponseBodyAsString();
        String summary = (body != null && body.length() > 300) ? body.substring(0, 300) + "..." : body;
        boolean retryable = status == 429 || status >= 500;

        // 统一"文件不存在"语义为 404：DeepSeek 对不存在文件的 retrieve/delete 返回 400 + "does not exist"，
        // 归一化后上层可直接以 code==404 判定（屏蔽厂商差异）
        if (isFileNotFound(e)) {
            log.debug("[{}] {}目标不存在 status={}", providerCode, action, status);
            return new FilesApiException(404, action + "失败：文件不存在或已被删除", false, e);
        }

        log.warn("[{}] {}失败 status={}: {}", providerCode, action, status, summary);
        if (status == 401 || status == 403) {
            return new FilesApiException(status, action + "失败：API Key 无效或未配置（HTTP " + status + "）", false, e);
        }
        String detail = (summary == null || summary.isBlank()) ? "无响应体" : summary;
        return new FilesApiException(status, action + "失败（HTTP " + status + "）：" + detail, retryable, e);
    }

    /**
     * 判定"文件不存在"：404，或 400 且响应体提示 does not exist / not found / invalid file_id
     * <ul>
     *   <li>DeepSeek OpenAI 风格：对不存在文件返回 400 invalid_request_error（"does not exist"）；</li>
     *   <li>Anthropic 兼容风格：对不存在/非法 file_id 返回 400（"Invalid file_id"）——
     *       对本系统合法来源（数据库记录回传）的 id 视同"目标不存在"，保证删除幂等与 orphan 补偿可收敛。</li>
     * </ul>
     */
    protected boolean isFileNotFound(WebClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == 404) {
            return true;
        }
        if (status == 400) {
            String body = e.getResponseBodyAsString();
            if (body != null) {
                String lower = body.toLowerCase();
                return lower.contains("does not exist") || lower.contains("not found")
                        || lower.contains("invalid file_id");
            }
        }
        return false;
    }
}
