package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.mapper.StockInfoMapper;
import com.example.agentdeepseek.mapper.WatchlistGroupMapper;
import com.example.agentdeepseek.mapper.WatchlistStockMapper;
import com.example.agentdeepseek.model.entity.StockInfo;
import com.example.agentdeepseek.model.entity.StockKlineDaily;
import com.example.agentdeepseek.model.entity.WatchlistGroup;
import com.example.agentdeepseek.model.entity.WatchlistStock;
import com.example.agentdeepseek.service.StockKlineDailyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 数据补全接口 — 手动触发日K线/分钟K线/实时行情的补全
 */
@Slf4j
@RestController
@RequestMapping("/api/backfill")
@Tag(name = "数据补全", description = "手动触发日K线、分钟K线、实时行情数据的补全")
public class BackfillController {

    private static final DateTimeFormatter TF = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String PYTHON = "C:\\Python313\\python.exe";
    private static final String SCRIPT_DIR = "E:\\zbcode\\agent-deepseek\\";
    private static final long SYSTEM_USER_ID = 1L;

    private final AtomicBoolean dailyRunning = new AtomicBoolean(false);
    private final AtomicBoolean klineMinRunning = new AtomicBoolean(false);
    private final AtomicBoolean realtimeRunning = new AtomicBoolean(false);
    private final AtomicBoolean upPointRunning = new AtomicBoolean(false);

    private final ConcurrentHashMap<String, Process> runningProcesses = new ConcurrentHashMap<>();

    private final StockInfoMapper stockInfoMapper;
    private final StockKlineDailyService klineService;
    private final WatchlistGroupMapper groupMapper;
    private final WatchlistStockMapper stockMapper;

    public BackfillController(StockInfoMapper stockInfoMapper,
                               StockKlineDailyService klineService,
                               WatchlistGroupMapper groupMapper,
                               WatchlistStockMapper stockMapper) {
        this.stockInfoMapper = stockInfoMapper;
        this.klineService = klineService;
        this.groupMapper = groupMapper;
        this.stockMapper = stockMapper;
    }

    @PostMapping("/daily")
    @Operation(summary = "补全日K线数据", description = "mode=realtime（默认）从实时行情表构建今日K线；mode=baostock 从baostock增量补全历史")
    public ApiResponse<Map<String, Object>> backfillDaily(
            @RequestParam(defaultValue = "realtime") @Parameter(description = "数据源：realtime(默认) / baostock") String mode) {
        if (!dailyRunning.compareAndSet(false, true)) {
            return ApiResponse.error(400, "日K线补全任务已在运行中");
        }
        Map<String, Object> result;
        if ("baostock".equals(mode)) {
            result = runScript("etl_kline_daily.py", dailyRunning);
        } else {
            result = runScriptArgs(new String[]{"etl_kline_daily.py", "--mode", "realtime"}, dailyRunning);
        }
        return ApiResponse.success(result);
    }

    @PostMapping("/kline-min")
    @Operation(summary = "补全1分钟K线数据（AKShare，最近约5个交易日）")
    public ApiResponse<Map<String, Object>> backfillKlineMin() {
        if (!klineMinRunning.compareAndSet(false, true)) {
            return ApiResponse.error(400, "分钟K线补全任务已在运行中");
        }
        Map<String, Object> result = runScript("etl_kline_min_akshare.py", klineMinRunning);
        return ApiResponse.success(result);
    }

    @PostMapping("/realtime")
    @Operation(summary = "更新实时行情快照")
    public ApiResponse<Map<String, Object>> backfillRealtime() {
        if (!realtimeRunning.compareAndSet(false, true)) {
            return ApiResponse.error(400, "实时行情更新任务已在运行中");
        }
        Map<String, Object> result = runScript("etl_realtime.py", realtimeRunning);
        return ApiResponse.success(result);
    }

    @PostMapping("/all")
    @Operation(summary = "依次补全全部数据（日K线 → 1分钟K线 → 实时行情）")
    public ApiResponse<Map<String, Object>> backfillAll() {
        Map<String, Object> summary = new LinkedHashMap<>();
        long t0 = System.currentTimeMillis();

        // 1. 日K线
        if (dailyRunning.compareAndSet(false, true)) {
            Map<String, Object> r = runScript("etl_kline_daily.py", dailyRunning);
            summary.put("daily", r);
        } else {
            summary.put("daily", Map.of("status", "skipped", "reason", "已有任务在运行"));
        }

        // 2. 1分钟K线（AKShare，最近约5个交易日）
        if (klineMinRunning.compareAndSet(false, true)) {
            Map<String, Object> r = runScript("etl_kline_min_akshare.py", klineMinRunning);
            summary.put("klineMin", r);
        } else {
            summary.put("klineMin", Map.of("status", "skipped", "reason", "已有任务在运行"));
        }

        // 3. 实时行情
        if (realtimeRunning.compareAndSet(false, true)) {
            Map<String, Object> r = runScript("etl_realtime.py", realtimeRunning);
            summary.put("realtime", r);
        } else {
            summary.put("realtime", Map.of("status", "skipped", "reason", "已有任务在运行"));
        }

        summary.put("totalElapsed", (System.currentTimeMillis() - t0) / 1000 + "s");
        return ApiResponse.success(summary);
    }

    @GetMapping("/status")
    @Operation(summary = "查看当前补全任务运行状态")
    public ApiResponse<Map<String, Object>> status() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("dailyRunning", dailyRunning.get());
        s.put("klineMinRunning", klineMinRunning.get());
        s.put("realtimeRunning", realtimeRunning.get());
        s.put("activeProcesses", runningProcesses.keySet());
        return ApiResponse.success(s);
    }

    @PostMapping("/up-point")
    @Operation(summary = "回测起爆点，按日期创建自选组")
    public ApiResponse<Map<String, Object>> backfillUpPoints(
            @RequestParam(required = false, defaultValue = "2024-01-01") String startDate,
            @RequestParam(required = false) String endDate) {
        if (!upPointRunning.compareAndSet(false, true)) {
            return ApiResponse.error(400, "起爆点回测任务已在运行中");
        }
        LocalDate start = LocalDate.parse(startDate);
        LocalDate end = endDate != null ? LocalDate.parse(endDate) : LocalDate.now(ZoneId.of("Asia/Shanghai"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("startTime", LocalDateTime.now().format(TF));
        result.put("startDate", start.toString());
        result.put("endDate", end.toString());
        result.put("status", "started");

        Thread t = new Thread(() -> {
            try {
                log.info("[upPoint] 回测开始，{} ~ {}", start, end);
                List<StockInfo> eligible = stockInfoMapper.findAllEligibleBasic();
                Map<String, StockInfo> infoMap = eligible.stream()
                        .collect(Collectors.toMap(StockInfo::getTsCode, si -> si));
                log.info("[upPoint] 排除 30/68/ST/*ST 后，共 {} 只股票", infoMap.size());

                // key: tradeDate, value: list of tsCode
                Map<LocalDate, List<String>> dateMap = new TreeMap<>();
                List<String> codes = new ArrayList<>(infoMap.keySet());

                for (int idx = 0; idx < codes.size(); idx++) {
                    String tsCode = codes.get(idx);
                    try {
                        List<StockKlineDaily> klines = klineService.findByTsCode(tsCode, "forward");
                        for (StockKlineDaily k : klines) {
                            if (k.isUpPoint() && !k.getTradeDate().isBefore(start) && !k.getTradeDate().isAfter(end)) {
                                dateMap.computeIfAbsent(k.getTradeDate(), d -> new ArrayList<>()).add(tsCode);
                            }
                        }
                    } catch (Exception e) {
                        log.warn("[upPoint] {} 异常: {}", tsCode, e.getMessage());
                    }
                    if ((idx + 1) % 500 == 0 || idx == codes.size() - 1) {
                        log.info("[upPoint] 进度 {}/{}", idx + 1, codes.size());
                    }
                }

                log.info("[upPoint] 检测完毕，共 {} 个交易日有起爆点", dateMap.size());

                for (Map.Entry<LocalDate, List<String>> entry : dateMap.entrySet()) {
                    LocalDate date = entry.getKey();
                    List<String> hitCodes = entry.getValue();
                    if (hitCodes.isEmpty()) continue;

                    String groupName = "起爆点" + date;
                    Long groupId = groupMapper.selectIdByNameAndUserId(SYSTEM_USER_ID, groupName);
                    if (groupId == null) {
                        WatchlistGroup group = new WatchlistGroup();
                        group.setUserId(SYSTEM_USER_ID);
                        group.setName(groupName);
                        group.setSortOrder(0);
                        groupMapper.insert(group);
                        groupId = group.getId();
                    }

                    final Long gid = groupId;
                    List<WatchlistStock> stocks = new ArrayList<>();
                    int sort = 0;
                    for (String tsCode : hitCodes) {
                        StockInfo info = infoMap.get(tsCode);
                        WatchlistStock s = new WatchlistStock();
                        s.setGroupId(gid);
                        s.setTsCode(tsCode);
                        s.setStockName(info != null ? info.getName() : null);
                        s.setSortOrder(sort++);
                        stocks.add(s);
                    }

                    stockMapper.batchInsert(stocks);
                    log.info("[upPoint] {}: {} 只股票", groupName, hitCodes.size());
                }

                log.info("[upPoint] 回测完成，共创建 {} 个自选组", dateMap.size());
            } catch (Exception e) {
                log.error("[upPoint] 回测异常", e);
            } finally {
                upPointRunning.set(false);
            }
        }, "backfill-upPoint");
        t.setDaemon(true);
        t.start();

        return ApiResponse.success(result);
    }

    @PostMapping("/stop/{task}")
    @Operation(summary = "停止指定补全任务：daily / klineMin / realtime / all / upPoint")
    public ApiResponse<Map<String, Object>> stopTask(@PathVariable String task) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task", task);

        String[] keys;
        if ("all".equals(task)) {
            keys = new String[]{"etl_kline_daily", "etl_kline_min_akshare", "etl_realtime"};
        } else if ("daily".equals(task)) {
            keys = new String[]{"etl_kline_daily"};
        } else if ("klineMin".equals(task)) {
            keys = new String[]{"etl_kline_min_akshare"};
        } else if ("realtime".equals(task)) {
            keys = new String[]{"etl_realtime"};
        } else {
            return ApiResponse.error(400, "未知任务: " + task + "，可选: daily / klineMin / realtime / all");
        }

        int killed = 0;
        for (String key : keys) {
            Process p = runningProcesses.get(key);
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
                killed++;
                log.warn("[{}] killed process: {}", LocalDateTime.now().format(TF), key);
            }
        }
        result.put("killed", killed);
        return ApiResponse.success(result);
    }

    private Map<String, Object> runScript(String scriptName, AtomicBoolean flag) {
        return runScriptArgs(new String[]{scriptName}, flag);
    }

    private Map<String, Object> runScriptArgs(String[] scriptAndArgs, AtomicBoolean flag) {
        String scriptName = scriptAndArgs[0];
        String taskKey = scriptName.replace(".py", "");
        String threadName = "backfill-" + taskKey;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("script", scriptName);
        result.put("startTime", LocalDateTime.now().format(TF));

        Thread t = new Thread(() -> {
            String[] cmd = new String[scriptAndArgs.length + 2];
            cmd[0] = PYTHON;
            cmd[1] = "-u";
            cmd[2] = SCRIPT_DIR + scriptName;
            for (int i = 1; i < scriptAndArgs.length; i++) {
                cmd[i + 2] = scriptAndArgs[i];
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            long t0 = System.currentTimeMillis();
            try {
                Process p = pb.start();
                runningProcesses.put(taskKey, p);
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("[{}] {}", scriptName, line);
                    }
                }
                int exitCode = p.waitFor();
                long elapsed = System.currentTimeMillis() - t0;
                log.info("[{}] {} finished in {}ms exitCode={}", LocalDateTime.now().format(TF), scriptName, elapsed, exitCode);
            } catch (InterruptedException e) {
                log.warn("[{}] {} interrupted", LocalDateTime.now().format(TF), scriptName);
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("[{}] {} error: {}", LocalDateTime.now().format(TF), scriptName, e.getMessage());
            } finally {
                runningProcesses.remove(taskKey);
                flag.set(false);
            }
        }, threadName);
        t.setDaemon(true);
        t.start();

        result.put("status", "started");
        return result;
    }
}
