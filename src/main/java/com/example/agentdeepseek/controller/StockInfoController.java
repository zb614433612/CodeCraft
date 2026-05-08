package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.common.response.PageResult;
import com.example.agentdeepseek.model.entity.StockInfo;
import com.example.agentdeepseek.service.StockInfoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/stock-info")
@Tag(name = "股票基础信息", description = "stock_info 表的分页条件查询")
public class StockInfoController {

    private final StockInfoService stockInfoService;

    public StockInfoController(StockInfoService stockInfoService) {
        this.stockInfoService = stockInfoService;
    }

    @GetMapping
    @Operation(summary = "分页条件查询股票列表")
    public ApiResponse<PageResult<StockInfo>> pageQuery(
            @RequestParam(required = false) @Parameter(description = "搜索关键词（代码/名称）") String keyword,
            @RequestParam(required = false) @Parameter(description = "市场：SH / SZ") String market,
            @RequestParam(required = false) @Parameter(description = "状态：1=正常") Integer status,
            @RequestParam(defaultValue = "1") @Parameter(description = "页码") int page,
            @RequestParam(defaultValue = "20") @Parameter(description = "每页条数") int pageSize) {
        PageResult<StockInfo> result = stockInfoService.pageQuery(keyword, market, status, page, pageSize);
        return ApiResponse.success(result);
    }
}
