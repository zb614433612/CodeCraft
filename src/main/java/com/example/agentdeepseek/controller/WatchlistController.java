package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.model.entity.WatchlistGroup;
import com.example.agentdeepseek.model.entity.WatchlistStock;
import com.example.agentdeepseek.service.WatchlistService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/watchlist")
@Tag(name = "自选股票", description = "管理自选股分组")
public class WatchlistController {

    private static final Logger log = LoggerFactory.getLogger(WatchlistController.class);
    private final WatchlistService watchlistService;

    public WatchlistController(WatchlistService watchlistService) {
        this.watchlistService = watchlistService;
    }

    @PostMapping("/group")
    @Operation(summary = "新增自选分组")
    public ApiResponse<WatchlistGroup> createGroup(
            @RequestBody @Parameter(description = "分组名称") Map<String, String> body,
            HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            return ApiResponse.error(400, "分组名称不能为空");
        }
        WatchlistGroup group = watchlistService.createGroup(userId, name);
        return ApiResponse.success(group);
    }

    @PutMapping("/group/{id}")
    @Operation(summary = "编辑分组名称")
    public ApiResponse<WatchlistGroup> updateGroup(
            @PathVariable @Parameter(description = "分组ID") Long id,
            @RequestBody @Parameter(description = "分组名称") Map<String, String> body,
            HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            return ApiResponse.error(400, "分组名称不能为空");
        }
        try {
            WatchlistGroup group = watchlistService.updateGroup(userId, id, name);
            return ApiResponse.success(group);
        } catch (NoSuchElementException e) {
            return ApiResponse.error(404, e.getMessage());
        } catch (SecurityException e) {
            return ApiResponse.error(403, e.getMessage());
        }
    }

    @DeleteMapping("/group/{id}")
    @Operation(summary = "删除分组（级联删除组内股票）")
    public ApiResponse<Void> deleteGroup(
            @PathVariable @Parameter(description = "分组ID") Long id,
            HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        try {
            watchlistService.deleteGroup(userId, id);
            return ApiResponse.success(null, "删除成功");
        } catch (NoSuchElementException e) {
            return ApiResponse.error(404, e.getMessage());
        } catch (SecurityException e) {
            return ApiResponse.error(403, e.getMessage());
        }
    }

    @GetMapping("/groups")
    @Operation(summary = "查询所有分组及组内股票")
    public ApiResponse<List<Map<String, Object>>> listGroups(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        List<Map<String, Object>> list = watchlistService.listGroups(userId);
        return ApiResponse.success(list);
    }

    @PostMapping("/group/{id}/stocks")
    @Operation(summary = "批量添加股票到分组")
    public ApiResponse<List<WatchlistStock>> addStocks(
            @PathVariable @Parameter(description = "分组ID") Long id,
            @RequestBody @Parameter(description = "股票代码列表") List<String> tsCodes,
            HttpServletRequest request) {
        if (tsCodes == null || tsCodes.isEmpty()) {
            return ApiResponse.error(400, "股票代码列表不能为空");
        }
        Long userId = (Long) request.getAttribute("userId");
        try {
            List<WatchlistStock> stocks = watchlistService.addStocks(userId, id, tsCodes);
            return ApiResponse.success(stocks);
        } catch (NoSuchElementException e) {
            return ApiResponse.error(404, e.getMessage());
        } catch (SecurityException e) {
            return ApiResponse.error(403, e.getMessage());
        }
    }

    @DeleteMapping("/group/{id}/stocks")
    @Operation(summary = "批量删除分组内股票")
    public ApiResponse<Void> removeStocks(
            @PathVariable @Parameter(description = "分组ID") Long id,
            @RequestBody @Parameter(description = "股票代码列表") List<String> tsCodes,
            HttpServletRequest request) {
        if (tsCodes == null || tsCodes.isEmpty()) {
            return ApiResponse.error(400, "股票代码列表不能为空");
        }
        Long userId = (Long) request.getAttribute("userId");
        try {
            watchlistService.removeStocks(userId, id, tsCodes);
            return ApiResponse.success(null, "删除成功");
        } catch (NoSuchElementException e) {
            return ApiResponse.error(404, e.getMessage());
        } catch (SecurityException e) {
            return ApiResponse.error(403, e.getMessage());
        }
    }
}
