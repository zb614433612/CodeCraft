package com.example.agentdeepseek.service;

import com.example.agentdeepseek.common.response.PageResult;
import com.example.agentdeepseek.model.entity.StockInfo;

public interface StockInfoService {
    PageResult<StockInfo> pageQuery(String keyword, String market, Integer status, int page, int pageSize);
}
