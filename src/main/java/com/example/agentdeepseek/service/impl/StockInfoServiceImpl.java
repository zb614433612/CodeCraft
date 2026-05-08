package com.example.agentdeepseek.service.impl;

import com.example.agentdeepseek.common.response.PageResult;
import com.example.agentdeepseek.mapper.StockInfoMapper;
import com.example.agentdeepseek.model.entity.StockInfo;
import com.example.agentdeepseek.service.StockInfoService;
import org.springframework.stereotype.Service;

@Service
public class StockInfoServiceImpl implements StockInfoService {

    private final StockInfoMapper stockInfoMapper;

    public StockInfoServiceImpl(StockInfoMapper stockInfoMapper) {
        this.stockInfoMapper = stockInfoMapper;
    }

    @Override
    public PageResult<StockInfo> pageQuery(String keyword, String market, Integer status,
                                           int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        long total = stockInfoMapper.countByCondition(keyword, market, status);
        var records = stockInfoMapper.selectByCondition(keyword, market, status, offset, pageSize);
        return new PageResult<>(records, total, page, pageSize);
    }
}
