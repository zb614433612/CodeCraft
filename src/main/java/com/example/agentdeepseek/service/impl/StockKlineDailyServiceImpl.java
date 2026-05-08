package com.example.agentdeepseek.service.impl;

import com.example.agentdeepseek.mapper.StockAdjFactorMapper;
import com.example.agentdeepseek.mapper.StockKlineDailyMapper;
import com.example.agentdeepseek.model.entity.StockAdjFactor;
import com.example.agentdeepseek.model.entity.StockKlineDaily;
import com.example.agentdeepseek.service.StockKlineDailyService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

@Service
public class StockKlineDailyServiceImpl implements StockKlineDailyService {

    private static final List<Integer> MA_PERIODS = Arrays.asList(5, 10, 20, 60, 120, 250);

    private final StockKlineDailyMapper klineMapper;
    private final StockAdjFactorMapper adjFactorMapper;

    public StockKlineDailyServiceImpl(StockKlineDailyMapper klineMapper,
                                      StockAdjFactorMapper adjFactorMapper) {
        this.klineMapper = klineMapper;
        this.adjFactorMapper = adjFactorMapper;
    }

    @Override
    public List<StockKlineDaily> findByDate(LocalDate date) {
        return klineMapper.findByDate(date);
    }

    @Override
    public List<StockKlineDaily> findByTsCode(String tsCode, String adjust) {
        List<StockKlineDaily> klines = klineMapper.findByTsCode(tsCode);
        List<StockAdjFactor> factors = adjFactorMapper.findByTsCode(tsCode);

        markDividendDates(klines, factors);

        if ("forward".equals(adjust) && !klines.isEmpty()) {
            applyForwardAdjustment(klines, factors);
        }

        // 标记起爆点
        markUpPoints(klines);

        return klines;
    }

    // ====================== upPoint detection ======================

    private void markUpPoints(List<StockKlineDaily> klines) {
        if (klines == null || klines.size() < 250) return;

        Map<Integer, List<Double>> sma = calculateSMA(klines);
        List<Double> ma5 = sma.get(5), ma10 = sma.get(10), ma20 = sma.get(20);
        List<Double> ma60 = sma.get(60), ma120 = sma.get(120), ma250 = sma.get(250);

        int index = 0, index1 = 0, maLow250 = 0;
        List<LocalDate> forecastPoint = new ArrayList<>();
        double maxHighSinceMa5LostTop = 0;
        boolean ma5BelowAllEver = false;

        for (int i = 0; i < klines.size(); i++) {
            Double m5 = ma5.get(i), m10 = ma10.get(i), m20 = ma20.get(i);
            Double m60 = ma60.get(i), m120 = ma120.get(i), m250 = ma250.get(i);
            if (m5 == null || m10 == null || m20 == null || m60 == null || m120 == null || m250 == null) continue;

            StockKlineDaily k = klines.get(i), prev = klines.get(i - 1);
            double low = k.getLow().doubleValue();
            double close = k.getClose().doubleValue();
            double high = k.getHigh().doubleValue();
            double pctChg = k.getPctChange() != null ? k.getPctChange().doubleValue() : 0;
            double change = k.getChange() != null ? k.getChange().doubleValue() : 0;

            boolean ma5IsTop = m5 >= m10 && m5 >= m20 && m5 >= m60 && m5 >= m120 && m5 >= m250;

            // 条件1：MA5从最高均线下穿 → 重置最高价跟踪
            if (i > 0) {
                Double pm5 = ma5.get(i - 1), pm10 = ma10.get(i - 1), pm20 = ma20.get(i - 1);
                Double pm60 = ma60.get(i - 1), pm120 = ma120.get(i - 1), pm250 = ma250.get(i - 1);
                if (pm5 != null && pm10 != null && pm20 != null && pm60 != null && pm120 != null && pm250 != null
                        && pm5 >= pm10 && pm5 >= pm20 && pm5 >= pm60 && pm5 >= pm120 && pm5 >= pm250 && !ma5IsTop) {
                    maxHighSinceMa5LostTop = 0;
                }
            }

            // 条件2设置：MA5低于所有均线
            if (m5 < m10 && m5 < m20 && m5 < m60 && m5 < m120 && m5 < m250) {
                ma5BelowAllEver = true;
            }

            // Phase 1: MA5低于所有短周期均线
            if (m5 < m10 && m5 < m20 && m5 < m60 && m5 < m120) index = i;
            // Phase 2: 价格跌破MA120和MA250
            if (index != 0 && low < m120 && low < m250) index1 = i;

            // Phase 3: 涨停 → upPoint
            if (pctChg > 9.8 && index1 != 0
                    && m5 >= m10 && m5 >= m20 && m5 >= m60
                    && !forecastPoint.isEmpty() && ma5BelowAllEver
                    && prev.getClose().doubleValue() >= maxHighSinceMa5LostTop
                    && ma5IsTop
                    && !(prev.getOpen().compareTo(prev.getHigh()) == 0
                         && prev.getHigh().compareTo(prev.getLow()) == 0
                         && prev.getLow().compareTo(prev.getClose()) == 0)) {
                if (round2(low) != round2(close)
                        && prev.getTradeDate().equals(forecastPoint.get(forecastPoint.size() - 1))
                        && !k.getOpen().equals(k.getClose())) {
                    boolean notDup = forecastPoint.size() <= 1
                            || !forecastPoint.get(forecastPoint.size() - 2).equals(klines.get(i - 2).getTradeDate());
                    if (notDup) k.setUpPoint(true);
                }
                index = 0;
                index1 = 0;
            }

            // Phase 4: MA5站上均线但未涨停 → 重置
            if (index1 != 0 && m5 >= m10 && m5 >= m20 && m5 >= m60 && !forecastPoint.isEmpty()) {
                index = 0;
                index1 = 0;
            }

            // 趋势超跌判断（bePoint）
            if (low > m5 && low > m10 && low > m20 && low > m60 && low > m120 && low > m250
                    && m5 > m10 && m10 > m20 && m20 > m60 && m60 > m120 && m120 > m250) {
                maLow250 = 1;
            } else if (low <= m5 && low <= m10 && low <= m20 && low <= m60 && low <= m120
                    && round2(low) <= round2(m250) && maLow250 == 1) {
                maLow250 = 0;
            }

            // forecastPoint预测
            if (index1 != 0) {
                StockKlineDaily prev2 = klines.get(i - 2), prev3 = klines.get(i - 3);
                double fc = (close * 1.1 + close
                        + prev.getClose().doubleValue() + prev2.getClose().doubleValue() + prev3.getClose().doubleValue()) / 5;
                double nb250 = (close * 1.1 + close
                        + prev.getClose().doubleValue() + prev2.getClose().doubleValue()
                        + close * 1.1 * 1.1) / 5;

                if (fc >= m10 && fc >= m20 && fc >= m60) {
                    boolean prevPctChgOk = prev.getPctChange() != null && prev.getPctChange().doubleValue() < 9;
                    boolean numberOk = nb250 > m120 && nb250 > m250;

                    if (prevPctChgOk && numberOk) {
                        if (change > 0) {
                            forecastPoint.add(k.getTradeDate());
                        } else if (forecastPoint.isEmpty()) {
                            index = 0;
                            index1 = 0;
                        }
                    } else {
                        index = 0;
                        index1 = 0;
                    }
                }
            }

            // 条件2重置：MA5涨回最高时重置（在Phase 3之后，避免涨停日误重置）
            if (ma5IsTop) ma5BelowAllEver = false;

            // 更新最高价跟踪（在Phase 3之后，排除今日）
            maxHighSinceMa5LostTop = Math.max(maxHighSinceMa5LostTop, high);
        }
    }

    private Map<Integer, List<Double>> calculateSMA(List<StockKlineDaily> klines) {
        Map<Integer, List<Double>> result = new HashMap<>();
        for (int period : MA_PERIODS) {
            List<Double> maList = new ArrayList<>();
            Queue<Double> window = new LinkedList<>();
            double sum = 0;
            for (StockKlineDaily k : klines) {
                double close = k.getClose().doubleValue();
                window.add(close);
                sum += close;
                if (window.size() > period) {
                    sum -= window.poll();
                }
                maList.add(window.size() == period ? round2(sum / period) : null);
            }
            result.put(period, maList);
        }
        return result;
    }

    private double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    // ====================== original logic ======================

    private void markDividendDates(List<StockKlineDaily> klines, List<StockAdjFactor> factors) {
        if (factors == null || factors.isEmpty()) return;

        Map<LocalDate, BigDecimal> dateFactorMap = new HashMap<>();
        for (StockAdjFactor f : factors) {
            if (f.getAdjFactor() != null) {
                dateFactorMap.put(f.getTradeDate(), f.getAdjFactor());
            }
        }
        if (dateFactorMap.isEmpty()) return;

        for (StockKlineDaily k : klines) {
            BigDecimal af = dateFactorMap.get(k.getTradeDate());
            if (af != null) {
                k.setDividendAdjFactor(af);
            }
        }
    }

    private void applyForwardAdjustment(List<StockKlineDaily> klines,
                                        List<StockAdjFactor> factors) {
        if (factors == null || factors.isEmpty()) {
            return;
        }
        NavigableMap<LocalDate, BigDecimal> factorMap = new TreeMap<>();
        for (StockAdjFactor f : factors) {
            if (f.getForeAdjFactor() != null) {
                factorMap.put(f.getTradeDate(), f.getForeAdjFactor());
            }
        }
        if (factorMap.isEmpty()) {
            return;
        }
        BigDecimal defaultFactor = factorMap.firstEntry().getValue();
        if (defaultFactor == null || defaultFactor.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        for (StockKlineDaily k : klines) {
            LocalDate date = k.getTradeDate();
            BigDecimal factor = defaultFactor;
            var entry = factorMap.floorEntry(date);
            if (entry != null) {
                factor = entry.getValue();
            }
            if (factor == null || factor.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            k.setOpen(multiply(k.getOpen(), factor));
            k.setHigh(multiply(k.getHigh(), factor));
            k.setLow(multiply(k.getLow(), factor));
            k.setClose(multiply(k.getClose(), factor));
            k.setPreClose(multiply(k.getPreClose(), factor));
        }
    }

    private BigDecimal multiply(BigDecimal value, BigDecimal factor) {
        if (value == null) return null;
        return value.multiply(factor).setScale(4, RoundingMode.HALF_UP);
    }
}
