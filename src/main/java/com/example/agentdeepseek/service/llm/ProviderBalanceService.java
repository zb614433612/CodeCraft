package com.example.agentdeepseek.service.llm;

import com.example.agentdeepseek.model.entity.ProviderConfig;
import com.example.agentdeepseek.model.vo.ProviderBalanceVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * LLM Provider 余额查询服务
 *
 * <h3>支持的平台</h3>
 * <ul>
 *   <li><b>DeepSeek</b>：官方 GET /user/balance
 *       （https://api-docs.deepseek.com/zh-cn/api/get-user-balance），复用 Provider 的 WebClient；</li>
 *   <li><b>火山引擎（方舟）</b>：费用中心 OpenAPI QueryBalanceAcct（V4 签名，需账号级 AccessKey/SecretKey，
 *       配置在 Provider 的「扩展配置」extraConfig 中），见 {@link VolcengineBillingClient}。</li>
 * </ul>
 *
 * <h3>能力判定原则</h3>
 * <p>按 Provider 的「请求模板 + 实际 baseUrl 域名 + 凭证有效性」综合判定，而不是只看 providerCode 名称
 * ——用户自定义 code（如 my-deepseek）时仍能正确识别。</p>
 */
@Slf4j
@Service
public class ProviderBalanceService {

    /** 余额查询超时（轻量 GET/POST 请求，无需长时间等待） */
    private static final Duration BALANCE_TIMEOUT = Duration.ofSeconds(10);

    /** DeepSeek 余额接口路径（位于 baseUrl 根路径，不带 /v1） */
    private static final String DEEPSEEK_BALANCE_PATH = "/user/balance";

    /** 请求模板标识：DeepSeek */
    private static final String TEMPLATE_DEEPSEEK = "deepseek";

    /** 请求模板标识：火山引擎（含历史别名 ark / doubao） */
    private static final Set<String> TEMPLATES_VOLCENGINE = Set.of("volcengine", "ark", "doubao");

    /** 请求模板标识：MiniMax（含区域别名） */
    private static final Set<String> TEMPLATES_MINIMAX = Set.of("minimax", "minimax-cn", "minimax-intl", "minimax-global");

    /** 请求模板标识：小米 MiMo */
    private static final Set<String> TEMPLATES_MIMO = Set.of("mimo", "xiaomi-mimo");

    /** 请求模板标识：通义千问（阿里云百炼 DashScope） */
    private static final Set<String> TEMPLATES_QWEN = Set.of("qwen", "dashscope", "tongyi");

    /** DeepSeek 官方域名（模板为 custom 时的兜底判定） */
    private static final String DEEPSEEK_HOST = "api.deepseek.com";

    /** MiniMax 官方域名后缀 */
    private static final String MINIMAX_CN_SUFFIX = "minimaxi.com";
    private static final String MINIMAX_GLOBAL_SUFFIX = "minimax.io";

    /** MiMo 官方域名后缀 */
    private static final String MIMO_HOST_SUFFIX = "xiaomimimo.com";

    /**
     * MiMo 控制台余额接口：仅会话 Cookie 可用（推理 Key / sk- 无法访问，官方未开放 OpenAPI）。
     * 需要在扩展配置中粘贴浏览器 Cookie（约 24 小时有效，过期后重新粘贴）。
     */
    private static final String MIMO_BALANCE_URL = "https://platform.xiaomimimo.com/api/v1/balance";

    private final LLMClientManager llmClientManager;
    private final LLMWebClientManager webClientManager;
    private final ObjectMapper objectMapper;
    private final VolcengineBillingClient volcengineBillingClient;
    private final MiniMaxCodingPlanClient miniMaxCodingPlanClient;
    private final AliyunBillingClient aliyunBillingClient;

    public ProviderBalanceService(LLMClientManager llmClientManager,
                                  LLMWebClientManager webClientManager,
                                  ObjectMapper objectMapper,
                                  VolcengineBillingClient volcengineBillingClient,
                                  MiniMaxCodingPlanClient miniMaxCodingPlanClient,
                                  AliyunBillingClient aliyunBillingClient) {
        this.llmClientManager = llmClientManager;
        this.webClientManager = webClientManager;
        this.objectMapper = objectMapper;
        this.volcengineBillingClient = volcengineBillingClient;
        this.miniMaxCodingPlanClient = miniMaxCodingPlanClient;
        this.aliyunBillingClient = aliyunBillingClient;
    }

    /**
     * 查询指定 Provider 的账户余额
     *
     * @param providerCode Provider 编码（如 "deepseek" / "volcengine"）
     * @return 余额信息
     * @throws IllegalArgumentException Provider 不存在或该 Provider 不支持余额查询
     * @throws RuntimeException         查询失败（网络 / 认证 / 解析错误）
     */
    public ProviderBalanceVO queryBalance(String providerCode) {
        ProviderConfig config = llmClientManager.getProviderConfig(providerCode);
        if (config == null) {
            throw new IllegalArgumentException("Provider [" + providerCode + "] 不存在或未启用");
        }

        String template = resolveTemplate(config);

        if (isVolcengine(config, template)) {
            return queryVolcengineBalance(config);
        }
        if (isDeepSeek(config, template)) {
            return queryDeepSeekBalance(config);
        }
        if (isMiniMax(config, template)) {
            return queryMiniMaxQuota(config);
        }
        if (isMiMo(config, template)) {
            return queryMiMoBalance(config);
        }
        if (isQwen(config, template)) {
            return queryQwenBalance(config);
        }
        throw new IllegalArgumentException("Provider [" + providerCode
                + "] 不支持余额/额度查询（目前支持 DeepSeek / 火山引擎 / MiniMax / 小米 MiMo / 通义千问）");
    }

    // ==================== DeepSeek ====================

    private ProviderBalanceVO queryDeepSeekBalance(ProviderConfig config) {
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
            log.warn("查询 Provider [{}] 余额失败: HTTP {}", config.getCode(), status);
            if (status == 401) {
                throw new RuntimeException("API Key 无效或未配置，无法查询余额");
            }
            throw new RuntimeException("余额查询失败: HTTP " + status);
        } catch (Exception e) {
            log.warn("查询 Provider [{}] 余额异常: {}", config.getCode(), e.getMessage());
            throw new RuntimeException("余额查询失败: " + e.getMessage());
        }
        return parseDeepSeekBalance(config.getCode(), responseBody);
    }

    /**
     * 解析 DeepSeek 余额响应
     * <p>
     * 响应示例：
     * {@code {"is_available":true,"balance_infos":[{"currency":"CNY",
     * "total_balance":"110.00","granted_balance":"10.00","topped_up_balance":"100.00"}]}}
     * </p>
     */
    private ProviderBalanceVO parseDeepSeekBalance(String providerCode, String responseBody) {
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

    // ==================== 火山引擎 ====================

    /**
     * 查询火山引擎账户余额（费用中心 QueryBalanceAcct）。
     * <p>扩展配置示例：{@code {"accessKey":"AK...","secretKey":"SK...","region":"cn-beijing"}}</p>
     */
    private ProviderBalanceVO queryVolcengineBalance(ProviderConfig config) {
        JsonNode extra = parseExtraConfig(config.getExtraConfig());
        String accessKey = text(extra, "accessKey", "access_key", "ak");
        String secretKey = text(extra, "secretKey", "secret_key", "sk");
        String region = text(extra, "region");

        if (isBlank(accessKey) || isBlank(secretKey) || containsMask(accessKey) || containsMask(secretKey)) {
            throw new IllegalArgumentException("火山引擎余额查询需要 AccessKey/SecretKey，"
                    + "请在 Provider 的「扩展配置」中填写：{\"accessKey\":\"...\",\"secretKey\":\"...\"}");
        }

        JsonNode root = volcengineBillingClient.queryBalance(accessKey, secretKey, region);
        ProviderBalanceVO vo = parseVolcengineBalance(config.getCode(), root);
        log.info("火山引擎余额查询成功: provider={}, available={}", config.getCode(),
                vo.getBalanceInfos().isEmpty() ? "-" : vo.getBalanceInfos().get(0).getTotalBalance());
        return vo;
    }

    /**
     * 解析火山引擎 QueryBalanceAcct 响应。
     * <p>
     * 响应示例：
     * {@code {"ResponseMetadata":{...},"Result":{"AccountID":210xxxxxxx,"ArrearsBalance":"0.00",
     * "AvailableBalance":"77.01","CashBalance":"83.01","CreditLimit":"0.00","FreezeAmount":"5.00"}}}
     * </p>
     */
    private ProviderBalanceVO parseVolcengineBalance(String providerCode, JsonNode root) {
        JsonNode result = root.path("Result");
        if (result.isMissingNode() || result.isNull()) {
            throw new RuntimeException("余额响应缺少 Result 字段");
        }
        String available = text(result, "AvailableBalance");
        String cash = text(result, "CashBalance");
        String credit = text(result, "CreditLimit");
        String freeze = text(result, "FreezeAmount");
        String arrears = text(result, "ArrearsBalance");
        String accountId = text(result, "AccountID");

        ProviderBalanceVO vo = new ProviderBalanceVO();
        vo.setProviderCode(providerCode);
        vo.setIsAvailable(parseDouble(available) > 0);

        ProviderBalanceVO.BalanceInfo info = new ProviderBalanceVO.BalanceInfo();
        info.setCurrency("CNY");
        // 通用语义映射：可用余额 ≈ 总余额；现金余额 ≈ 充值余额；信控额度 ≈ 赠金位（火山无赠金概念）
        info.setTotalBalance(orZero(available));
        info.setCashBalance(orZero(cash));
        info.setCreditLimit(orZero(credit));
        info.setFreezeAmount(orZero(freeze));
        info.setArrearsBalance(orZero(arrears));
        info.setAccountId(accountId == null ? "" : accountId);
        info.setToppedUpBalance(orZero(cash));
        info.setGrantedBalance(orZero(credit));

        vo.setBalanceInfos(List.of(info));
        vo.setUpdatedAt(System.currentTimeMillis());
        return vo;
    }

    // ==================== MiniMax（Token Plan 套餐额度） ====================

    /**
     * 查询 MiniMax Token Plan 套餐额度。
     * <p>官方接口 {@code GET /v1/token_plan/remains}（Coding Plan / Token Plan Key），
     * 返回 5 小时窗口与周窗口的剩余额度；MiniMax 未开放"现金余额"OpenAPI，故以「额度」呈现。</p>
     */
    private ProviderBalanceVO queryMiniMaxQuota(ProviderConfig config) {
        MiniMaxCodingPlanClient.QuotaSnapshot snapshot = miniMaxCodingPlanClient.queryQuota();
        if (snapshot == null) {
            throw new IllegalArgumentException("MiniMax 额度查询需要 Coding Plan / Token Plan Key，"
                    + "请在 Provider 的「扩展配置」中填写：{\"codingPlanKey\":\"sk-cp-...\"}");
        }
        ProviderBalanceVO vo = new ProviderBalanceVO();
        vo.setProviderCode(config.getCode());
        vo.setBalanceType("quota");
        vo.setIsAvailable(snapshot.remainingPercent() > 0);

        ProviderBalanceVO.BalanceInfo info = new ProviderBalanceVO.BalanceInfo();
        info.setCurrency("CNY");
        info.setTotalBalance(String.format(Locale.ROOT, "%.1f%%", snapshot.remainingPercent()));
        info.setRemainingPercent(snapshot.remainingPercent());
        info.setRemainingCount(snapshot.remainingCount() >= 0 ? snapshot.remainingCount() : null);
        info.setTotalCount(snapshot.totalCount() > 0 ? snapshot.totalCount() : null);
        info.setResetAt(snapshot.resetAt() > 0 ? snapshot.resetAt() : null);
        info.setPlanName(snapshot.planName());
        vo.setBalanceInfos(List.of(info));
        vo.setUpdatedAt(System.currentTimeMillis());
        return vo;
    }

    // ==================== 小米 MiMo（控制台余额，Cookie 鉴权） ====================

    /**
     * 查询小米 MiMo 账户余额。
     * <p>MiMo 官方未开放余额 OpenAPI：推理 Key（sk-）无法查询，只能用
     * {@code platform.xiaomimimo.com/api/v1/balance} + 控制台会话 Cookie（约 24 小时有效）。</p>
     */
    private ProviderBalanceVO queryMiMoBalance(ProviderConfig config) {
        JsonNode extra = parseExtraConfig(config.getExtraConfig());
        String cookie = text(extra, "mimoCookie", "mimo_cookie", "cookie");
        if (isBlank(cookie) || containsMask(cookie)) {
            throw new IllegalArgumentException("MiMo 余额需要控制台 Cookie（推理 API Key 无法查询余额）："
                    + "登录 platform.xiaomimimo.com → F12 网络 → 复制 balance 请求的 Cookie，"
                    + "填入扩展配置 {\"mimoCookie\":\"...\"}（约 24 小时有效，过期后重新粘贴）");
        }
        String responseBody = httpGet(MIMO_BALANCE_URL, cookie);
        return parseMiMoBalance(config.getCode(), responseBody);
    }

    /**
     * 解析 MiMo 控制台余额响应。
     * <p>响应形如 {@code {code:0,data:{balance,cashBalance,giftBalance,frozenBalance,
     * overdraftLimit,remainingOverdraftLimit}}}，字段做多写法兼容。</p>
     */
    private ProviderBalanceVO parseMiMoBalance(String providerCode, String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root.hasNonNull("code") && root.path("code").asInt(0) != 0) {
                throw new RuntimeException("MiMo 余额查询失败: " + root.path("message").asText("Cookie 可能已失效"));
            }
            JsonNode data = root.path("data").isObject() ? root.path("data") : root;

            String total = text(data, "balance", "totalBalance", "total_balance");
            String cash = text(data, "cashBalance", "cash_balance");
            String gift = text(data, "giftBalance", "gift_balance", "grantedBalance");
            String frozen = text(data, "frozenBalance", "frozen_balance");
            String overdraft = text(data, "remainingOverdraftLimit", "remaining_overdraft_limit");
            if (isBlank(total) && isBlank(cash) && isBlank(gift)) {
                throw new RuntimeException("MiMo 余额响应缺少余额字段（Cookie 可能已失效）");
            }

            ProviderBalanceVO vo = new ProviderBalanceVO();
            vo.setProviderCode(providerCode);
            vo.setBalanceType("balance");
            vo.setIsAvailable(parseDouble(isBlank(total) ? cash : total) > 0);

            ProviderBalanceVO.BalanceInfo info = new ProviderBalanceVO.BalanceInfo();
            info.setCurrency("CNY");
            info.setTotalBalance(orZero(isBlank(total) ? cash : total));
            info.setCashBalance(orZero(cash));
            info.setToppedUpBalance(orZero(cash));
            info.setGrantedBalance(orZero(gift));
            info.setFreezeAmount(orZero(frozen));
            info.setCreditLimit(orZero(overdraft));
            vo.setBalanceInfos(List.of(info));
            vo.setUpdatedAt(System.currentTimeMillis());
            return vo;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("解析 MiMo 余额响应失败: " + e.getMessage(), e);
        }
    }

    /** 轻量 GET（MiMo 控制台需要 Cookie 鉴权，不走 Provider 的 WebClient） */
    private String httpGet(String url, String cookie) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(BALANCE_TIMEOUT)
                    .header("Accept", "application/json")
                    .header("Cookie", cookie)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) CodeCraft")
                    .GET()
                    .build();
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status == 401 || status == 403) {
                throw new RuntimeException("MiMo Cookie 已失效（约 24 小时有效），请重新复制控制台 Cookie 并更新扩展配置");
            }
            if (status / 100 != 2) {
                throw new RuntimeException("余额查询失败: HTTP " + status);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("余额查询被中断", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("余额查询失败: " + e.getMessage(), e);
        }
    }

    // ==================== 通义千问（阿里云费用中心 BSS 余额） ====================

    /**
     * 查询通义千问（阿里云账号）余额。
     * <p>百炼 API Key 只能调用模型，账户余额需阿里云账号级 AK/SK 调用 BssOpenApi
     * {@code QueryAccountBalance}（RPC 签名）。</p>
     */
    private ProviderBalanceVO queryQwenBalance(ProviderConfig config) {
        JsonNode extra = parseExtraConfig(config.getExtraConfig());
        String accessKey = text(extra, "accessKey", "access_key", "ak");
        String secretKey = text(extra, "secretKey", "secret_key", "sk");
        if (isBlank(accessKey) || isBlank(secretKey) || containsMask(accessKey) || containsMask(secretKey)) {
            throw new IllegalArgumentException("通义千问余额查询需要阿里云 AccessKey/SecretKey，"
                    + "请在 Provider 的「扩展配置」中填写：{\"accessKey\":\"LTAI...\",\"secretKey\":\"...\"}");
        }
        JsonNode root = aliyunBillingClient.queryBalance(accessKey, secretKey);
        ProviderBalanceVO vo = parseQwenBalance(config.getCode(), root);
        log.info("通义千问余额查询成功: provider={}, available={}", config.getCode(),
                vo.getBalanceInfos().isEmpty() ? "-" : vo.getBalanceInfos().get(0).getTotalBalance());
        return vo;
    }

    /**
     * 解析阿里云 QueryAccountBalance 响应。
     * <p>响应示例：{@code {"Code":"200","Success":true,"Data":{"AvailableAmount":"10000.00",
     * "AvailableCashAmount":"10000.00","CreditAmount":"0.00","Currency":"CNY","QuotaLimit":"10000.00"}}}</p>
     */
    private ProviderBalanceVO parseQwenBalance(String providerCode, JsonNode root) {
        JsonNode data = root.path("Data");
        if (data.isMissingNode() || data.isNull()) {
            throw new RuntimeException("余额响应缺少 Data 字段");
        }
        String available = text(data, "AvailableAmount");
        String cash = text(data, "AvailableCashAmount");
        String credit = text(data, "CreditAmount");
        String currency = text(data, "Currency");
        if (isBlank(currency)) {
            currency = "CNY";
        }

        ProviderBalanceVO vo = new ProviderBalanceVO();
        vo.setProviderCode(providerCode);
        vo.setBalanceType("balance");
        vo.setIsAvailable(parseDouble(available) > 0);

        ProviderBalanceVO.BalanceInfo info = new ProviderBalanceVO.BalanceInfo();
        info.setCurrency(currency);
        info.setTotalBalance(orZero(available));
        info.setCashBalance(orZero(cash));
        info.setToppedUpBalance(orZero(cash));
        info.setCreditLimit(orZero(credit));
        vo.setBalanceInfos(List.of(info));
        vo.setUpdatedAt(System.currentTimeMillis());
        return vo;
    }

    // ==================== 能力判定 & 工具方法 ====================

    private String resolveTemplate(ProviderConfig config) {
        String template = config.getRequestTemplate();
        if (template == null || template.isBlank()) {
            template = config.getCode();
        }
        return template == null ? "" : template.trim();
    }

    private boolean isDeepSeek(ProviderConfig config, String template) {
        if (TEMPLATE_DEEPSEEK.equalsIgnoreCase(template)) {
            return true;
        }
        String host = hostOf(config.getBaseUrl());
        return DEEPSEEK_HOST.equalsIgnoreCase(host);
    }

    private boolean isVolcengine(ProviderConfig config, String template) {
        if (TEMPLATES_VOLCENGINE.contains(template.toLowerCase(Locale.ROOT))) {
            return true;
        }
        String host = hostOf(config.getBaseUrl());
        return host != null && host.toLowerCase(Locale.ROOT).endsWith("volces.com");
    }

    private boolean isMiniMax(ProviderConfig config, String template) {
        if (TEMPLATES_MINIMAX.contains(template.toLowerCase(Locale.ROOT))) {
            return true;
        }
        String host = hostOf(config.getBaseUrl());
        if (host == null) {
            return false;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.endsWith(MINIMAX_CN_SUFFIX) || lower.endsWith(MINIMAX_GLOBAL_SUFFIX);
    }

    private boolean isMiMo(ProviderConfig config, String template) {
        if (TEMPLATES_MIMO.contains(template.toLowerCase(Locale.ROOT))) {
            return true;
        }
        String host = hostOf(config.getBaseUrl());
        return host != null && host.toLowerCase(Locale.ROOT).endsWith(MIMO_HOST_SUFFIX);
    }

    private boolean isQwen(ProviderConfig config, String template) {
        if (TEMPLATES_QWEN.contains(template.toLowerCase(Locale.ROOT))) {
            return true;
        }
        String host = hostOf(config.getBaseUrl());
        return host != null && host.toLowerCase(Locale.ROOT).endsWith("aliyuncs.com");
    }

    private static String hostOf(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        try {
            return URI.create(baseUrl.trim()).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析扩展配置 JSON（非法 JSON 视为未配置，不影响主链路） */
    private JsonNode parseExtraConfig(String extraConfig) {
        if (extraConfig == null || extraConfig.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(extraConfig);
            return node.isObject() ? node : objectMapper.createObjectNode();
        } catch (Exception e) {
            log.warn("Provider 扩展配置不是合法 JSON，已忽略: {}", e.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    /** 按候选字段名取值（兼容大小写/下划线写法） */
    private static String text(JsonNode node, String... names) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull()) {
                String text = value.asText();
                if (!isBlank(text)) {
                    return text;
                }
            }
        }
        // 忽略大小写兜底
        var fields = node.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            for (String name : names) {
                if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isNull()) {
                    String text = entry.getValue().asText();
                    if (!isBlank(text)) {
                        return text;
                    }
                }
            }
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean containsMask(String s) {
        return s != null && s.contains("****");
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String orZero(String s) {
        return isBlank(s) ? "0.00" : s;
    }
}
