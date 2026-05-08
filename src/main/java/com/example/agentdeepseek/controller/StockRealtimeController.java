package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.model.entity.StockRealtime;
import com.example.agentdeepseek.service.StockRealtimeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/realtime")
@Tag(name = "实时行情", description = "查询实时行情数据并更新数据库快照表")
public class StockRealtimeController {

    private final StockRealtimeService stockRealtimeService;

    public StockRealtimeController(StockRealtimeService stockRealtimeService) {
        this.stockRealtimeService = stockRealtimeService;
    }

    @PostMapping
    @Operation(summary = "查询实时行情（最多80只），自动更新数据库快照")
    public ApiResponse<List<StockRealtime>> fetchRealtime(@RequestBody List<String> tsCodes) {
        if (tsCodes.isEmpty()) {
            return ApiResponse.error(400, "tsCode列表不能为空");
        }
        if (tsCodes.size() > 80) {
            tsCodes = tsCodes.subList(0, 80);
        }
        List<StockRealtime> list = stockRealtimeService.fetchAndSave(tsCodes);
        return ApiResponse.success(list);
    }
}
