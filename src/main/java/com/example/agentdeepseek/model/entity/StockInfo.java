package com.example.agentdeepseek.model.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class StockInfo {

    private Integer id;
    private String tsCode;
    private String symbol;
    private String market;
    private String name;
    private LocalDate listDate;
    private Long totalShare;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public String getTsCode() { return tsCode; }
    public void setTsCode(String tsCode) { this.tsCode = tsCode; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getMarket() { return market; }
    public void setMarket(String market) { this.market = market; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public LocalDate getListDate() { return listDate; }
    public void setListDate(LocalDate listDate) { this.listDate = listDate; }

    public Long getTotalShare() { return totalShare; }
    public void setTotalShare(Long totalShare) { this.totalShare = totalShare; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
