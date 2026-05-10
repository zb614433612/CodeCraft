package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.config.NetworkToolConfig;
import com.example.agentdeepseek.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 网络连通性检测工具
 * 使用 ConnectivityChecker 测试目标网站可达性，辅助判断当前网络环境
 */
@Slf4j
@Component
public class NetworkCheckTool implements Tool {

    private final ObjectMapper objectMapper;
    private final ConnectivityChecker connectivityChecker;
    private final NetworkToolConfig config;

    public NetworkCheckTool(ObjectMapper objectMapper, ConnectivityChecker connectivityChecker, NetworkToolConfig config) {
        this.objectMapper = objectMapper;
        this.connectivityChecker = connectivityChecker;
        this.config = config;
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
            sb.append("检测目标: ").append(targetUrl).append("\n");
            ConnectivityChecker.CheckResult result = connectivityChecker.check(targetUrl);
            sb.append("状态: ").append(result.statusEmoji()).append("\n");
            sb.append("耗时: ").append(result.timeMs).append("ms\n");
            if (result.error != null && !result.reachable) {
                sb.append("错误: ").append(result.error).append("\n");
            }
            sb.append("\n判定: ").append(result.reachable
                    ? "当前网络可以访问此网站"
                    : "当前网络无法访问此网站，建议更换目标或使用代理");
        } else {
            sb.append("检测一批常用国外站点：\n\n");
            List<ConnectivityChecker.CheckResult> results = new ArrayList<>();
            for (String site : config.getNetworkCheckSites()) {
                ConnectivityChecker.CheckResult result = connectivityChecker.check(site);
                results.add(result);
                sb.append("  ").append(result).append("\n");
            }

            // 综合判定
            long reachableCount = results.stream().filter(r -> r.reachable).count();
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
}
