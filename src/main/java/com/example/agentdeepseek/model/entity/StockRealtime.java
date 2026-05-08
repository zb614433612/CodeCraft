package com.example.agentdeepseek.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockRealtime {
    private String tsCode;
    private String name;
    private BigDecimal price;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal preClose;
    private BigDecimal change_;
    private BigDecimal pctChange;
    private Long volume;
    private BigDecimal amount;
    private String bidPrices;
    private String bidVolumes;
    private String askPrices;
    private String askVolumes;
    private BigDecimal turnoverRate;
    private BigDecimal pe;
    private BigDecimal pb;
    private BigDecimal totalMv;
    private BigDecimal circMv;
    private BigDecimal limitUp;
    private BigDecimal limitDown;
    private Long buyVol;
    private Long sellVol;
    private BigDecimal amplitude;
    private Long orderDiff;
    private BigDecimal avgPrice;
    private BigDecimal volumeRatio;
    private BigDecimal peTtm;
    private BigDecimal eps;
    private BigDecimal bvps;
    private BigDecimal capitalReserve;
    private BigDecimal netProfitGrowth;
    private BigDecimal revenueGrowth;
    private Long totalShares;
    private Long circShares;
    private BigDecimal pcf;
    private String rawData;
    private LocalDateTime updateTime;
}
