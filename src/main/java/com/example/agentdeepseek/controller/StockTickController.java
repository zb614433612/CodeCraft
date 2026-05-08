package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.response.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

@RestController
@RequestMapping("/api/tick")
@Tag(name = "分时成交明细", description = "查询单只股票的分时成交明细")
public class StockTickController {

    private static final Logger log = LoggerFactory.getLogger(StockTickController.class);
    private static final String EM_DETAIL_URL = "http://push2.eastmoney.com/api/qt/stock/details/get";
    private static final String TENCENT_MINUTE_URL = "https://ifzq.gtimg.cn/appstock/app/minute/query";
    /** 东方财富限流：每 3 秒最多请求一次 */
    private static final long EM_RATE_LIMIT_MS = 3000;
    private static final Object EM_LOCK = new Object();
    private static long emLastRequestTime = 0;

    @GetMapping("/{tsCode}")
    @Operation(summary = "查询分时成交明细（当日）")
    public ApiResponse<List<Map<String, Object>>> getTicks(
            @PathVariable @Parameter(description = "股票代码，如 000001.SZ") String tsCode) {

        if (tsCode == null || !tsCode.contains(".")) {
            return ApiResponse.error(400, "无效tsCode格式，应为 000001.SZ");
        }

        // 优先东方财富（包含逐笔成交方向）-> 降级到腾讯分钟聚合
        String secid = toSecid(tsCode);
        if (secid != null) {
            String raw = callEastmoney(secid);
            if (raw != null) {
                List<Map<String, Object>> ticks = parseEastmoney(raw);
                if (!ticks.isEmpty()) {
                    return ApiResponse.success(ticks);
                }
            }
        }

        String tCode = toTencentCode(tsCode);
        List<Map<String, Object>> ticks = callTencent(tCode);
        if (ticks != null && !ticks.isEmpty()) {
            return ApiResponse.success(ticks);
        }

        return ApiResponse.error(500, "分时数据获取失败，请稍后重试");
    }

    /**
     * tsCode -> Tencent code (e.g. 000001.SZ -> sz000001, 600519.SH -> sh600519)
     */
    private String toTencentCode(String tsCode) {
        String[] parts = tsCode.split("\\.");
        return parts[1].toLowerCase() + parts[0];
    }

    /**
     * 调用腾讯分时接口（主力数据源）
     * 返回格式：["0930 11.50 10436 12001400.00", ...]
     *           HHMM 价格 成交量(股) 成交额(元)
     */
    private List<Map<String, Object>> callTencent(String tCode) {
        try {
            String url = TENCENT_MINUTE_URL + "?code=" + tCode;
            URI uri = new URI(url);
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "*/*");
            conn.setInstanceFollowRedirects(true);

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            return parseTencentMinute(sb.toString(), tCode);
        } catch (Exception e) {
            log.warn("Tencent minute API error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析腾讯分时数据
     * "0930 11.50 10436 12001400.00" -> {time, price, volume, amount, direction=0, type="-"}
     */
    private List<Map<String, Object>> parseTencentMinute(String raw, String tCode) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(raw);
            JsonNode dataNode = root.path("data").path(tCode).path("data").path("data");
            if (dataNode.isMissingNode() || !dataNode.isArray()) return result;

            for (JsonNode node : dataNode) {
                String line = node.asText();
                String[] parts = line.split(" ", -1);
                if (parts.length < 4) continue;

                String timeStr = parts[0]; // "0930"
                if (timeStr.length() == 4) {
                    timeStr = timeStr.substring(0, 2) + ":" + timeStr.substring(2) + ":00";
                }

                Map<String, Object> tick = new LinkedHashMap<>();
                tick.put("time", timeStr);
                tick.put("price", new BigDecimal(parts[1]));
                tick.put("volume", Long.parseLong(parts[2]));
                tick.put("amount", new BigDecimal(parts[3]));
                tick.put("direction", 0);
                tick.put("type", "-");
                result.add(tick);
            }
        } catch (Exception e) {
            log.warn("Parse Tencent minute error: {}", e.getMessage());
        }
        return result;
    }

    // ---- Eastmoney fallback ----

    private String toSecid(String tsCode) {
        String[] parts = tsCode.split("\\.");
        String symbol = parts[0];
        String market = parts[1];
        String prefix = "SH".equalsIgnoreCase(market) ? "1" : "0";
        return prefix + "." + symbol;
    }

    private String callEastmoney(String secid) {
        // 限流：每 3 秒最多一次东方财富请求
        synchronized (EM_LOCK) {
            long now = System.currentTimeMillis();
            long wait = EM_RATE_LIMIT_MS - (now - emLastRequestTime);
            if (wait > 0) {
                log.info("Eastmoney rate limited, waiting {}ms", wait);
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            emLastRequestTime = System.currentTimeMillis();
        }

        String[] endpoints = {
                "http://push2.eastmoney.com/api/qt/stock/details/get",
                "https://push2.eastmoney.com/api/qt/stock/details/get",
                "http://push2his.eastmoney.com/api/qt/stock/details/get"
        };
        for (String baseUrl : endpoints) {
            String result = tryFetchEastmoney(baseUrl, secid);
            if (result != null) return result;
        }
        return null;
    }

    private String tryFetchEastmoney(String baseUrl, String secid) {
        try {
            String url = baseUrl + "?secid=" + secid
                    + "&fields1=f1,f2,f3&fields2=f51,f52,f53,f55"
                    + "&pos=0&end=100000"
                    + "&ut=7eea3edcaed734bea9cbfc24409ed989";
            URI uri = new URI(url);
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            conn.setRequestProperty("Referer", "https://quote.eastmoney.com/");
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    "gzip".equals(conn.getContentEncoding())
                            ? new GZIPInputStream(conn.getInputStream())
                            : conn.getInputStream(),
                    "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Eastmoney detail API error ({}): {}", baseUrl, e.getMessage());
            return null;
        }
    }

    private List<Map<String, Object>> parseEastmoney(String raw) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(raw);
            JsonNode details = root.path("data").path("details");
            if (details.isMissingNode() || !details.isArray()) return result;

            for (JsonNode node : details) {
                String line = node.asText();
                String[] parts = line.split(",", -1);
                if (parts.length < 4) continue;

                Map<String, Object> tick = new LinkedHashMap<>();
                tick.put("time", parts[0]);
                tick.put("price", new BigDecimal(parts[1]));
                tick.put("volume", Long.parseLong(parts[2]));
                int direction = Integer.parseInt(parts[3]);
                tick.put("direction", direction);
                String type;
                if (direction == 1) type = "买";
                else if (direction == 2) type = "卖";
                else if (direction == 4) type = "集合竞价";
                else type = String.valueOf(direction);
                tick.put("type", type);
                result.add(tick);
            }
        } catch (Exception e) {
            log.warn("Parse Eastmoney details error: {}", e.getMessage());
        }
        return result;
    }
}
