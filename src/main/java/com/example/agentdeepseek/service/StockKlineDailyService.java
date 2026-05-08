package com.example.agentdeepseek.service;

import com.example.agentdeepseek.model.entity.StockKlineDaily;

import java.time.LocalDate;
import java.util.List;

public interface StockKlineDailyService {
    List<StockKlineDaily> findByTsCode(String tsCode, String adjust);

    List<StockKlineDaily> findByDate(LocalDate date);
}
