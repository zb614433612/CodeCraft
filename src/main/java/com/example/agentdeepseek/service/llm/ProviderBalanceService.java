package com.example.agentdeepseek.service.llm;

import com.example.agentdeepseek.model.entity.ProviderConfig;
import com.example.agentdeepseek.model.vo.ProviderBalanceVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * LLM Provider 余额查询服务
 * <p>
 * 目前仅支持 DeepSeek 平台：调用官方 GET /user/balance 接口查询账户余额
 * （https://api-docs.deepseek.com/zh-cn/api/get-user-balance）。
 * 复用该 Provider 的 WebClient（自动携带认证头与连接池），不新建连接。
 * </p>
 */
@Slf4j
@Service
public class ProviderBalanceService {

    /** 余额查询超时（轻量 GET 请求，无需长时间等待） */
    private static final Duration BALANCE_TIMEOUT = Duration.ofSeconds(10);

    /** DeepSeek 余额接口路径（位于 baseUrl 根路径，不带 /v1） */
    private static final String DEEPSEEK_BALANCE_PATH = "/user/balance";

    /** 请求模板标识：DeepSeek */
    private static final String TEMPLATE_DEEPSEEK = "deepseek";

    private final LLMClientManager llmClientManager;
    private final LLMWebClientManager webClientManager;
    private final ObjectMapper objectMapper;

    public ProviderBalanceService(LLMClientManager llmClientManager,
                                  LLMWebClientManager webClientManager,
                                  ObjectMapper objectMapper) {
        this.llmClientManager = llmClientManager;
        this.webClientManager = webClientManager;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询指定 Provider 的账户余额
     *
     * @param providerCode Provider 编码（如 "deepseek"）
     * @return 余额信息
     * @throws IllegalArgumentException Provider 不存在或该 Provider 不支持余额查询
     * @throws RuntimeException         查询失败（网络 / 认证 / 解析错误）
     */
    public ProviderBalanceVO queryBalance(String providerCode) {
        ProviderConfig config = llmClientManager.getProviderConfig(providerCode);
        if (config == null) {
            throw new IllegalArgumentException("Provider [" + providerCode + "] 不存在或未启用");
        }

        // 仅 DeepSeek 平台提供余额查询接口（requestTemplate 为空时兜底用 code）
        String template = config.getRequestTemplate();
        if (template == null || template.isEmpty()) {
            template = config.getCode();
        }
        if (!TEMPLATE_DEEPSEEK.equalsIgnoreCase(template)) {
            throw new IllegalArgumentException("Provider [" + providerCode + "] 不支持余额查询（仅 DeepSeek 支持）");
        }

        // 复用该 Provider 的 WebClient：自动携带认证头（Authorization: Bearer <apiKey>）
        WebClient webClient = webClientManager.getOrCreate(config);
        String responseBody;
        try {
            responseBody = webClient.get()
                    .uri(DEEPSEEK_BALANCE_PATH)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(BALANCE_TIMEOUT);
        } catch (WebClientResponseException e) {
            int status = e.getStatusCode().value();
            log.warn("查询 Provider [{}] 余额失败: HTTP {}", providerCode, status);
            if (status == 401) {
                throw new RuntimeException("API Key 无效或未配置，无法查询余额");
            }
            throw new RuntimeException("余额查询失败: HTTP " + status);
        } catch (Exception e) {
            log.warn("查询 Provider [{}] 余额异常: {}", providerCode, e.getMessage());
            throw new RuntimeException("余额查询失败: " + e.getMessage());
        }

        return parseBalance(providerCode, responseBody);
    }

    /**
     * 解析 DeepSeek 余额响应
     * <p>
     * 响应示例：
     * {@code {"is_available":true,"balance_infos":[{"currency":"CNY",
     * "total_balance":"110.00","granted_balance":"10.00","topped_up_balance":"100.00"}]}}
     * </p>
     */
    private ProviderBalanceVO parseBalance(String providerCode, String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            ProviderBalanceVO vo = new ProviderBalanceVO();
            vo.setProviderCode(providerCode);
            vo.setIsAvailable(root.path("is_available").asBoolean(false));

            List<ProviderBalanceVO.BalanceInfo> infos = new ArrayList<>();
            for (JsonNode item : root.path("balance_infos")) {
                ProviderBalanceVO.BalanceInfo info = new ProviderBalanceVO.BalanceInfo();
                info.setCurrency(item.path("currency").asText(null));
                info.setTotalBalance(item.path("total_balance").asText(""));
                info.setGrantedBalance(item.path("granted_balance").asText(""));
                info.setToppedUpBalance(item.path("topped_up_balance").asText(""));
                infos.add(info);
            }
            vo.setBalanceInfos(infos);
            vo.setUpdatedAt(System.currentTimeMillis());
            return vo;
        } catch (Exception e) {
            log.warn("解析 Provider [{}] 余额响应失败: {}", providerCode, e.getMessage());
            throw new RuntimeException("余额响应解析失败");
        }
    }
}
