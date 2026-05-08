package com.example.agentdeepseek.model.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class StockAdjFactor {

    private Long id;
    private String tsCode;
    private LocalDate tradeDate;
    private BigDecimal adjFactor;
    private BigDecimal foreAdjFactor;
    private BigDecimal backAdjFactor;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTsCode() { return tsCode; }
    public void setTsCode(String tsCode) { this.tsCode = tsCode; }

    public LocalDate getTradeDate() { return tradeDate; }
    public void setTradeDate(LocalDate tradeDate) { this.tradeDate = tradeDate; }

    public BigDecimal getAdjFactor() { return adjFactor; }
    public void setAdjFactor(BigDecimal adjFactor) { this.adjFactor = adjFactor; }

    public BigDecimal getForeAdjFactor() { return foreAdjFactor; }
    public void setForeAdjFactor(BigDecimal foreAdjFactor) { this.foreAdjFactor = foreAdjFactor; }

    public BigDecimal getBackAdjFactor() { return backAdjFactor; }
    public void setBackAdjFactor(BigDecimal backAdjFactor) { this.backAdjFactor = backAdjFactor; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
