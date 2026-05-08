package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.model.entity.StockKlineDaily;
import com.example.agentdeepseek.service.StockKlineDailyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/kline-daily")
@Tag(name = "日K线数据", description = "stock_kline_daily 表查询")
public class StockKlineDailyController {

    private final StockKlineDailyService stockKlineDailyService;

    public StockKlineDailyController(StockKlineDailyService stockKlineDailyService) {
        this.stockKlineDailyService = stockKlineDailyService;
    }

    @GetMapping("/{tsCode}")
    @Operation(summary = "查询股票全部日K线数据，支持前复权")
    public ApiResponse<List<StockKlineDaily>> findByTsCode(
            @PathVariable @Parameter(description = "股票代码，如 000001.SZ") String tsCode,
            @RequestParam(defaultValue = "raw") @Parameter(description = "复权方式：raw=不复权, forward=前复权") String adjust) {
        List<StockKlineDaily> list = stockKlineDailyService.findByTsCode(tsCode, adjust);
        return ApiResponse.success(list);
    }

    @GetMapping("/date/{tradeDate}")
    @Operation(summary = "查询指定日期全部股票的日K线数据")
    public ApiResponse<List<StockKlineDaily>> findByDate(
            @PathVariable @Parameter(description = "日期 yyyy-MM-dd") String tradeDate) {
        LocalDate date = LocalDate.parse(tradeDate);
        List<StockKlineDaily> list = stockKlineDailyService.findByDate(date);
        return ApiResponse.success(list);
    }
}
