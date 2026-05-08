package com.example.agentdeepseek.service.impl;

import com.example.agentdeepseek.mapper.StockInfoMapper;
import com.example.agentdeepseek.mapper.WatchlistGroupMapper;
import com.example.agentdeepseek.mapper.WatchlistStockMapper;
import com.example.agentdeepseek.model.entity.StockInfo;
import com.example.agentdeepseek.model.entity.WatchlistGroup;
import com.example.agentdeepseek.model.entity.WatchlistStock;
import com.example.agentdeepseek.service.WatchlistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class WatchlistServiceImpl implements WatchlistService {

    private static final Logger log = LoggerFactory.getLogger(WatchlistServiceImpl.class);

    private final WatchlistGroupMapper groupMapper;
    private final WatchlistStockMapper stockMapper;
    private final StockInfoMapper stockInfoMapper;

    public WatchlistServiceImpl(WatchlistGroupMapper groupMapper,
                                WatchlistStockMapper stockMapper,
                                StockInfoMapper stockInfoMapper) {
        this.groupMapper = groupMapper;
        this.stockMapper = stockMapper;
        this.stockInfoMapper = stockInfoMapper;
    }

    @Override
    public WatchlistGroup createGroup(Long userId, String name) {
        WatchlistGroup group = new WatchlistGroup();
        group.setUserId(userId);
        group.setName(name);
        group.setSortOrder(0);
        groupMapper.insert(group);
        return group;
    }

    @Override
    public WatchlistGroup updateGroup(Long userId, Long groupId, String name) {
        WatchlistGroup group = groupMapper.selectById(groupId);
        if (group == null) {
            throw new NoSuchElementException("分组不存在");
        }
        if (!userId.equals(group.getUserId())) {
            throw new SecurityException("无权操作该分组");
        }
        groupMapper.updateName(groupId, name);
        group.setName(name);
        return group;
    }

    @Override
    @Transactional
    public void deleteGroup(Long userId, Long groupId) {
        WatchlistGroup group = groupMapper.selectById(groupId);
        if (group == null) {
            throw new NoSuchElementException("分组不存在");
        }
        if (!userId.equals(group.getUserId())) {
            throw new SecurityException("无权操作该分组");
        }
        stockMapper.deleteByGroupId(groupId);
        groupMapper.deleteById(groupId);
        log.info("Deleted watchlist group {} ({}) with stocks", groupId, group.getName());
    }

    @Override
    public List<Map<String, Object>> listGroups(Long userId) {
        List<WatchlistGroup> groups = groupMapper.selectByUserId(userId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (WatchlistGroup g : groups) {
            List<WatchlistStock> stocks = stockMapper.selectByGroupId(g.getId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", g.getId());
            item.put("name", g.getName());
            item.put("sortOrder", g.getSortOrder());
            item.put("createdAt", g.getCreatedAt());
            item.put("updatedAt", g.getUpdatedAt());
            item.put("stocks", stocks);
            result.add(item);
        }
        return result;
    }

    @Override
    @Transactional
    public List<WatchlistStock> addStocks(Long userId, Long groupId, List<String> tsCodes) {
        WatchlistGroup group = groupMapper.selectById(groupId);
        if (group == null) {
            throw new NoSuchElementException("分组不存在");
        }
        if (!userId.equals(group.getUserId())) {
            throw new SecurityException("无权操作该分组");
        }

        List<WatchlistStock> stocks = new ArrayList<>();
        int sortOrder = 0;
        for (String tsCode : tsCodes) {
            // 尝试获取股票名称
            String stockName = null;
            try {
                StockInfo info = stockInfoMapper.findByTsCode(tsCode);
                if (info != null) {
                    stockName = info.getName();
                }
            } catch (Exception e) {
                log.debug("Skip stock name lookup for {}: {}", tsCode, e.getMessage());
            }
            WatchlistStock s = new WatchlistStock();
            s.setGroupId(groupId);
            s.setTsCode(tsCode);
            s.setStockName(stockName);
            s.setSortOrder(sortOrder++);
            stocks.add(s);
        }

        if (!stocks.isEmpty()) {
            stockMapper.batchInsert(stocks);
        }
        return stocks;
    }

    @Override
    @Transactional
    public void removeStocks(Long userId, Long groupId, List<String> tsCodes) {
        WatchlistGroup group = groupMapper.selectById(groupId);
        if (group == null) {
            throw new NoSuchElementException("分组不存在");
        }
        if (!userId.equals(group.getUserId())) {
            throw new SecurityException("无权操作该分组");
        }
        int deleted = stockMapper.batchDelete(groupId, tsCodes);
        log.info("Removed {} stocks from group {}", deleted, groupId);
    }
}
