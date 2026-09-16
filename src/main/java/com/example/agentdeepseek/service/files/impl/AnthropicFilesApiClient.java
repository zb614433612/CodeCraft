package com.example.agentdeepseek.service.files.impl;

import com.example.agentdeepseek.service.files.AbstractFilesApiClient;
import com.example.agentdeepseek.service.files.FilesApiException;
import com.example.agentdeepseek.service.files.model.FileObject;
import com.example.agentdeepseek.service.files.model.FilePage;
import com.example.agentdeepseek.service.files.model.FileUploadRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Anthropic Files API 客户端（Anthropic 兼容风格，P3 收尾）
 *
 * <h3>端点契约（api-docs.deepseek.com / Anthropic Files API beta）</h3>
 * <ul>
 *   <li>上传：POST /v1/files（multipart：file）</li>
 *   <li>列表：GET /v1/files?after_id&amp;limit</li>
 *   <li>查询：GET /v1/files/{file_id}</li>
 *   <li>删除：DELETE /v1/files/{file_id}（404 视为已删除）</li>
 * </ul>
 *
 * <h3>与 OpenAI 兼容风格（{@link DeepSeekFilesApiClient}）的差异（已在本类内屏蔽）</h3>
 * <ul>
 *   <li>路径：{@code /v1/files}（baseUrl 含 {@code /anthropic} 前缀，如 DeepSeek 兼容
 *       {@code https://api.deepseek.com/anthropic} → 完整路径 {@code /anthropic/v1/files}）</li>
 *   <li>认证：{@code x-api-key} 头（由 {@code LLMWebClientManager} 按 template=anthropic 处理，
 *       无 Bearer 前缀）+ 每次请求附 {@code anthropic-beta: files-api-2025-04-14}（本类添加）</li>
 *   <li>列表分页：{@code after_id}（本类映射；本系统仅顺序翻页，不使用 before_id）</li>
 *   <li>列表过滤：不支持 order / purpose（参数忽略）</li>
 *   <li>字段差异：{@code size_bytes}→bytes、RFC3339 时间→epoch 秒、{@code type:"file"}——
 *       均由 {@link AbstractFilesApiClient} 的归一化解析兼容</li>
 *   <li>删除响应：{@code {id, type:"file_deleted"}}（HTTP 2xx 即视为成功；404 幂等）</li>
 * </ul>
 *
 * <h3>路由约定</h3>
 * 由 {@code FilesApiClientManager} 按 Provider 的 requestTemplate=anthropic 分发；
 * Provider 的 baseUrl 应为 Anthropic 风格根地址（如 {@code https://api.deepseek.com/anthropic}
 * 或 {@code https://api.anthropic.com}），与 {@code AnthropicClient}（chat）的端点约定一致。
 */
@Slf4j
public class AnthropicFilesApiClient extends AbstractFilesApiClient {

    /** Files API 端点（相对 baseUrl 的 Anthropic 风格路径） */
    private static final String FILES_ENDPOINT = "/v1/files";

    /** Anthropic Files API beta 头（file_id 引用与文件端点均需携带） */
    private static final String ANTHROPIC_BETA = "files-api-2025-04-14";

    public AnthropicFilesApiClient(WebClient webClient, ObjectMapper objectMapper, String providerCode) {
        super(webClient, objectMapper, providerCode);
    }

    @Override
    public FileObject upload(FileUploadRequest request) {
        // Anthropic 风格上传：multipart 仅 file 字段（无 purpose / expires_after）
        MultiValueMap<String, HttpEntity<?>> parts = buildUploadMultipart(request, null, false);
        try {
            String body = webClient.post()
                    .uri(FILES_ENDPOINT)
                    .header("anthropic-beta", ANTHROPIC_BETA)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(parts))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(UPLOAD_TIMEOUT);
            FileObject file = parseFileObject(readJsonTree(body));
            log.info("[{}] 文件上传成功（Anthropic 风格）: fileId={}, filename={}, bytes={}",
                    providerCode, file.getFileId(), file.getFilename(), file.getBytes());
            return file;
        } catch (WebClientResponseException e) {
            throw toFilesApiException(e, "上传文件");
        } catch (FilesApiException e) {
            throw e;
        } catch (Exception e) {
            throw new FilesApiException(0, "上传文件失败: " + e.getMessage(), false, e);
        }
    }

    @Override
    public FilePage list(String afterCursor, int limit, String order) {
        // limit 收敛到 1~1000（Anthropic 文档限制），未指定时默认 100
        int effectiveLimit = Math.min(limit <= 0 ? 100 : limit, 1000);
        // order 参数 Anthropic 风格不支持：忽略（接口保留兼容）
        try {
            String body = webClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path(FILES_ENDPOINT).queryParam("limit", effectiveLimit);
                        if (afterCursor != null && !afterCursor.isBlank()) {
                            uriBuilder.queryParam("after_id", afterCursor);
                        }
                        return uriBuilder.build();
                    })
                    .header("anthropic-beta", ANTHROPIC_BETA)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(DEFAULT_TIMEOUT);
            return parseFilePage(readJsonTree(body));
        } catch (WebClientResponseException e) {
            throw toFilesApiException(e, "列出文件");
        } catch (FilesApiException e) {
            throw e;
        } catch (Exception e) {
            throw new FilesApiException(0, "列出文件失败: " + e.getMessage(), false, e);
        }
    }

    @Override
    public FileObject retrieve(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            throw new FilesApiException(0, "fileId 不能为空", false);
        }
        try {
            String body = webClient.get()
                    .uri(FILES_ENDPOINT + "/{fileId}", fileId)
                    .header("anthropic-beta", ANTHROPIC_BETA)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(DEFAULT_TIMEOUT);
            return parseFileObject(readJsonTree(body));
        } catch (WebClientResponseException e) {
            // 404 / 400(does-not-exist) 均由 toFilesApiException 统一归一为 404 语义
            throw toFilesApiException(e, "查询文件");
        } catch (FilesApiException e) {
            throw e;
        } catch (Exception e) {
            throw new FilesApiException(0, "查询文件失败: " + e.getMessage(), false, e);
        }
    }

    @Override
    public boolean delete(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            throw new FilesApiException(0, "fileId 不能为空", false);
        }
        try {
            String body = webClient.delete()
                    .uri(FILES_ENDPOINT + "/{fileId}", fileId)
                    .header("anthropic-beta", ANTHROPIC_BETA)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(DEFAULT_TIMEOUT);
            // Anthropic 风格删除响应：{id, type:"file_deleted"}——HTTP 2xx 即成功（无 deleted:false 语义）
            JsonNode node = null;
            try {
                node = readJsonTree(body);
            } catch (FilesApiException ignored) {
                // 空响应体/非 JSON 场景不视为失败（状态码已成功）
            }
            String type = (node == null) ? null : node.path("type").asText(null);
            log.info("[{}] 文件已删除（Anthropic 风格）: fileId={}, type={}", providerCode, fileId, type);
            return true;
        } catch (WebClientResponseException e) {
            if (isFileNotFound(e)) {
                // 目标不存在（404 / not found）视为已删除（幂等语义）
                log.info("[{}] 删除目标不存在（视为已删除）: fileId={}", providerCode, fileId);
                return true;
            }
            throw toFilesApiException(e, "删除文件");
        } catch (FilesApiException e) {
            throw e;
        } catch (Exception e) {
            throw new FilesApiException(0, "删除文件失败: " + e.getMessage(), false, e);
        }
    }
}
