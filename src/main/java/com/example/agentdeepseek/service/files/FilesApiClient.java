package com.example.agentdeepseek.service.files;

import com.example.agentdeepseek.service.files.model.FileObject;
import com.example.agentdeepseek.service.files.model.FilePage;
import com.example.agentdeepseek.service.files.model.FileUploadRequest;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Files API 客户端统一接口
 * <p>
 * 所有支持 Files API 的 LLM Provider（DeepSeek / OpenAI / Anthropic 等）实现此接口，
 * 屏蔽各平台文件接口的差异（端点路径、字段命名、认证方式）。
 * </p>
 *
 * <h3>职责边界</h3>
 * <ul>
 *   <li>上传 / 列表 / 查询 / 删除 四项基本操作（同步阻塞语义，与 LLMClient.blockingChat 风格一致）</li>
 *   <li>响应字段归一化为 {@link FileObject} / {@link FilePage}</li>
 *   <li>错误统一转换为 {@link FilesApiException}</li>
 * </ul>
 *
 * <h3>多厂商扩展指引</h3>
 * <ol>
 *   <li>新增厂商：实现本接口（或继承 AbstractFilesApiClient），并在
 *       {@code FilesApiClientManager} 的 switch 中注册对应的 requestTemplate；</li>
 *   <li>字段差异处理：一律在客户端内归一到 {@link FileObject}
 *       （例：Anthropic 的 size_bytes → bytes、RFC3339 时间字符串 → epoch 秒）；</li>
 *   <li>认证差异：无需处理——由 {@code LLMWebClientManager} 按 template 自动生成认证头。</li>
 * </ol>
 */
public interface FilesApiClient {

    /**
     * 获取 Provider 编码（如 "deepseek"）
     */
    String getProviderCode();

    /**
     * 获取当前使用的 WebClient 实例
     * <p>由 {@code LLMWebClientManager.getOrCreate()} 提供，已内置认证头、连接池、并发限流与 429/5xx 重试。</p>
     */
    WebClient getWebClient();

    /**
     * 上传文件
     *
     * @param request 上传请求（文件来源 + 文件名 + 可选过期秒数；expiresAfterSeconds=0 表示永久保存）
     * @return 远端文件对象（归一化）
     * @throws FilesApiException 远端错误 / 网络错误
     */
    FileObject upload(FileUploadRequest request);

    /**
     * 分页列出文件
     *
     * @param afterCursor 游标（上一页的 lastId），可空
     * @param limit       条数限制（1~1000，超出自动收敛）
     * @param order       排序（asc / desc），可空时用远端默认
     * @return 文件分页（归一化）
     * @throws FilesApiException 远端错误 / 网络错误
     */
    FilePage list(String afterCursor, int limit, String order);

    /**
     * 查询单个文件信息
     *
     * @param fileId 远端文件 ID
     * @return 文件对象
     * @throws FilesApiException 文件不存在（404）或远端错误
     */
    FileObject retrieve(String fileId);

    /**
     * 删除文件
     *
     * @param fileId 远端文件 ID
     * @return true = 删除成功（远端 404 视为已删除，同样返回 true）
     * @throws FilesApiException 远端错误（非 404）
     */
    boolean delete(String fileId);

    /**
     * 能力声明：是否支持视觉（图片理解）。
     * 供上层注入 / 降级判断；默认 true，特殊厂商可覆盖。
     */
    default boolean supportsVision() {
        return true;
    }
}
