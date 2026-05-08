package com.example.agentdeepseek.service.impl;

import com.example.agentdeepseek.mapper.StockRealtimeMapper;
import com.example.agentdeepseek.model.entity.StockRealtime;
import com.example.agentdeepseek.service.StockRealtimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class StockRealtimeServiceImpl implements StockRealtimeService {

    private static final Logger log = LoggerFactory.getLogger(StockRealtimeServiceImpl.class);
    private static final String TENCENT_URL = "http://qt.gtimg.cn/q=";

    private final StockRealtimeMapper mapper;

    public StockRealtimeServiceImpl(StockRealtimeMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<StockRealtime> fetchAndSave(List<String> tsCodes) {
        if (tsCodes == null || tsCodes.isEmpty()) {
            return List.of();
        }
        if (tsCodes.size() > 80) {
            tsCodes = tsCodes.subList(0, 80);
        }

        // 1. 转腾讯格式: 000001.SZ -> sz000001
        String queryStr = tsCodes.stream()
                .map(this::toTencentCode)
                .collect(Collectors.joining(","));

        // 2. 调腾讯接口
        String raw = callTencent(queryStr);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        // 3. 解析
        List<StockRealtime> records = parseResponse(raw, tsCodes);

        // 4. 批量写入数据库
        if (!records.isEmpty()) {
            try {
                mapper.upsertBatch(records);
                log.info("Saved {} realtime records", records.size());
            } catch (Exception e) {
                log.warn("Batch upsert failed, trying single: {}", e.getMessage());
                for (StockRealtime r : records) {
                    try {
                        mapper.upsert(r);
                    } catch (Exception ex) {
                        log.warn("Skip {}: {}", r.getTsCode(), ex.getMessage());
                    }
                }
            }
        }

        return records;
    }

    private String toTencentCode(String tsCode) {
        if (tsCode == null || !tsCode.contains(".")) return tsCode;
        String[] parts = tsCode.split("\\.");
        String symbol = parts[0];
        String market = parts[1];
        String prefix = "SH".equalsIgnoreCase(market) ? "sh" : "sz";
        return prefix + symbol;
    }

    private String callTencent(String query) {
        try {
            URI uri = new URI(TENCENT_URL + query);
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "GBK"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Tencent API call failed: {}", e.getMessage());
            return null;
        }
    }

    private List<StockRealtime> parseResponse(String raw, List<String> tsCodes) {
        // Build tsCode -> Tencent code mapping
        java.util.Map<String, String> codeToTsCode = new java.util.HashMap<>();
        for (String tc : tsCodes) {
            codeToTsCode.put(toTencentCode(tc), tc);
        }

        List<StockRealtime> records = new ArrayList<>();
        String[] lines = raw.split("\n");

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            try {
                // Format: v_sh600519="...fields...";
                int start = line.indexOf('"');
                int end = line.lastIndexOf('"');
                if (start < 0 || end <= start) continue;

                // Extract tencent code from v_sh600519
                String keyPart = line.contains("=") ? line.substring(0, line.indexOf('=')).trim() : "";
                String tcCode = keyPart.startsWith("v_") ? keyPart.substring(2) : keyPart;

                String dataStr = line.substring(start + 1, end);
                String[] fields = dataStr.split("~", -1);

                String tsCode = codeToTsCode.get(tcCode);
                if (tsCode == null) continue;

                StockRealtime rec = parseFields(fields, tsCode);
                if (rec != null) {
                    records.add(rec);
                }
            } catch (Exception e) {
                log.debug("Parse error: {}", e.getMessage());
            }
        }

        return records;
    }

    private StockRealtime parseFields(String[] fields, String tsCode) {
        if (fields.length < 48) return null;

        try {
            StockRealtime r = new StockRealtime();
            r.setTsCode(tsCode);
            r.setName(valStr(fields, 1, ""));
            r.setPrice(valDec(fields, 3));
            r.setOpen(valDec(fields, 5));
            r.setHigh(valDec(fields, 33));
            r.setLow(valDec(fields, 34));
            r.setPreClose(valDec(fields, 4));
            r.setChange_(valDec(fields, 31));
            r.setPctChange(valDec(fields, 32));
            r.setVolume(valLong(fields, 6, v -> v * 100));
            r.setAmount(valDec(fields, 37, v -> v.multiply(BigDecimal.valueOf(10000))));
            r.setBidPrices(makeJsonArray(fields, 9, 11, 13, 15, 17));
            r.setBidVolumes(makeJsonArray(fields, 10, 12, 14, 16, 18));
            r.setAskPrices(makeJsonArray(fields, 19, 21, 23, 25, 27));
            r.setAskVolumes(makeJsonArray(fields, 20, 22, 24, 26, 28));
            r.setTurnoverRate(valDec(fields, 38));
            r.setPe(valDec(fields, 39));
            r.setPb(valDec(fields, 46));
            r.setTotalMv(valDec(fields, 45, v -> v.multiply(BigDecimal.valueOf(10000))));
            r.setCircMv(valDec(fields, 44, v -> v.multiply(BigDecimal.valueOf(10000))));
            r.setLimitUp(valDec(fields, 47));
            r.setLimitDown(valDec(fields, 48));
            r.setBuyVol(valLong(fields, 7, v -> v * 100));
            r.setSellVol(valLong(fields, 8, v -> v * 100));
            r.setAmplitude(valDec(fields, 43));
            r.setOrderDiff(valLong(fields, 50));
            r.setAvgPrice(valDec(fields, 51));
            r.setVolumeRatio(valDec(fields, 52));
            r.setPeTtm(valDec(fields, 53));
            r.setEps(valDec(fields, 62));
            r.setBvps(valDec(fields, 65));
            r.setCapitalReserve(valDec(fields, 66));
            r.setNetProfitGrowth(valDec(fields, 63));
            r.setRevenueGrowth(valDec(fields, 64));
            r.setTotalShares(valLong(fields, 72));
            r.setCircShares(valLong(fields, 73));
            r.setPcf(valDec(fields, 71));

            // raw_data
            java.util.List<String> allFields = java.util.Arrays.asList(fields);
            r.setRawData(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(allFields));

            // update_time
            r.setUpdateTime(parseUpdateTime(valStr(fields, 30, null)));

            return r;
        } catch (Exception e) {
            log.debug("Parse fields error for {}: {}", tsCode, e.getMessage());
            return null;
        }
    }

    private String valStr(String[] fields, int idx, String def) {
        return idx < fields.length && fields[idx] != null && !fields[idx].isEmpty()
                ? fields[idx].trim() : def;
    }

    private BigDecimal valDec(String[] fields, int idx) {
        return valDec(fields, idx, null);
    }

    private BigDecimal valDec(String[] fields, int idx, java.util.function.UnaryOperator<BigDecimal> transform) {
        String v = valStr(fields, idx, null);
        if (v == null) return null;
        try {
            BigDecimal bd = new BigDecimal(v);
            return transform != null ? transform.apply(bd) : bd;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long valLong(String[] fields, int idx) {
        return valLong(fields, idx, null);
    }

    private Long valLong(String[] fields, int idx, java.util.function.UnaryOperator<Long> transform) {
        String v = valStr(fields, idx, null);
        if (v == null) return null;
        try {
            long l = Long.parseLong(v);
            return transform != null ? transform.apply(l) : l;
        } catch (NumberFormatException e) {
            try {
                double d = Double.parseDouble(v);
                long l = (long) d;
                return transform != null ? transform.apply(l) : l;
            } catch (NumberFormatException ex) {
                return null;
            }
        }
    }

    private String makeJsonArray(String[] fields, int... indices) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (int idx : indices) {
            if (!first) sb.append(",");
            String v = valStr(fields, idx, null);
            sb.append(v != null ? v : "0");
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    private LocalDateTime parseUpdateTime(String raw) {
        if (raw == null || raw.isEmpty()) return LocalDateTime.now();
        try {
            // Format: 20260430150000 or 150000
            String s = raw.trim();
            if (s.length() >= 14) {
                return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            } else if (s.length() >= 6) {
                String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
                return LocalDateTime.parse(today + s, DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            }
        } catch (Exception ignored) {
        }
        return LocalDateTime.now();
    }
}
