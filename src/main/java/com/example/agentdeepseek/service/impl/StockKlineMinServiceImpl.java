package com.example.agentdeepseek.service.impl;

import com.example.agentdeepseek.mapper.StockKlineMinMapper;
import com.example.agentdeepseek.model.entity.StockKlineMin;
import com.example.agentdeepseek.service.StockKlineMinService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class StockKlineMinServiceImpl implements StockKlineMinService {

    private static final Logger log = LoggerFactory.getLogger(StockKlineMinServiceImpl.class);
    private static final String EM_KLINE_URL = "http://push2his.eastmoney.com/api/qt/stock/kline/get";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final StockKlineMinMapper mapper;

    public StockKlineMinServiceImpl(StockKlineMinMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<StockKlineMin> findByTsCodeAndDate(String tsCode, LocalDate date) {
        if (date == null) {
            date = LocalDate.now();
        }

        // 今天走API，历史走DB
        if (date.equals(LocalDate.now())) {
            List<StockKlineMin> fromApi = fetchFromApi(tsCode, date);
            if (fromApi != null) {
                return fromApi;
            }
            log.warn("API fetch failed for {}, fallback to DB", tsCode);
        }
        return mapper.findByTsCodeAndTradeDate(tsCode, date);
    }

    private List<StockKlineMin> fetchFromApi(String tsCode, LocalDate date) {
        String secid = toSecid(tsCode);
        if (secid == null) return null;

        String dateStr = date.format(DATE_FMT);
        try {
            String url = EM_KLINE_URL + "?secid=" + secid
                    + "&fields1=f1,f2,f3,f4,f5,f6"
                    + "&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61"
                    + "&klt=1&fqt=1"
                    + "&beg=0&end=20500000"
                    + "&ut=7eea3edcaed734bea9cbfc24409ed989";

            URI uri = new URI(url);
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setRequestProperty("Referer", "https://quote.eastmoney.com/");

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }

            return parseKlineResponse(sb.toString(), tsCode, dateStr);
        } catch (Exception e) {
            log.warn("Eastmoney kline API error: {}", e.getMessage());
            return null;
        }
    }

    private List<StockKlineMin> parseKlineResponse(String raw, String tsCode, String dateStr) {
        List<StockKlineMin> result = new ArrayList<>();
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(raw);
            JsonNode klines = root.path("data").path("klines");
            if (klines.isMissingNode() || !klines.isArray()) return result;

            for (JsonNode node : klines) {
                String line = node.asText();
                String[] parts = line.split(",", -1);
                if (parts.length < 7) continue;

                // parts[0]: "2026-05-04 09:31"
                String dt = parts[0].trim();
                if (!dt.contains(" ")) continue;
                String[] dtParts = dt.split(" ");
                String tradeDate = dtParts[0];
                // 只返回指定日期的数据
                if (!tradeDate.equals(dateStr)) continue;

                String minute = dtParts[1];
                if (minute.length() == 5) minute += ":00";

                StockKlineMin k = new StockKlineMin();
                k.setTsCode(tsCode);
                k.setTradeDate(LocalDate.parse(tradeDate));
                k.setMinute(minute);
                k.setFreq(1);
                k.setOpen(new BigDecimal(parts[1]));
                k.setClose(new BigDecimal(parts[2]));
                k.setHigh(new BigDecimal(parts[3]));
                k.setLow(new BigDecimal(parts[4]));
                k.setVol(Long.parseLong(parts[5]));
                k.setAmount(new BigDecimal(parts[6]));
                result.add(k);
            }
        } catch (Exception e) {
            log.warn("Parse kline error: {}", e.getMessage());
        }
        return result;
    }

    private String toSecid(String tsCode) {
        if (tsCode == null || !tsCode.contains(".")) return null;
        String[] parts = tsCode.split("\\.");
        String symbol = parts[0];
        String market = parts[1];
        String prefix = "SH".equalsIgnoreCase(market) ? "1" : "0";
        return prefix + "." + symbol;
    }
}
