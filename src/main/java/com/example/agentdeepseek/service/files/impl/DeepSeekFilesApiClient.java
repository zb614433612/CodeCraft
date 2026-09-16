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
 * DeepSeek Files API 客户端（OpenAI 兼容风格）
 *
 * <h3>端点契约（api-docs.deepseek.com，2026-09-15 抓取）</h3>
 * <ul>
 *   <li>上传：POST /files（multipart：file + purpose=user_data [+ expires_after]）</li>
 *   <li>列表：GET /files?after&amp;limit&amp;order</li>
 *   <li>查询：GET /files/{file_id}</li>
 *   <li>删除：DELETE /files/{file_id}（404 视为已删除）</li>
 * </ul>
 *
 * <h3>限制</h3>
 * <ul>
 *   <li>单文件 ≤ 64 MiB；文件名 ≤ 512 字符；上传需 10 分钟内完成</li>
 *   <li>purpose 固定 user_data；expires_after[seconds] 范围 3600~2592000（不传 = 永久）</li>
 * </ul>
 */
@Slf4j
public class DeepSeekFilesApiClient extends AbstractFilesApiClient {

    /** Files API 端点（OpenAI 兼容路径，baseUrl 由 WebClient 承载） */
    private static final String FILES_ENDPOINT = "/files";

    /** purpose 固定值（DeepSeek 仅支持 user_data） */
    private static final String PURPOSE_USER_DATA = "user_data";

    public DeepSeekFilesApiClient(WebClient webClient, ObjectMapper objectMapper, String providerCode) {
        super(webClient, objectMapper, providerCode);
    }

    @Override
    public FileObject upload(FileUploadRequest request) {
        MultiValueMap<String, HttpEntity<?>> parts = buildUploadMultipart(request, PURPOSE_USER_DATA);
        try {
            String body = webClient.post()
                    .uri(FILES_ENDPOINT)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(parts))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(UPLOAD_TIMEOUT);
            FileObject file = parseFileObject(readJsonTree(body));
            log.info("[{}] 文件上传成功: fileId={}, filename={}, bytes={}",
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
        // limit 收敛到 1~1000（DeepSeek 文档限制），未指定时默认 100
        int effectiveLimit = Math.min(limit <= 0 ? 100 : limit, 1000);
        String effectiveOrder = (order == null || order.isBlank()) ? null : order;
        try {
            String body = webClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path(FILES_ENDPOINT).queryParam("limit", effectiveLimit);
                        if (afterCursor != null && !afterCursor.isBlank()) {
                            uriBuilder.queryParam("after", afterCursor);
                        }
                        if (effectiveOrder != null) {
                            uriBuilder.queryParam("order", effectiveOrder);
                        }
                        return uriBuilder.build();
                    })
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
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(DEFAULT_TIMEOUT);
            JsonNode node = readJsonTree(body);
            JsonNode deleted = node.get("deleted");
            if (deleted != null && !deleted.asBoolean(true)) {
                // DeepSeek 对不存在/不属于当前账号的文件返回 200 + deleted:false（未删除到目标），
                // 归一为幂等语义：目标已不存在 → 视为已删除
                log.info("[{}] 删除目标不存在（deleted=false，视为已删除）: fileId={}", providerCode, fileId);
                return true;
            }
            log.info("[{}] 文件已删除: fileId={}", providerCode, fileId);
            return true;
        } catch (WebClientResponseException e) {
            if (isFileNotFound(e)) {
                // 目标不存在（404 / 400 does-not-exist）视为已删除（幂等语义）
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
