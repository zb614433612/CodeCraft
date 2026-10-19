package com.example.agentdeepseek.service.llm;

import com.example.agentdeepseek.service.files.FileVisionService;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 适配器「图片识别」能力矩阵测试（main 方法，不依赖 JUnit / 网络）。
 *
 * <p>内部视觉链路（{@code FileVisionService}）对所有 Provider 统一产出 OpenAI 风格块：
 * {@code {"type":"text","text":"..."}} 与
 * {@code {"type":"image_url","image_url":{"url":"data:image/png;base64,AAA","detail":"auto"}}}。
 * 各适配器必须把它转成自家协议，否则就是「图发出去了但模型看不到」的静默失败。</p>
 *
 * <h3>覆盖点</h3>
 * <ol>
 *   <li><b>Ollama</b>：content 数组 → {@code images[]}（纯 base64、无 data: 前缀）；URL / file_id 跳过并计数</li>
 *   <li><b>openai / gpt</b>：公共契约与官方专精均按 OpenAI 规范原样透传（含 {@code detail}）</li>
 *   <li><b>qwen / minimax / mimo</b>：保留 image_url，剔除协议未声明的 {@code detail}</li>
 *   <li><b>anthropic</b>：转 {@code image/source}（base64 与 url 两种分支）</li>
 *   <li><b>supportsVisionModel</b>：后端模型防线矩阵（deepseek / 火山 / qwen / minimax / mimo / 未知）</li>
 * </ol>
 *
 * <h3>运行方式</h3>
 * <pre>
 * mvn -q -DskipTests -DskipFrontend=true test-compile
 * mvn -q org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
 *     -Dexec.mainClass=com.example.agentdeepseek.service.llm.AdapterVisionMatrixTest \
 *     -Dexec.classpathScope=test -DskipFrontend=true
 * </pre>
 */
public class AdapterVisionMatrixTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PNG_DATA_URL = "data:image/png;base64,AAAA";
    private static final String JPG_DATA_URL = "data:image/jpeg;base64,BBBB";

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        ollamaConversion();
        ollamaEdgeCases();
        openAiPassthrough();
        gptPassthrough();
        detailStrippedAdapters();
        anthropicConversion();
        visionModelMatrix();

        System.out.println("\n========== 结果: " + passed + " passed, " + failed + " failed ==========");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== 1. Ollama：content 数组 → images[] ====================

    private static void ollamaConversion() {
        List<Map<String, Object>> messages = imageMessages("看一下这张图", PNG_DATA_URL, JPG_DATA_URL);
        OllamaClient.ImageConversion result = OllamaClient.convertImageBlocksToImages(messages);

        check("Ollama：2 张图全部转换", "2", result.converted());
        check("Ollama：无跳过", "0", result.skipped());
        check("Ollama：content 合并为纯文本", "看一下这张图", String.valueOf(messages.get(0).get("content")));

        @SuppressWarnings("unchecked")
        List<String> images = (List<String>) messages.get(0).get("images");
        check("Ollama：images 数量", "2", String.valueOf(images.size()));
        check("Ollama：第一张去掉 data: 前缀", "AAAA", images.get(0));
        check("Ollama：第二张去掉 data: 前缀", "BBBB", images.get(1));

        // 幂等：content 已是字符串，再次调用不得重复转换也不得丢图
        OllamaClient.ImageConversion again = OllamaClient.convertImageBlocksToImages(messages);
        check("Ollama：重复调用幂等（converted=0）", "0", again.converted());
        check("Ollama：重复调用后 images 仍在", "2",
                String.valueOf(((List<?>) messages.get(0).get("images")).size()));
    }

    private static void ollamaEdgeCases() {
        // 远程 URL：Ollama REST 只认 base64 → 跳过并计数（不静默）
        List<Map<String, Object>> urlMessages = imageMessages("远程图", "https://example.com/a.png");
        OllamaClient.ImageConversion urlResult = OllamaClient.convertImageBlocksToImages(urlMessages);
        check("Ollama：远程 URL 跳过计数", "1", urlResult.skipped());
        check("Ollama：远程 URL 不产生 images", "false",
                String.valueOf(urlMessages.get(0).containsKey("images")));
        check("Ollama：远程 URL 仍保留文本", "远程图", String.valueOf(urlMessages.get(0).get("content")));

        // 非 base64 的 data URL
        check("Ollama：空白 url 判为不可用", "null",
                String.valueOf(OllamaClient.extractBase64Payload("   ")));
        OllamaClient.ImageConversion svgResult =
                OllamaClient.convertImageBlocksToImages(imageMessages("内联 SVG", "data:image/svg+xml,<svg/>"));
        check("Ollama：内联 SVG 跳过", "1", svgResult.skipped());

        // 裸 base64 原样保留
        check("Ollama：裸 base64 原样", "CCCC",
                OllamaClient.extractBase64Payload("CCCC"));
        // Map 形态
        Map<String, Object> imageUrlMap = new LinkedHashMap<>();
        imageUrlMap.put("url", PNG_DATA_URL);
        imageUrlMap.put("detail", "high");
        check("Ollama：Map 形态取值", "AAAA",
                OllamaClient.extractBase64Payload(imageUrlMap));
        // 空值
        check("Ollama：空 url 返回 null", "null",
                String.valueOf(OllamaClient.extractBase64Payload((Object) null)));

        // 多文本块按换行合并；无文本时 content 为空串
        List<Map<String, Object>> twoText = new ArrayList<>();
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "user");
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(textBlock("第一段"));
        parts.add(textBlock("第二段"));
        parts.add(imageBlock(PNG_DATA_URL));
        msg.put("content", parts);
        twoText.add(msg);
        OllamaClient.convertImageBlocksToImages(twoText);
        check("Ollama：多文本块换行合并", "第一段\n第二段", String.valueOf(twoText.get(0).get("content")));

        List<Map<String, Object>> imageOnly = imageMessages("", PNG_DATA_URL);
        OllamaClient.convertImageBlocksToImages(imageOnly);
        check("Ollama：只有图时 content 为空串", "", String.valueOf(imageOnly.get(0).get("content")));

        // Files API 的 file 块 → 跳过
        List<Map<String, Object>> fileBlockMessages = new ArrayList<>();
        Map<String, Object> fileMsg = new LinkedHashMap<>();
        fileMsg.put("role", "user");
        List<Map<String, Object>> fileParts = new ArrayList<>();
        fileParts.add(textBlock("文件句柄"));
        Map<String, Object> fileBlock = new LinkedHashMap<>();
        fileBlock.put("type", "file");
        fileBlock.put("file_id", "file-api-123");
        fileParts.add(fileBlock);
        fileMsg.put("content", fileParts);
        fileBlockMessages.add(fileMsg);
        OllamaClient.ImageConversion fileResult = OllamaClient.convertImageBlocksToImages(fileBlockMessages);
        check("Ollama：file 块跳过计数", "1", fileResult.skipped());
        check("Ollama：file 块不产生 images", "false",
                String.valueOf(fileBlockMessages.get(0).containsKey("images")));

        // 纯文本消息（字符串 content）不受影响
        List<Map<String, Object>> plain = plainMessages("你好");
        OllamaClient.ImageConversion plainResult = OllamaClient.convertImageBlocksToImages(plain);
        check("Ollama：纯文本消息不处理", "0", plainResult.converted());
        check("Ollama：纯文本 content 原样", "你好", String.valueOf(plain.get(0).get("content")));
    }

    // ==================== 2. OpenAI 公共契约：原样透传 ====================

    private static void openAiPassthrough() {
        OpenAIClient client = new OpenAIClient(null, MAPPER, "openai", "https://api.openai.com/v1");
        Map<String, Object> body = client.buildRequestBody(
                imageMessages("看图", PNG_DATA_URL), "gpt-4o", 0.3, "non-thinking", false, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sent = (List<Map<String, Object>>) body.get("messages");
        Object content = sent.get(0).get("content");
        check("openai：content 保持数组形态", "true", String.valueOf(content instanceof List));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) content;
        check("openai：图片块仍在", "image_url", String.valueOf(blocks.get(1).get("type")));

        @SuppressWarnings("unchecked")
        Map<String, Object> imageUrl = (Map<String, Object>) blocks.get(1).get("image_url");
        check("openai：url 原样透传", PNG_DATA_URL, String.valueOf(imageUrl.get("url")));
        check("openai：detail 原样透传（OpenAI 规范字段）", "auto", String.valueOf(imageUrl.get("detail")));
    }

    private static void gptPassthrough() {
        GptClient client = new GptClient(null, MAPPER, "gpt", "https://api.openai.com/v1");
        Map<String, Object> body = client.buildRequestBody(
                imageMessages("看图", PNG_DATA_URL), "gpt-4o", 0.3, "non-thinking", false, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sent = (List<Map<String, Object>>) body.get("messages");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) sent.get(0).get("content");
        @SuppressWarnings("unchecked")
        Map<String, Object> imageUrl = (Map<String, Object>) blocks.get(1).get("image_url");
        check("gpt：官方专精同样透传 url", PNG_DATA_URL, String.valueOf(imageUrl.get("url")));
        check("gpt：官方专精保留 detail", "auto", String.valueOf(imageUrl.get("detail")));
    }

    // ==================== 3. 剔除 detail 的三家 ====================

    private static void detailStrippedAdapters() {
        check("qwen：剔除 detail", "false",
                String.valueOf(detailPresentAfter(
                        new QwenClient(null, MAPPER, "qwen", "https://dashscope.aliyuncs.com", false, null),
                        "qwen3-vl-plus")));
        check("minimax：剔除 detail", "false",
                String.valueOf(detailPresentAfter(
                        new MiniMaxClient(null, MAPPER, "minimax"), "MiniMax-M3")));
        check("mimo：剔除 detail", "false",
                String.valueOf(detailPresentAfter(
                        new MiMoClient(null, MAPPER, "mimo"), "mimo-v2.5")));
    }

    /** 构建请求体后，检查第一个 image_url 块是否还带 detail；同时确认 url 未被破坏 */
    private static boolean detailPresentAfter(AbstractLLMClient client, String model) {
        Map<String, Object> body = client.buildRequestBody(
                imageMessages("看图", PNG_DATA_URL), model, 0.3, "non-thinking", false, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sent = (List<Map<String, Object>>) body.get("messages");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) sent.get(0).get("content");
        @SuppressWarnings("unchecked")
        Map<String, Object> imageUrl = (Map<String, Object>) blocks.get(1).get("image_url");
        boolean urlIntact = PNG_DATA_URL.equals(String.valueOf(imageUrl.get("url")));
        check("  ↳ url 未被破坏（" + model + "）", "true", String.valueOf(urlIntact));
        return imageUrl.containsKey("detail");
    }

    // ==================== 4. Anthropic：image_url → image/source ====================

    private static void anthropicConversion() {
        List<Map<String, Object>> base64Messages = imageMessages("看图", PNG_DATA_URL);
        AnthropicClient.convertImageBlocks(base64Messages);

        Map<String, Object> block = firstImageBlock(base64Messages);
        check("anthropic：块类型转 image", "image", String.valueOf(block.get("type")));
        @SuppressWarnings("unchecked")
        Map<String, Object> source = (Map<String, Object>) block.get("source");
        check("anthropic：source.type=base64", "base64", String.valueOf(source.get("type")));
        check("anthropic：media_type 取自 data URL", "image/png", String.valueOf(source.get("media_type")));
        check("anthropic：data 去前缀", "AAAA", String.valueOf(source.get("data")));

        List<Map<String, Object>> urlMessages = imageMessages("看图", "https://example.com/a.jpg");
        AnthropicClient.convertImageBlocks(urlMessages);
        @SuppressWarnings("unchecked")
        Map<String, Object> urlSource =
                (Map<String, Object>) firstImageBlock(urlMessages).get("source");
        check("anthropic：远程 URL 走 source.type=url", "url", String.valueOf(urlSource.get("type")));
        check("anthropic：远程 URL 原样", "https://example.com/a.jpg", String.valueOf(urlSource.get("url")));
    }

    // ==================== 5. 后端模型防线矩阵 ====================

    private static void visionModelMatrix() {
        // deepseek 官方：仅 flash / vision / vl 系列
        check("防线：deepseek-chat 不支持图", "false",
                String.valueOf(FileVisionService.supportsVisionModel("deepseek", "deepseek-chat")));
        check("防线：deepseek-v4-flash 支持图", "true",
                String.valueOf(FileVisionService.supportsVisionModel("deepseek", "deepseek-v4-flash")));
        // 火山方舟：deepseek 系纯文本，豆包 seed 多模态
        check("防线：方舟 deepseek-v4-pro 不支持图", "false",
                String.valueOf(FileVisionService.supportsVisionModel("volcengine", "deepseek-v4-pro")));
        check("防线：方舟 doubao-seed 支持图", "true",
                String.valueOf(FileVisionService.supportsVisionModel("volcengine", "doubao-seed-1-6-250615")));
        // 千问：仅 VL / Omni
        check("防线：qwen-plus 不支持图", "false",
                String.valueOf(FileVisionService.supportsVisionModel("qwen", "qwen-plus")));
        check("防线：qwen3-vl-plus 支持图", "true",
                String.valueOf(FileVisionService.supportsVisionModel("qwen", "qwen3-vl-plus")));
        // MiniMax：M2 系纯文本，M3 多模态
        check("防线：MiniMax-M2 不支持图", "false",
                String.valueOf(FileVisionService.supportsVisionModel("minimax", "MiniMax-M2")));
        check("防线：MiniMax-M3 支持图", "true",
                String.valueOf(FileVisionService.supportsVisionModel("minimax", "MiniMax-M3")));
        // MiMo：v2.5 起多模态
        check("防线：mimo-7b 不支持图", "false",
                String.valueOf(FileVisionService.supportsVisionModel("mimo", "mimo-7b")));
        check("防线：mimo-v2.5 支持图", "true",
                String.valueOf(FileVisionService.supportsVisionModel("mimo", "mimo-v2.5")));
        // 未适配平台 / 空模型：宽松放行
        check("防线：ollama 宽松放行", "true",
                String.valueOf(FileVisionService.supportsVisionModel("ollama", "qwen3:14b")));
        check("防线：空模型放行", "true",
                String.valueOf(FileVisionService.supportsVisionModel("deepseek", null)));
    }

    // ==================== 工具方法 ====================

    private static List<Map<String, Object>> imageMessages(String text, String... imageUrls) {
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(textBlock(text == null ? "" : text));
        for (String url : imageUrls) {
            parts.add(imageBlock(url));
        }
        user.put("content", parts);
        messages.add(user);
        return messages;
    }

    private static Map<String, Object> textBlock(String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        return block;
    }

    private static Map<String, Object> imageBlock(String url) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "image_url");
        Map<String, Object> imageUrl = new LinkedHashMap<>();
        imageUrl.put("url", url);
        imageUrl.put("detail", "auto");
        block.put("image_url", imageUrl);
        return block;
    }

    private static List<Map<String, Object>> plainMessages(String text) {
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content", text);
        messages.add(user);
        return messages;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstImageBlock(List<Map<String, Object>> messages) {
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) messages.get(0).get("content");
        for (Map<String, Object> block : blocks) {
            if ("image".equals(block.get("type")) || "image_url".equals(block.get("type"))) {
                return block;
            }
        }
        throw new IllegalStateException("未找到图片块");
    }

    private static void check(String name, String expected, int actual) {
        check(name, expected, String.valueOf(actual));
    }

    private static void check(String name, String expected, String actual) {
        if (expected.equals(actual)) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name + "：期望 [" + expected + "]，实际 [" + actual + "]");
        }
    }
}
