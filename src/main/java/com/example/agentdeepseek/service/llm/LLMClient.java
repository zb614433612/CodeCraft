package com.example.agentdeepseek.service.llm;

import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * LLM 客户端统一接口
 * <p>
 * 所有 LLM Provider（DeepSeek / OpenAI / Anthropic / Ollama 等）
 * 都必须实现此接口，屏蔽不同平台的 API 差异。
 * </p>
 *
 * <h3>职责边界</h3>
 * <ul>
 *   <li>构建 Provider 特有的请求体格式（thinking 参数、max_tokens 等）</li>
 *   <li>提供流式和非流式两种调用方式</li>
 *   <li>解析 Provider 特有的响应格式（SSE 数据提取、错误处理）</li>
 * </ul>
 */
public interface LLMClient {

    /**
     * 获取 Provider 编码（如 "deepseek"、"openai"）
     */
    String getProviderCode();

    /**
     * 获取当前使用的 WebClient 实例
     */
    WebClient getWebClient();

    /**
     * 构建 API 请求体
     *
     * @param messages      消息列表（含 system/user/assistant/tool 角色）
     * @param model         模型名称
     * @param temperature   采样温度（0-2），可为 null
     * @param thinkingMode  思考模式（non-thinking / thinking / thinking_max），可为 null
     * @param stream        是否流式返回
     * @param tools         工具定义列表，可为 null 或空
     * @return Provider 格式的完整请求体 Map
     */
    Map<String, Object> buildRequestBody(
            List<Map<String, Object>> messages,
            String model,
            Double temperature,
            String thinkingMode,
            boolean stream,
            List<Map<String, Object>> tools);

    /**
     * 流式聊天（SSE）
     *
     * @param requestBody 由 {@link #buildRequestBody} 构建的请求体
     * @return SSE 事件流（每块为一个完整的 data: 行）
     */
    Flux<String> streamChat(Map<String, Object> requestBody);

    /**
     * 阻塞式聊天（非流式）
     *
     * @param requestBody 由 {@link #buildRequestBody} 构建的请求体
     * @param timeout     超时时间
     * @return 原始 JSON 响应字符串
     */
    String blockingChat(Map<String, Object> requestBody, Duration timeout);

    /**
     * 从 SSE 流式块中提取内容增量
     *
     * @param sseChunk 原始 SSE 数据块（不含 "data: " 前缀）
     * @return 内容文本增量；非内容块返回 null
     */
    String extractContentFromStreamChunk(String sseChunk);

    /**
     * 从 SSE 流式块中提取思考过程增量（如 DeepSeek 的 reasoning_content）
     *
     * @param sseChunk 原始 SSE 数据块（不含 "data: " 前缀）
     * @return 思考过程文本增量；无思考内容返回 null
     */
    String extractReasoningFromStreamChunk(String sseChunk);

    /**
     * 获取 API 端点路径（如 "/v1/chat/completions"）
     */
    default String getChatEndpoint() {
        return "/v1/chat/completions";
    }

    /**
     * 从阻塞式响应中提取最终内容
     *
     * @param responseBody 完整的 JSON 响应字符串
     * @return 模型输出的文本内容
     */
    String extractContentFromBlockingResponse(String responseBody);
}
