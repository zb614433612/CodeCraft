package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.tool.browser.BrowserBridgeClient;
import com.example.agentdeepseek.tool.browser.BrowserConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 浏览器会话控制器。
 *
 * <p><b>为什么需要它</b>：{@code browser} 工具在 Electron 主进程里开的是独立窗口 ——
 * 模型看得到（通过 snapshot），用户看不到，只能自己翻任务栏找。这个接口把窗口状态
 * 暴露给聊天界面：用户随时知道「AI 正在浏览哪个页面」，并能一键显示 / 隐藏 / 关闭。
 * 这是产品「操作可审计」定位的具体落点，也是浏览器工具从「黑盒」变成「可见」的关键一步。</p>
 *
 * <p><b>数据来源</b>：每次请求都实时问 Electron bridge（一次本机回环 HTTP，开销远小于一次 LLM token）。
 * 不落库 —— 浏览器窗口是进程级资源，客户端重启后本来就不存在了，存下来只会读到脏数据。
 * 桌面客户端没启动时返回 {@code available=false}，前端据此隐藏入口而不是弹错误。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/browser")
@Tag(name = "AI 浏览器", description = "查看 / 控制 AI 正在操作的浏览器窗口")
public class BrowserSessionController {

    private final BrowserBridgeClient bridge;
    private final BrowserConfig config;
    private final ObjectMapper objectMapper;

    public BrowserSessionController(BrowserBridgeClient bridge, BrowserConfig config,
                                    ObjectMapper objectMapper) {
        this.bridge = bridge;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询浏览器标签状态。
     *
     * <p>前端按当前会话查询用于展示；不传 conversationId 时返回全部（诊断用）。</p>
     */
    @Operation(summary = "查询 AI 浏览器标签", description = "返回指定会话（或全部）的浏览器标签状态；无客户端时 available=false")
    @GetMapping("/sessions")
    public ApiResponse<Map<String, Object>> sessions(@RequestParam(required = false) String conversationId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", false);
        result.put("conversationId", conversationId == null ? "" : conversationId);
        result.put("tabs", List.of());
        result.put("activeTabId", "");
        result.put("totalWindows", 0);
        result.put("maxTabs", 4);

        if (!config.isEnabled()) {
            result.put("reason", "浏览器工具已被配置禁用（codecraft.browser.enabled=false）");
            return ApiResponse.success(result);
        }
        if (!bridge.isAvailable()) {
            // 非桌面客户端 / 客户端刚重启：不是错误，只是没有窗口可看
            result.put("reason", "当前不在桌面客户端内运行，或客户端尚未启动浏览器通道");
            return ApiResponse.success(result);
        }

        ObjectNode payload = objectMapper.createObjectNode();
        boolean byConversation = conversationId != null && !conversationId.isBlank();
        if (byConversation) {
            payload.put("conversationId", conversationId);
        }
        // 有 conversationId 时用 /tabs（能拿到 activeTabId），否则用 /list（全局视图）
        BrowserBridgeClient.BridgeResponse response = byConversation
                ? bridge.call("/tabs", payload)
                : bridge.call("/list", payload);
        if (!response.ok()) {
            result.put("reason", "查询浏览器状态失败：" + response.error());
            return ApiResponse.success(result);
        }

        result.put("available", true);
        JsonNode body = response.body();
        List<Map<String, Object>> tabs = new ArrayList<>();
        if (byConversation) {
            JsonNode array = body.path("tabs");
            for (JsonNode tab : array) {
                tabs.add(toTab(tab));
            }
            result.put("activeTabId", body.path("activeTabId").asText(""));
            result.put("maxTabs", body.path("maxTabsPerConversation").asInt(4));
        } else {
            JsonNode array = body.path("windows");
            for (JsonNode tab : array) {
                Map<String, Object> item = toTab(tab);
                item.put("conversationId", tab.path("conversationId").asText(""));
                tabs.add(item);
            }
            result.put("maxTabs", body.path("maxTabsPerConversation").asInt(4));
        }
        result.put("tabs", tabs);
        result.put("totalWindows", tabs.size());
        return ApiResponse.success(result);
    }

    /**
     * 显示 / 隐藏 AI 浏览器窗口。
     *
     * <p>用户点「看一眼」时用：模型正在填表单但窗口被 hide 了，这一点就能把它调出来，
     * 不必等模型自己想起来调 show。</p>
     */
    @Operation(summary = "显示 / 隐藏浏览器窗口", description = "把指定会话（可指定标签）的 AI 浏览器窗口显示或隐藏")
    @PostMapping("/sessions/show")
    public ApiResponse<Map<String, Object>> show(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        String conversationId = text(body, "conversationId");
        if (conversationId.isBlank()) {
            result.put("ok", false);
            result.put("error", "conversationId 不能为空");
            return ApiResponse.success(result);
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("conversationId", conversationId);
        String tabId = text(body, "tabId");
        if (!tabId.isBlank()) payload.put("tabId", tabId);
        Object visibleFlag = body == null ? null : body.get("visible");
        payload.put("visible", !"false".equalsIgnoreCase(String.valueOf(visibleFlag)));

        BrowserBridgeClient.BridgeResponse response = bridge.call("/show", payload);
        result.put("ok", response.ok());
        if (!response.ok()) {
            result.put("error", response.status() == 410 ? "窗口已关闭" : response.error());
        }
        return ApiResponse.success(result);
    }

    /**
     * 关闭浏览器标签。
     *
     * <p>用户手动收尾用：AI 干完活忘了关窗口时，不必再发一条消息让模型关。</p>
     */
    @Operation(summary = "关闭浏览器标签", description = "关闭指定会话的某个标签，或 all=true 关掉全部")
    @PostMapping("/sessions/close")
    public ApiResponse<Map<String, Object>> close(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        String conversationId = text(body, "conversationId");
        if (conversationId.isBlank()) {
            result.put("ok", false);
            result.put("error", "conversationId 不能为空");
            return ApiResponse.success(result);
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("conversationId", conversationId);
        boolean all = Boolean.parseBoolean(String.valueOf(body == null ? null : body.get("all")));
        if (all) {
            payload.put("all", true);
        } else {
            String tabId = text(body, "tabId");
            if (!tabId.isBlank()) payload.put("tabId", tabId);
        }
        payload.put("reason", "用户手动关闭");

        BrowserBridgeClient.BridgeResponse response = bridge.call("/close", payload);
        result.put("ok", response.ok());
        if (!response.ok()) {
            result.put("error", response.error());
            return ApiResponse.success(result);
        }
        result.put("closed", response.flag("closed", false));
        result.put("closedCount", response.body() == null ? 0 : response.body().path("closedCount").asInt(0));
        result.put("remain", response.body() == null ? 0 : response.body().path("remain").asInt(0));
        return ApiResponse.success(result);
    }

    private Map<String, Object> toTab(JsonNode tab) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("tabId", tab.path("tabId").asText(""));
        item.put("active", tab.path("active").asBoolean(false));
        item.put("visible", tab.path("visible").asBoolean(false));
        item.put("pageUrl", tab.path("pageUrl").asText(""));
        item.put("idleMs", tab.path("idleMs").asLong(0));
        return item;
    }

    private String text(Map<String, Object> body, String key) {
        if (body == null) return "";
        Object value = body.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
