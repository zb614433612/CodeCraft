package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.model.entity.StockKlineMin;
import com.example.agentdeepseek.service.StockKlineMinService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/kline-min")
@Tag(name = "分钟K线数据", description = "查询1分钟K线，今天走API实时获取，历史查表")
public class StockKlineMinController {

    private final StockKlineMinService stockKlineMinService;

    public StockKlineMinController(StockKlineMinService stockKlineMinService) {
        this.stockKlineMinService = stockKlineMinService;
    }

    @GetMapping("/{tsCode}")
    @Operation(summary = "查询分钟K线", description = "date为空默认今天，今天走实时API，历史查stock_kline_min表")
    public ApiResponse<List<StockKlineMin>> findByTsCodeAndDate(
            @PathVariable @Parameter(description = "股票代码，如 000001.SZ") String tsCode,
            @RequestParam(required = false) @Parameter(description = "日期 yyyy-MM-dd，默认今天") String date) {

        LocalDate queryDate = (date != null && !date.isEmpty()) ? LocalDate.parse(date) : LocalDate.now();
        List<StockKlineMin> list = stockKlineMinService.findByTsCodeAndDate(tsCode, queryDate);
        return ApiResponse.success(list);
    }
}
