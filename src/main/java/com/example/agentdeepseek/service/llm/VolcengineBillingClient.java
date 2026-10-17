package com.example.agentdeepseek.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 火山引擎费用中心（billing）OpenAPI 客户端 —— 账户余额查询。
 *
 * <h3>接口</h3>
 * <pre>
 * POST https://billing.volcengineapi.com/?Action=QueryBalanceAcct&amp;Version=2022-01-01
 * Authorization: HMAC-SHA256 Credential=&lt;AK&gt;/&lt;scope&gt;, SignedHeaders=..., Signature=...
 * </pre>
 * 官方文档：<a href="https://www.volcengine.com/docs/82379/1263279">QueryBalanceAcct - 查询用户账户余额信息</a>
 *
 * <h3>为什么需要 AK/SK</h3>
 * <p>费用中心属于火山引擎账号级 OpenAPI，使用 <b>AccessKey/SecretKey + V4 签名</b>鉴权，
 * 与方舟（Ark）的 API Key 是两套凭据。因此该能力通过 Provider 的「扩展配置」单独配置，
 * 未配置时仅跳过余额查询，不影响对话主链路。</p>
 *
 * <h3>签名算法（火山引擎 V4，与 AWS SigV4 同构）</h3>
 * <ol>
 *   <li>CanonicalRequest = Method + URI + Query + CanonicalHeaders + SignedHeaders + SHA256(Body)</li>
 *   <li>StringToSign = "HMAC-SHA256" + X-Date + Scope + SHA256(CanonicalRequest)</li>
 *   <li>SigningKey = HMAC(HMAC(HMAC(HMAC(SK, date), region), service), "request")</li>
 * </ol>
 */
@Slf4j
@Component
public class VolcengineBillingClient {

    /** 费用中心 API 域名（余额查询固定走该 endpoint） */
    private static final String HOST = "billing.volcengineapi.com";

    /** OpenAPI 服务名 */
    private static final String SERVICE = "billing";

    /** 接口版本 */
    private static final String API_VERSION = "2022-01-01";

    /** 接口名 */
    private static final String ACTION = "QueryBalanceAcct";

    /** 默认区域（费用中心为全局接口，区域仅参与签名） */
    public static final String DEFAULT_REGION = "cn-beijing";

    /** 签名算法标识 */
    private static final String ALGORITHM = "HMAC-SHA256";

    private static final DateTimeFormatter X_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final ObjectMapper objectMapper;

    public VolcengineBillingClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 查询火山引擎资金账户余额。
     *
     * @param accessKey 火山引擎 AccessKey（账号级）
     * @param secretKey 火山引擎 SecretKey
     * @param region    区域（为空时用 cn-beijing，仅参与签名）
     * @return 原始响应 JSON（含 Result.AvailableBalance / CashBalance / ArrearsBalance 等）
     * @throws RuntimeException 网络、鉴权或业务错误
     */
    public JsonNode queryBalance(String accessKey, String secretKey, String region) {
        String effectiveRegion = (region == null || region.isBlank()) ? DEFAULT_REGION : region.trim();
        String body = "{}";
        String xDate = ZonedDateTime.now(ZoneOffset.UTC).format(X_DATE_FORMATTER);
        String url = "https://" + HOST + "/?Action=" + ACTION + "&Version=" + API_VERSION;

        String authorization = buildAuthorization(accessKey, secretKey, effectiveRegion, xDate, body);
        String contentHash = sha256Hex(body);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("X-Date", xDate)
                .header("X-Content-Sha256", contentHash)
                .header("Authorization", authorization)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String responseBody = response.body();

            if (response.statusCode() / 100 != 2) {
                throw new RuntimeException("余额查询失败: HTTP " + response.statusCode()
                        + " - " + describeError(responseBody));
            }
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode error = root.path("ResponseMetadata").path("Error");
            if (!error.isMissingNode() && !error.isNull() && error.hasNonNull("Code")) {
                throw new RuntimeException("余额查询失败: " + error.path("Code").asText()
                        + " - " + error.path("Message").asText(""));
            }
            return root;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("余额查询被中断", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.warn("火山引擎余额查询异常: {}", e.getMessage());
            throw new RuntimeException("余额查询失败: " + e.getMessage(), e);
        }
    }

    // ==================== 签名（包级可见，便于单元测试） ====================

    /**
     * 构建 Authorization 请求头（火山引擎 V4 签名）。
     *
     * <p>包级可见：便于用固定时间戳做确定性单元测试。</p>
     */
    String buildAuthorization(String accessKey, String secretKey, String region, String xDate, String body) {
        String date = xDate.substring(0, 8);
        String contentHash = sha256Hex(body);
        String canonicalQuery = "Action=" + ACTION + "&Version=" + API_VERSION;
        String signedHeaders = "content-type;host;x-content-sha256;x-date";
        String canonicalHeaders = "content-type:application/json\n"
                + "host:" + HOST + "\n"
                + "x-content-sha256:" + contentHash + "\n"
                + "x-date:" + xDate + "\n";

        String canonicalRequest = "POST\n"
                + "/\n"
                + canonicalQuery + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + contentHash;

        String credentialScope = date + "/" + region + "/" + SERVICE + "/request";
        String stringToSign = ALGORITHM + "\n"
                + xDate + "\n"
                + credentialScope + "\n"
                + sha256Hex(canonicalRequest);

        byte[] kDate = hmac(secretKey, date);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, SERVICE);
        byte[] kSigning = hmac(kService, "request");
        String signature = hex(hmac(kSigning, stringToSign));

        return ALGORITHM + " Credential=" + accessKey + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;
    }

    private static byte[] hmac(String key, String data) {
        return hmac(key.getBytes(StandardCharsets.UTF_8), data);
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 计算失败", e);
        }
    }

    static String sha256Hex(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 计算失败", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 从错误响应里提取可读信息（火山错误结构：ResponseMetadata.Error.Code/Message） */
    private String describeError(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "(空响应)";
        }
        try {
            JsonNode error = objectMapper.readTree(responseBody).path("ResponseMetadata").path("Error");
            if (error.hasNonNull("Code")) {
                return error.path("Code").asText() + " - " + error.path("Message").asText("");
            }
        } catch (Exception ignored) {
            // 非 JSON 错误体，走截断兜底
        }
        return responseBody.length() > 200 ? responseBody.substring(0, 200) + "..." : responseBody;
    }
}
