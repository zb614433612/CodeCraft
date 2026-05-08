package com.example.agentdeepseek.service;

import com.example.agentdeepseek.model.entity.StockKlineMin;

import java.time.LocalDate;
import java.util.List;

public interface StockKlineMinService {
    List<StockKlineMin> findByTsCodeAndDate(String tsCode, LocalDate date);
}
