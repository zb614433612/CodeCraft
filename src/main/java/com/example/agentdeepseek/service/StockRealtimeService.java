package com.example.agentdeepseek.service;

import com.example.agentdeepseek.model.entity.StockRealtime;

import java.util.List;

public interface StockRealtimeService {
    /**
     * 查询指定股票的实时行情，更新到数据库并返回
     * @param tsCodes 股票代码列表，最多80个
     * @return 实时行情数据
     */
    List<StockRealtime> fetchAndSave(List<String> tsCodes);
}
