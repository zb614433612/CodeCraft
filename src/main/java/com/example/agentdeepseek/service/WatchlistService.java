package com.example.agentdeepseek.service;

import com.example.agentdeepseek.model.entity.WatchlistGroup;
import com.example.agentdeepseek.model.entity.WatchlistStock;

import java.util.List;
import java.util.Map;

public interface WatchlistService {

    WatchlistGroup createGroup(Long userId, String name);

    WatchlistGroup updateGroup(Long userId, Long groupId, String name);

    void deleteGroup(Long userId, Long groupId);

    List<Map<String, Object>> listGroups(Long userId);

    List<WatchlistStock> addStocks(Long userId, Long groupId, List<String> tsCodes);

    void removeStocks(Long userId, Long groupId, List<String> tsCodes);
}
