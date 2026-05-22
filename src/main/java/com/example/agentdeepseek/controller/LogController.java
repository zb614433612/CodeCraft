package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.log.LogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/logs")
@Tag(name = "日志管理", description = "历史日志搜索与读取")
public class LogController {

    private final LogService logService;

    public LogController(LogService logService) {
        this.logService = logService;
    }

    @Operation(summary = "获取日志日期列表")
    @GetMapping("/dates")
    public Map<String, Object> getAvailableDates() {
        List<String> dates = logService.getAvailableDates();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", dates);
        return result;
    }

    @Operation(summary = "搜索历史日志")
    @GetMapping("/search")
    public Map<String, Object> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String date,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 1) page = 1;
        if (size < 1) size = 20;
        if (size > 100) size = 100;
        LogService.SearchResult result = logService.search(keyword, level, date, page, size);
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("data", result);
        return response;
    }

    @Operation(summary = "读取最新日志", description = "从日志文件末尾读取最新 N 行，用于虚拟滚动展示")
    @GetMapping("/tail")
    public Map<String, Object> tail(
            @Parameter(description = "最多行数") @RequestParam(defaultValue = "500") int maxLines,
            @Parameter(description = "跳过行数（滚动加载更多时使用）") @RequestParam(defaultValue = "0") int skip) {
        if (maxLines < 1) maxLines = 500;
        if (maxLines > 5000) maxLines = 5000;
        if (skip < 0) skip = 0;
        List<String> lines = logService.tail(maxLines, skip);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", lines);
        return result;
    }
}
