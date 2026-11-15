package com.example.agentdeepseek.service.llm;

import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * LLM 客户端统一接口 —— <b>新增 Provider 适配的【唯一总入口】（适配清单）</b>
 *
 * <p>所有 LLM Provider（DeepSeek / OpenAI / Anthropic / Ollama …）都必须实现此接口。
 * 适配一个新 Provider 时，<b>请按 A → B → C → D 顺序过完下面四组清单</b>：</p>
 * <ul>
 *   <li><b>A 组（必写）</b>——不做就跑不通；</li>
 *   <li><b>B 组（能力开关）</b>——不做<b>不会报错</b>，只会「静默缺功能」；</li>
 *   <li><b>C 组（散点速查）</b>——真正要改的代码分散在另外 4 个类里，只改适配器等于没做；</li>
 *   <li><b>D 组（前端与测试）</b>——决定用户在界面上看不看得到这个能力。</li>
 * </ul>
 *
 * <p><b>一句话判定标准</b>：如果新 Provider「对话能跑通，但成本看板没有金额 / 发图报 400 /
 * 余额按钮灰掉 / 联网只走 Bing」——那不是 Bug，而是 B 组漏项。</p>
 *
 * <h3>A. 必写代码（不做就跑不通）</h3>
 * <table border="1">
 *   <caption>必写项</caption>
 *   <tr><th>#</th><th>做什么</th><th>说明</th></tr>
 *   <tr>
 *     <td>A1</td>
 *     <td>新建 {@code XxxClient extends AbstractLLMClient}</td>
 *     <td>不建议直接实现本接口：基类已实现流式/阻塞发送、超时、SSE 拆帧、错误体提取</td>
 *   </tr>
 *   <tr>
 *     <td>A2</td>
 *     <td>实现 {@code buildThinkingParams(String thinkingMode)}</td>
 *     <td><b>唯一的 abstract 方法</b>。入参只会是 {@code AbstractLLMClient.normalizeThinkingMode}
 *         归一化后的 5 档（none/low/medium/high/max）或空串（auto）。
 *         <b>不要</b>在这里比较 {@code thinking_low / non-thinking / disabled} 这类原始字符串——历史教训：
 *         新增 {@code thinking_low} 时 MiniMax 适配器因为 switch 的 default 分支把思考直接关掉了</td>
 *   </tr>
 *   <tr>
 *     <td>A3</td>
 *     <td>实现 3 个响应解析方法</td>
 *     <td>{@code extractContentFromStreamChunk}（正文增量）、
 *         {@code extractReasoningFromStreamChunk}（思考链增量，无则返回 null）、
 *         {@code extractContentFromBlockingResponse}（非流式正文）</td>
 *   </tr>
 *   <tr>
 *     <td>A4</td>
 *     <td>确认工具调用的<b>增量结构</b></td>
 *     <td>{@code DeepSeekServiceImpl} 固定按 OpenAI 的 {@code choices[].delta.tool_calls}
 *         （{@code index / id / function.arguments} 增量拼接）解析工具调用。
 *         <b>协议不同的适配器必须在内部转换成该结构</b>，否则症状是「能聊天、一调工具就断」。
 *         另：{@code assistant.tool_calls} 与 {@code role=tool} 消息必须严格一一配对</td>
 *   </tr>
 *   <tr>
 *     <td>A5</td>
 *     <td>在 {@code ProviderRegistry} 登记档案</td>
 *     <td><b>⭐ 这是 T12 之后唯一的注册点，只改这一处。</b>
 *         <ol>
 *           <li>在 {@code ProviderRegistry} 新增/修改一个 {@code ProviderProfile}，
 *               设置 {@code template}（主名）、{@code aliases}（历史别名 / 区域别名）、
 *               {@code adapterType}（用哪个适配器实现）；</li>
 *           <li>{@code adapterType} 直接决定 {@code LLMClientManager.createClient} 的选型 ——
 *               <b>不要再改 {@code createClient} 的 switch</b>（它已改为查表分派）；</li>
 *           <li>{@code auth} 声明认证头（名称 + 值前缀，两个字段必须成对填写）——
 *               <b>不要再改 {@code LLMWebClientManager} 的认证头逻辑</b>（两个 switch 已合并为查表）。</li>
 *         </ol>
 *         为什么这样设计：旧实现下「档案声明了别名、适配器 switch 却没写」会让配了该别名的
 *         Provider <b>掉进 {@code default} 被当成 DeepSeekClient</b> —— 能力按档案判、对话走错协议，
 *         编译和测试都不报错，只在用户那儿暴露。收进档案后别名与实现同源，漂移在结构上不可能发生。
 *         详见 {@code ProviderProfile#adapterType} 的注释与自检项 {@code ProviderRegistry}.selfCheck()。</td>
 *   </tr>
 * </table>
 *
 * <h3>B. 能力开关（不写不报错，功能静默缺失）</h3>
 * <p>下列钩子在 {@code AbstractLLMClient} 里<b>默认全部关闭</b>，漏掉时症状各不相同，请逐项确认：</p>
 * <table border="1">
 *   <caption>能力开关清单</caption>
 *   <tr><th>#</th><th>能力</th><th>覆写 / 配置</th><th>漏掉的症状</th></tr>
 *   <tr>
 *     <td>B1</td>
 *     <td>stream usage（成本）</td>
 *     <td>{@link #getUsageProtocol()} + {@code requiresStreamUsageOption()} +
 *         {@code application.yml → llm.pricing.models.<模型>}（单价）</td>
 *     <td>成本看板金额全空 / 聊天页显示「—」</td>
 *   </tr>
 *   <tr>
 *     <td>B2</td>
 *     <td>reasoning_content 回传</td>
 *     <td>{@code requiresReasoningPadding()}（使用该字段体系的 Provider 必开）</td>
 *     <td>thinking 模型第二轮起 400：{@code The reasoning_content in the thinking mode must be passed back}</td>
 *   </tr>
 *   <tr>
 *     <td>B3</td>
 *     <td>必需请求头</td>
 *     <td>{@link #getRequestHeaders()}（如 Anthropic 的 {@code anthropic-version}）</td>
 *     <td>端点写对了但请求 400 / 401</td>
 *   </tr>
 *   <tr>
 *     <td>B4</td>
 *     <td>端点路径</td>
 *     <td>{@link #getChatEndpoint()}（默认 {@code /v1/chat/completions}）</td>
 *     <td>404</td>
 *   </tr>
 *   <tr>
 *     <td>B5</td>
 *     <td>图片识别</td>
 *     <td>{@code application.yml → llm.model-capabilities.vision-rules}（模型级判定）+
 *         适配器内做块协议转换（如 OpenAI {@code image_url} → Anthropic {@code image/source}，
 *         或剔除自家不认的 {@code detail} 字段）</td>
 *     <td>发图 400 / 图片被静默丢弃</td>
 *   </tr>
 *   <tr>
 *     <td>B6</td>
 *     <td>文件上传（Files API）</td>
 *     <td>实现 {@code FilesApiClient}（或继承 {@code AbstractFilesApiClient}）+
 *         在 {@code ProviderRegistry} 档案的 {@code filesApi} 字段声明（T7 后 {@code FilesApiClientManager}
 *         已改为查表）；未声明的模板<b>自动降级为本地存储 + base64 内联</b>，功能仍可用</td>
 *     <td>大文件/图片只能走 inline base64，超限报错</td>
 *   </tr>
 *   <tr>
 *     <td>B7</td>
 *     <td>联网搜索</td>
 *     <td>在 {@code ProviderRegistry} 档案的 {@code search} 字段声明平台类型
 *         （{@code VOLCENGINE / QWEN_DIALOG / MINIMAX_PLAN / DEEPSEEK_OFFICIAL}）+
 *         实现对应的 ApiClient（T8 后身份判定已查表）</td>
 *     <td>联网只剩通用抓取（Bing / 百度 / Jina），拿不到平台结构化结果</td>
 *   </tr>
 *   <tr>
 *     <td>B8</td>
 *     <td>余额 / 额度查询</td>
 *     <td>在 {@code ProviderRegistry} 档案的 {@code balance} 字段声明平台类型
 *         （{@code DEEPSEEK_OFFICIAL / VOLCENGINE_BILLING / ALIYUN_BILLING / MINIMAX_PLAN / MIMO_COOKIE}）+
 *         必要时新增 BillingClient（T6 后 {@code ProviderBalanceService} 已删掉 5 个 {@code isXxx}，改为查表分派）</td>
 *     <td>余额按钮提示「不支持余额查询」</td>
 *   </tr>
 *   <tr>
 *     <td>B9</td>
 *     <td>输出上限 / 推理模型判定</td>
 *     <td>{@code application.yml → llm.model-capabilities}
 *         （reasoning-model-prefixes / effort 支持档位）+ {@code extraConfig.maxTokens}</td>
 *     <td>输出被截断，或推理模型被塞 temperature 遭官方拒绝</td>
 *   </tr>
 * </table>
 *
 * <h3>C. 后端唯一真相源（T12 后：4 份副本 → 1 份）</h3>
 * <p>
 * <b>本项目的核心教训：能力规则一旦有副本，就必然漂移，而且漂移<b>不报错</b>。</b>
 * 典型症状是「界面说能发图、实际图片被静默丢弃」和「界面说能查余额、点下去报缺凭证」。
 * </p>
 * <p>
 * T1~T12 的重构把原本散落在下列位置的规则全部收进 {@code ProviderRegistry}：
 * </p>
 * <table border="1">
 *   <caption>原 4 份副本 → 现 1 份唯一真相源</caption>
 *   <tr><th>能力</th><th>重构前散落在</th><th>现在</th></tr>
 *   <tr>
 *     <td>视觉</td>
 *     <td>① {@code ModelCapabilityConfig} 代码默认值；② {@code application.yml} 的 {@code vision-rules}；
 *         ③ {@code ModelCapabilityRules} 的 if 链；④ 前端 {@code VISION_TEMPLATES + visionStateOf}</td>
 *     <td>{@code ProviderRegistry} 的 {@code vision} 声明（yml 降级为<b>可选覆盖层</b>）</td>
 *   </tr>
 *   <tr>
 *     <td>Files API</td>
 *     <td>{@code FilesApiClientManager.createClient} 的 switch</td>
 *     <td>{@code ProviderRegistry} 的 {@code filesApi}（T7）</td>
 *   </tr>
 *   <tr>
 *     <td>联网搜索</td>
 *     <td>{@code SearchTool} 的 if 优先级链 + 3 个 ApiClient 各自的身份判定</td>
 *     <td>{@code ProviderRegistry} 的 {@code search}（T8）</td>
 *   </tr>
 *   <tr>
 *     <td>余额</td>
 *     <td>{@code ProviderBalanceService} 的 5 个 {@code isXxx} + baseUrl host 兜底猜测</td>
 *     <td>{@code ProviderRegistry} 的 {@code balance} + {@code BALANCE_DETECTION_ORDER}（T6）</td>
 *   </tr>
 *   <tr>
 *     <td>计费模式</td>
 *     <td>无（此前根本没建模 → 套餐制被按 token 估出虚假金额）</td>
 *     <td>{@code ProviderRegistry} 的 {@code pricingMode} + {@code quotaModeUrlKeywords}（T4）</td>
 *   </tr>
 *   <tr>
 *     <td>适配器选型 / 认证头</td>
 *     <td>{@code LLMClientManager.createClient} 的 switch + {@code LLMWebClientManager} 的<b>两个</b> switch</td>
 *     <td>{@code ProviderRegistry} 的 {@code adapterType} + {@code auth}（T12）</td>
 *   </tr>
 * </table>
 *
 * <h3>前端现在怎么拿能力结论（T9/T10/T11）</h3>
 * <p>
 * 后端通过 {@code ProviderCapabilityService} 下发结论，前端<b>从「推导者」降级为「展示者」</b>：
 * </p>
 * <ul>
 *   <li>{@code GET /api/llm-providers/capabilities} —— 全部 Provider 的批量能力（含凭证成对判定）</li>
 *   <li>{@code GET /api/llm-providers/templates} —— 全部内置模板档案（新建时的下拉 + 预览）</li>
 *   <li>{@code GET /api/llm-providers/{code}/capabilities} —— 单个 Provider（精确到「缺 secretKey」）</li>
 * </ul>
 * <p>
 * 前端 {@code provider-capability.ts} 只保留<b>离线兜底</b>规则：后端不可达时用它，
 * 所以<b>不必</b>再与新模板逐条同步（不同步最多退化成「旧结论」，不会显示错误能力）。
 * {@code ProviderEditDrawer} 的模板下拉也已改为从 {@code /templates} 拉取。
 * </p>
 *
 * <h3>D. 前端与测试</h3>
 * <ol>
 *   <li><b>v3 前端</b>：模板下拉与能力徽章<b>已自动跟随后端</b>，新增模板时无需改前端
 *       （下拉来自 {@code /templates}，徽章来自 {@code /capabilities}）。</li>
 *   <li><b>旧版前端</b>（{@code frontend/}）：{@code ConfigView.vue} / {@code ProviderConfigView.vue} 的
 *       {@code templateLabel} / 下拉选项、{@code CodeAssistantView.vue} 的 {@code BALANCE_TEMPLATES}
 *       —— 这些是<b>已标注废弃的遗留副本</b>，<b>新增模板时不要在此扩展</b>（旧版无能力接口消费层，
 *       改了也只是又多一份必然漂移的副本）。</li>
 *   <li><b>单测</b>（本项目用 main 方法 + 手写 check，pom 无 junit 依赖）：
 *       <ul>
 *         <li>{@code ProviderRegistrySelfCheck} —— 档案完整性、视觉规则等价、别名一致、适配器覆盖率；</li>
 *         <li>{@code AuthHeaderEquivalenceTest} —— 认证头与旧 switch 逐条等价；</li>
 *         <li>{@code AdapterVisionMatrixTest} —— 各适配器块协议转换 + 视觉防线；</li>
 *         <li>{@code PricingModeGuardTest} —— 套餐制不估价；</li>
 *         <li>{@code ProviderCapabilityServiceTest} —— 能力接口三态与凭证成对判定；</li>
 *         <li>新 usage 协议参与 {@code TokenUsageMultiProtocolTest}。</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p><b>维护约定</b>：新增或修改任何适配点后，请同步更新本清单与
 * {@code docs/LLM_PROVIDER_SYSTEM.md} 第 9.1 节（新增 Provider 实现 SOP）。
 * <b>只改 {@code ProviderRegistry} 一处即可</b>，其余位置应视为「消费方」而非「配置处」。</p>
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
     * 流式响应中 {@code usage}（token 用量）的承载协议。
     *
     * <p><b>为什么需要这个声明</b>：成本估算的前提是拿到真实用量，而不同协议的用量字段
     * 位置与含义完全不同，靠「有没有 usage 字段」猜不出来：</p>
     *
     * <table border="1">
     *   <caption>三种协议对比</caption>
     *   <tr><th>协议</th><th>承载位置</th><th>字段</th><th>典型 Provider</th></tr>
     *   <tr>
     *     <td>{@code openai}</td>
     *     <td>最后一个 chunk 的 {@code usage} 对象</td>
     *     <td>prompt_tokens / completion_tokens / prompt_tokens_details.cached_tokens</td>
     *     <td>DeepSeek / OpenAI / 千问 / 方舟 / MiniMax / MiMo</td>
     *   </tr>
     *   <tr>
     *     <td>{@code anthropic}</td>
     *     <td><b>拆成两处</b>：{@code message_start.message.usage}（输入侧）+
     *         {@code message_delta.usage}（输出侧）</td>
     *     <td>input_tokens / cache_read_input_tokens / cache_creation_input_tokens / output_tokens</td>
     *     <td>Anthropic 官方与各类 /anthropic 兼容层</td>
     *   </tr>
     *   <tr>
     *     <td>{@code ollama}</td>
     *     <td>最后一个 {@code done=true} 的 NDJSON 行</td>
     *     <td>prompt_eval_count / eval_count / prompt_eval_cached_count</td>
     *     <td>本机 / 内网 Ollama</td>
     *   </tr>
     * </table>
     *
     * <p>默认 {@code openai}：绝大多数适配器都走 OpenAI 兼容协议，无需覆盖。</p>
     *
     * @return {@code openai} / {@code anthropic} / {@code ollama}（大小写不敏感）
     */
    default String getUsageProtocol() {
        return "openai";
    }

    /**
     * Provider 专属的 HTTP 请求头（如 Anthropic 必需的 {@code anthropic-version}）。
     *
     * <p><b>为什么需要它</b>：主对话链路与工具循环是直接用
     * {@code webClient.post().uri(getChatEndpoint())} 发请求的（不经
     * {@link #streamChat}），而 WebClient 只注入了认证头。若某协议的请求头是
     * <b>必需项</b>而非可选装饰，缺失会直接 400 —— 该头必须由适配器声明、
     * 由发送点统一挂载，否则「端点对了但请求被拒」这类问题极难定位。</p>
     *
     * @return header 名 → 值；无专属头返回空 Map（不为 null）
     */
    default Map<String, String> getRequestHeaders() {
        return Map.of();
    }

    /**
     * 请求发送前的最后一道兜底：确保 assistant 消息的 {@code reasoning_content} 字段存在。
     *
     * <p>
     * <b>为什么必须在调用方显式调用</b>：主对话链路与工具循环续跑
     * （{@code DeepSeekServiceImpl}）是直接用 {@code webClient.post().bodyValue(requestBody)}
     * 发请求的，<b>不会经过</b> {@link #streamChat} / {@link #buildRequestBody}，
     * 因此兜底逻辑必须由这些调用点在发送前主动调用，否则等于没挂。
     * </p>
     *
     * <p>默认实现原样返回：不使用 {@code reasoning_content} 字段体系的 Provider 无需处理。</p>
     *
     * @param requestBody 原始请求体
     * @return 处理后的请求体（可能是副本）；不得为 null 时返回入参本身
     */
    default Map<String, Object> ensureReasoningContent(Map<String, Object> requestBody) {
        return requestBody;
    }

    /**
     * 从阻塞式响应中提取最终内容
     *
     * @param responseBody 完整的 JSON 响应字符串
     * @return 模型输出的文本内容
     */
    String extractContentFromBlockingResponse(String responseBody);
}
