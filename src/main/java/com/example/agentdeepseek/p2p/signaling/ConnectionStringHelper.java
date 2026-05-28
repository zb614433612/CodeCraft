package com.example.agentdeepseek.p2p.signaling;

import com.example.agentdeepseek.p2p.protocol.P2pConstants;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 连接串生成与解析
 * <p>
 * 格式：p2p://[2001:db8::1]:9527?peerId=xxx&fp=yyy&key=zzz
 * </p>
 */
public class ConnectionStringHelper {

    /**
     * 生成连接串
     */
    public static String generate(SignalingData data) {
        StringBuilder sb = new StringBuilder();
        sb.append(P2pConstants.CONNECTION_STRING_PREFIX);
        sb.append(data.getAddress());
        sb.append("?peerId=").append(data.getPeerId());
        sb.append("&fp=").append(data.getCertFingerprint());

        if (data.getAesKey() != null) {
            sb.append("&key=").append(urlEncode(data.getAesKey()));
        }

        return sb.toString();
    }

    /**
     * 解析连接串
     */
    public static SignalingData parse(String connectionString) {
        if (connectionString == null || connectionString.isEmpty()) {
            throw new IllegalArgumentException("Connection string is empty");
        }

        String prefix = P2pConstants.CONNECTION_STRING_PREFIX;
        if (!connectionString.startsWith(prefix)) {
            throw new IllegalArgumentException("Invalid connection string prefix. Expected: " + prefix);
        }

        String remainder = connectionString.substring(prefix.length());
        int queryIndex = remainder.indexOf('?');

        String address;
        String queryPart;
        if (queryIndex > 0) {
            address = remainder.substring(0, queryIndex);
            queryPart = remainder.substring(queryIndex + 1);
        } else {
            address = remainder;
            queryPart = "";
        }

        String peerId = getQueryParam(queryPart, "peerId");
        String fingerprint = getQueryParam(queryPart, "fp");
        String aesKey = getQueryParam(queryPart, "key");

        if (peerId == null || fingerprint == null) {
            throw new IllegalArgumentException("Missing required parameters: peerId or fp");
        }

        return new SignalingData(peerId, address, fingerprint, aesKey);
    }

    /**
     * 生成压缩连接串（Base64 编码 JSON，适合手动输入场景）
     */
    public static String generateCompact(SignalingData data) {
        String json = String.format(
                "{\"p\":\"%s\",\"a\":\"%s\",\"f\":\"%s\"%s}",
                data.getPeerId(),
                data.getAddress(),
                data.getCertFingerprint(),
                data.getAesKey() != null ? ",\"k\":\"" + data.getAesKey() + "\"" : ""
        );
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解析压缩连接串
     */
    public static SignalingData parseCompact(String compactString) {
        try {
            String json = new String(
                    Base64.getUrlDecoder().decode(compactString), StandardCharsets.UTF_8);
            return parseFromJson(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid compact connection string", e);
        }
    }

    /**
     * 从 JSON 解析（简单手动解析，避免引入 Jackson 依赖）
     */
    private static SignalingData parseFromJson(String json) {
        String peerId = extractJsonValue(json, "p");
        String address = extractJsonValue(json, "a");
        String fingerprint = extractJsonValue(json, "f");
        String aesKey = extractJsonValue(json, "k");

        if (peerId == null || address == null || fingerprint == null) {
            throw new IllegalArgumentException("Missing required fields in signaling JSON");
        }

        return new SignalingData(peerId, address, fingerprint, aesKey);
    }

    private static String extractJsonValue(String json, String key) {
        String searchKey = "\"" + key + "\":\"";
        int start = json.indexOf(searchKey);
        if (start < 0) return null;
        start += searchKey.length();
        int end = json.indexOf('"', start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    private static String getQueryParam(String query, String key) {
        if (query == null || query.isEmpty()) return null;
        String searchKey = key + "=";
        for (String part : query.split("&")) {
            if (part.startsWith(searchKey)) {
                String value = part.substring(searchKey.length());
                return urlDecode(value);
            }
        }
        return null;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
