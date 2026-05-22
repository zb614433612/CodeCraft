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

import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;

/**
 * 网络连通性检测工具
 * 使用 ConnectivityChecker 测试目标网站可达性，辅助判断当前网络环境
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.NETWORK, description = "检查网络连通性")
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
        return "【适用场景】检测当前网络环境能否访问目标网站，用于排查 web_search / web_fetch / http_request 失败是否是网络不通导致。是其他网络工具的诊断前置工具。"
                + "【与其他工具的关系】当 web_fetch 或 http_request 报连接失败时，先用本工具检测目标站点可达性。可达再排查工具参数，不可达则需要配置代理。"
                + "【默认行为】不传 target_url 时，自动检测 google / github / huggingface / deepseek 等一批常用站点，给出综合连通性判定。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        parameters.put("description", "检测网络连通性");

        ObjectNode properties = objectMapper.createObjectNode();
        ObjectNode urlProperty = objectMapper.createObjectNode();
        urlProperty.put("type", "string");
        urlProperty.put("description", "【可选】要检测的单个目标 URL，必须包含协议前缀。示例：https://api.github.com。不传则自动检测一批常用国外站点并给出综合判定。通常在 web_fetch/http_request/web_search 失败后，将失败的目标 URL 传入以确诊是否是网络不通");
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
                sb.append("【失败原因】").append(result.error).append("\n");
            }
            if (result.reachable) {
                sb.append("\n【判定】当前网络可以访问此网站，web_fetch 和 http_request 工具可以正常使用。");
            } else {
                sb.append("\n【判定】当前网络无法访问此网站。建议：1) 在应用设置中启用代理 2) 更换目标为同类可替代的国内站点 3) 如果必须访问该网站，请手动配置代理后重试 web_fetch/http_request");
            }
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
            sb.append("\n--- 【综合判定】---\n");
            if (reachableCount == results.size()) {
                sb.append("当前网络环境通畅，可以访问国外网站。web_search / web_fetch / http_request 工具可正常使用。");
            } else if (reachableCount >= results.size() / 2.0) {
                sb.append("当前网络环境部分受限，" + reachableCount + "/" + results.size() + " 个站点可达。建议：1) web_search 使用 cn.bing.com 通常不受限 2) web_fetch 优先抓取可达站点 3) 对不可达站点需启用代理");
            } else {
                sb.append("当前网络环境严重受限，仅 " + reachableCount + "/" + results.size() + " 个站点可达。建议：1) 在应用设置中启用代理以访问国外网站 2) web_search 可尝试使用（cn.bing.com 通常不受限）3) http_request 和 web_fetch 访问国外地址前先用 check_network 检测单个地址");
            }
        }

        return sb.toString();
    }
}
