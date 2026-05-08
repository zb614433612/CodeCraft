package com.example.agentdeepseek.model.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class StockKlineDaily {

    private Long id;
    private String tsCode;
    private LocalDate tradeDate;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private BigDecimal preClose;
    private BigDecimal change;
    private BigDecimal pctChange;
    private Long vol;
    private BigDecimal amount;
    private BigDecimal turnover;
    private BigDecimal dividendAdjFactor;
    private boolean upPoint;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTsCode() { return tsCode; }
    public void setTsCode(String tsCode) { this.tsCode = tsCode; }

    public LocalDate getTradeDate() { return tradeDate; }
    public void setTradeDate(LocalDate tradeDate) { this.tradeDate = tradeDate; }

    public BigDecimal getOpen() { return open; }
    public void setOpen(BigDecimal open) { this.open = open; }

    public BigDecimal getHigh() { return high; }
    public void setHigh(BigDecimal high) { this.high = high; }

    public BigDecimal getLow() { return low; }
    public void setLow(BigDecimal low) { this.low = low; }

    public BigDecimal getClose() { return close; }
    public void setClose(BigDecimal close) { this.close = close; }

    public BigDecimal getPreClose() { return preClose; }
    public void setPreClose(BigDecimal preClose) { this.preClose = preClose; }

    public BigDecimal getChange() { return change; }
    public void setChange(BigDecimal change) { this.change = change; }

    public BigDecimal getPctChange() { return pctChange; }
    public void setPctChange(BigDecimal pctChange) { this.pctChange = pctChange; }

    public Long getVol() { return vol; }
    public void setVol(Long vol) { this.vol = vol; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public BigDecimal getTurnover() { return turnover; }
    public void setTurnover(BigDecimal turnover) { this.turnover = turnover; }

    public BigDecimal getDividendAdjFactor() { return dividendAdjFactor; }
    public void setDividendAdjFactor(BigDecimal dividendAdjFactor) { this.dividendAdjFactor = dividendAdjFactor; }

    public boolean isUpPoint() { return upPoint; }
    public void setUpPoint(boolean upPoint) { this.upPoint = upPoint; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
