package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 网络连通性检测工具
 * 测试目标网站的可达性，辅助判断当前网络环境是否能访问国外网络
 */
@Slf4j
public class NetworkCheckTool implements Tool {

    /** 默认检测的国外站点列表 */
    private static final String[] DEFAULT_TARGETS = {
            "https://www.google.com",
            "https://github.com",
            "https://huggingface.co",
            "https://api.deepseek.com"
    };

    private static final int CONNECT_TIMEOUT = 5000;

    private final ObjectMapper objectMapper;

    public NetworkCheckTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "check_network";
    }

    @Override
    public String getDescription() {
        return "检测当前网络环境是否能访问国外网站，帮助排查网络连通性问题。"
                + "在调用 web_fetch 之前，可先用此工具判断目标网站是否可达，避免抓取超时。"
                + "不传参数时自动检测 google/github/huggingface/deepseek 等常用站点";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        parameters.put("description", "检测网络连通性");

        ObjectNode properties = objectMapper.createObjectNode();
        ObjectNode urlProperty = objectMapper.createObjectNode();
        urlProperty.put("type", "string");
        urlProperty.put("description", "要检测的目标 URL（可选），不传则检测一批常用国外站点");
        properties.set("target_url", urlProperty);

        parameters.set("properties", properties);
        parameters.putArray("required");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String targetUrl = arguments.path("target_url").asText("");

        StringBuilder sb = new StringBuilder();
        sb.append("--- 网络连通性检测结果 ---\n\n");

        if (!targetUrl.isEmpty()) {
            // 检测指定目标
            sb.append("检测目标: ").append(targetUrl).append("\n");
            CheckResult result = check(targetUrl);
            sb.append("状态: ").append(result.status).append("\n");
            sb.append("耗时: ").append(result.timeMs).append("ms").append("\n");
            if (result.error != null) {
                sb.append("错误: ").append(result.error).append("\n");
            }
            sb.append("\n判定: ").append(result.status.equals("✅ 可达") ? "当前网络可以访问此网站" : "当前网络无法访问此网站，建议更换目标或使用代理");
        } else {
            // 检测默认站点列表
            sb.append("检测一批常用国外站点：\n\n");
            List<CheckResult> results = new ArrayList<>();
            for (String target : DEFAULT_TARGETS) {
                CheckResult result = check(target);
                results.add(result);
                sb.append("  ").append(result).append("\n");
            }

            // 综合判定
            long reachableCount = results.stream().filter(r -> r.status.equals("✅ 可达")).count();
            sb.append("\n--- 判定 ---\n");
            if (reachableCount == results.size()) {
                sb.append("当前网络环境通畅，可以访问国外网站。");
            } else if (reachableCount >= results.size() / 2.0) {
                sb.append("当前网络环境部分受限，部分国外网站可以访问。");
            } else {
                sb.append("当前网络环境受限，大部分国外网站无法访问，建议启用代理。");
            }
        }

        return sb.toString();
    }

    private CheckResult check(String url) {
        CheckResult result = new CheckResult();
        result.url = url;

        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : (url.startsWith("https") ? 443 : 80);

            long start = System.currentTimeMillis();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT);
            }
            long elapsed = System.currentTimeMillis() - start;

            result.timeMs = elapsed;
            result.status = "✅ 可达";
            log.info("网络检测 - 可达: {} ({}ms)", url, elapsed);
        } catch (Exception e) {
            long elapsed = CONNECT_TIMEOUT;
            result.timeMs = elapsed;
            result.status = "❌ 不可达";
            result.error = e.getMessage();
            log.info("网络检测 - 不可达: {} ({})", url, e.getMessage());
        }

        return result;
    }

    private static class CheckResult {
        String url;
        String status;
        long timeMs;
        String error;

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(status).append(" ").append(url);
            sb.append(" (").append(timeMs).append("ms)");
            if (error != null) {
                sb.append(" - ").append(error);
            }
            return sb.toString();
        }
    }
}
