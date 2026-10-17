package com.example.agentdeepseek.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 阿里云费用中心（BssOpenApi）客户端 —— 账户余额查询。
 *
 * <h3>接口</h3>
 * <pre>
 * GET https://business.aliyuncs.com/?Action=QueryAccountBalance&amp;Version=2017-12-14&amp;Format=JSON
 *     &amp;AccessKeyId=...&amp;SignatureMethod=HMAC-SHA1&amp;SignatureVersion=1.0
 *     &amp;SignatureNonce=...&amp;Timestamp=...&amp;Signature=...
 * </pre>
 * 官方文档：<a href="https://help.aliyun.com/zh/user-center/developer-reference/api-bssopenapi-2017-12-14-queryaccountbalance">QueryAccountBalance</a>
 *
 * <h3>为什么需要 AK/SK</h3>
 * <p>费用中心属于阿里云账号级 RPC OpenAPI（RPC 签名风格，HMAC-SHA1），
 * 与百炼（DashScope）的 API Key 是两套凭据。该能力通过 Provider 的「扩展配置」单独配置，
 * 未配置时仅跳过余额查询，不影响对话主链路。</p>
 *
 * <h3>签名算法（阿里云 RPC 风格）</h3>
 * <ol>
 *   <li>参数（含公共参数，不含 Signature）按参数名排序并 percentEncode</li>
 *   <li>StringToSign = "GET&amp;%2F&amp;" + percentEncode(CanonicalizedQueryString)</li>
 *   <li>Signature = Base64(HMAC-SHA1(AccessKeySecret + "&amp;", StringToSign))</li>
 * </ol>
 */
@Slf4j
@Component
public class AliyunBillingClient {

    /** 费用中心 endpoint（RPC 风格） */
    private static final String ENDPOINT = "https://business.aliyuncs.com/";

    /** 接口名 / 版本 */
    private static final String ACTION = "QueryAccountBalance";
    private static final String API_VERSION = "2017-12-14";

    /** 默认区域（余额为账号级接口，区域仅作为公共参数占位） */
    public static final String DEFAULT_REGION = "cn-hangzhou";

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final ObjectMapper objectMapper;

    public AliyunBillingClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 查询阿里云账户余额。
     *
     * @param accessKey 阿里云 AccessKeyId（账号/RAM 用户）
     * @param secretKey 阿里云 AccessKeySecret
     * @return 响应 JSON（Data.AvailableAmount / AvailableCashAmount / CreditAmount / Currency ...）
     * @throws RuntimeException 网络、鉴权或业务错误
     */
    public JsonNode queryBalance(String accessKey, String secretKey) {
        String timestamp = ZonedDateTime.now(ZoneOffset.UTC).format(TIMESTAMP_FORMATTER);
        String nonce = UUID.randomUUID().toString();
        String signedUrl = buildSignedUrl(accessKey, secretKey, timestamp, nonce);

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(signedUrl))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String body = response.body();

            if (response.statusCode() / 100 != 2) {
                throw new RuntimeException("余额查询失败: HTTP " + response.statusCode() + " - " + describeError(body));
            }
            JsonNode root = objectMapper.readTree(body);
            if (!root.path("Success").asBoolean(false)) {
                String code = root.path("Code").asText("");
                if ("200".equals(code)) {
                    return root;
                }
                throw new RuntimeException("余额查询失败: " + code + " - " + root.path("Message").asText(""));
            }
            return root;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("余额查询被中断", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.warn("阿里云余额查询异常: {}", e.getMessage());
            throw new RuntimeException("余额查询失败: " + e.getMessage(), e);
        }
    }

    // ==================== 签名（包级可见，便于单元测试） ====================

    /**
     * 构造带签名的完整请求 URL（阿里云 RPC 签名）。
     *
     * <p>包级可见：便于用固定 timestamp / nonce 做确定性单元测试。</p>
     */
    String buildSignedUrl(String accessKey, String secretKey, String timestamp, String nonce) {
        Map<String, String> params = new TreeMap<>();
        params.put("Action", ACTION);
        params.put("Version", API_VERSION);
        params.put("Format", "JSON");
        params.put("AccessKeyId", accessKey);
        params.put("SignatureMethod", "HMAC-SHA1");
        params.put("SignatureVersion", "1.0");
        params.put("SignatureNonce", nonce);
        params.put("Timestamp", timestamp);

        String canonicalizedQueryString = canonicalize(params);
        String stringToSign = "GET" + "&" + percentEncode("/") + "&" + percentEncode(canonicalizedQueryString);
        String signature = sign(stringToSign, secretKey);

        return ENDPOINT + "?" + canonicalizedQueryString + "&Signature=" + percentEncode(signature);
    }

    private static String canonicalize(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(percentEncode(entry.getKey())).append('=').append(percentEncode(entry.getValue()));
        }
        return sb.toString();
    }

    /** RFC 3986 编码（阿里云签名要求：+ → %20、* → %2A、~ 保留） */
    static String percentEncode(String value) {
        if (value == null) {
            return "";
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    private static String sign(String stringToSign, String secretKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec((secretKey + "&").getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] digest = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA1 计算失败", e);
        }
    }

    private String describeError(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "(空响应)";
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root.hasNonNull("Code")) {
                return root.path("Code").asText() + " - " + root.path("Message").asText("");
            }
        } catch (Exception ignored) {
            // 非 JSON 错误体，走截断兜底
        }
        return responseBody.length() > 200 ? responseBody.substring(0, 200) + "..." : responseBody;
    }
}
