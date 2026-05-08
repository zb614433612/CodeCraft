package com.example.agentdeepseek.scheduler;

import com.example.agentdeepseek.mapper.StockInfoMapper;
import com.example.agentdeepseek.mapper.WatchlistGroupMapper;
import com.example.agentdeepseek.mapper.WatchlistStockMapper;
import com.example.agentdeepseek.model.entity.StockInfo;
import com.example.agentdeepseek.model.entity.StockKlineDaily;
import com.example.agentdeepseek.model.entity.WatchlistGroup;
import com.example.agentdeepseek.model.entity.WatchlistStock;
import com.example.agentdeepseek.service.StockKlineDailyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 股票数据定时任务
 *
 * 盘后流水线（交易日 17:00）:
 *   1. etl_realtime.py    — 获取收盘实时快照
 *   2. etl_kline_daily.py — 从 stock_realtime 表构建今日日K线
 *   3. checkUpPoints()    — 检测起爆点并创建自选组
 */
@Component
public class StockSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(StockSchedulerService.class);
    private static final DateTimeFormatter TF = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final long SYSTEM_USER_ID = 1L;

    private static final String PYTHON = "C:\\Python313\\python.exe";
    private static final String SCRIPT_DIR = "E:\\zbcode\\agent-deepseek\\";

    /** 跟踪正在运行的异步 Python 脚本，key=脚本名，value=进程信息 */
    private static final ConcurrentHashMap<String, RunningScript> RUNNING_SCRIPTS = new ConcurrentHashMap<>();

    private static class RunningScript {
        final Process process;
        final long pid;
        final long startTime;
        final String threadName;
        RunningScript(Process process, long pid, String threadName) {
            this.process = process;
            this.pid = pid;
            this.startTime = System.currentTimeMillis();
            this.threadName = threadName;
        }
    }

    private final StockInfoMapper stockInfoMapper;
    private final StockKlineDailyService klineService;
    private final WatchlistGroupMapper groupMapper;
    private final WatchlistStockMapper stockMapper;

    public StockSchedulerService(StockInfoMapper stockInfoMapper,
                                  StockKlineDailyService klineService,
                                  WatchlistGroupMapper groupMapper,
                                  WatchlistStockMapper stockMapper) {
        this.stockInfoMapper = stockInfoMapper;
        this.klineService = klineService;
        this.groupMapper = groupMapper;
        this.stockMapper = stockMapper;
    }

    // ==================== 盘后流水线（交易日 17:00） ====================

    @Scheduled(cron = "0 0 17 * * MON-FRI")
    public void collectRealtimeAndDaily() {
        log.info("[{}] 盘后流水线开始", LocalDateTime.now().format(TF));
        // 1. 实时行情快照（同步等待）
        runPythonSync("etl_realtime.py");
        // 2. 从实时行情表构建今日日K线（同步等待）
        runPythonSync("etl_kline_daily.py", "--mode", "realtime");
        // 3. 检测起爆点
        checkUpPoints();
    }

    // ----- 分钟K线：盘后 16:00 获取（异步，不阻塞调度线程） -----
    @Scheduled(cron = "0 0 16 * * MON-FRI")
    public void collectKlineMin() {
        log.info("[{}] collectKlineMin start — 异步执行 etl_kline_min.py（腾讯 20 并发，东方财富降级）",
                LocalDateTime.now().format(TF));
        runPython("etl_kline_min.py");
    }

    // ==================== 盘前定时任务（每日，异步执行） ====================

    @Scheduled(cron = "0 0 6 * * ?")
    public void collectStockInfo() {
        log.info("[{}] collectStockInfo start", LocalDateTime.now().format(TF));
        runPython("etl_stock_info.py");
    }

    @Scheduled(cron = "0 0 7 * * ?")
    public void collectAdjFactor() {
        log.info("[{}] collectAdjFactor start", LocalDateTime.now().format(TF));
        runPython("etl_adj_factor.py");
    }

    private void checkUpPoints() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        log.info("[upPoint] 开始检测 {} 起爆点", today);

        List<StockInfo> eligible = stockInfoMapper.findAllEligibleBasic();
        Map<String, StockInfo> infoMap = eligible.stream()
                .collect(Collectors.toMap(StockInfo::getTsCode, si -> si));
        List<String> hitCodes = new ArrayList<>();

        for (StockInfo info : eligible) {
            String tsCode = info.getTsCode();
            try {
                List<StockKlineDaily> klines = klineService.findByTsCode(tsCode, "forward");
                if (klines.isEmpty()) continue;

                StockKlineDaily last = klines.get(klines.size() - 1);
                if (today.equals(last.getTradeDate()) && last.isUpPoint()) {
                    hitCodes.add(tsCode);
                }
            } catch (Exception e) {
                log.warn("[upPoint] {} 检测异常: {}", tsCode, e.getMessage());
            }
        }

        if (hitCodes.isEmpty()) {
            log.info("[upPoint] {} 无满足条件的股票", today);
            return;
        }

        String groupName = "起爆点" + today;
        Long groupId = groupMapper.selectIdByNameAndUserId(SYSTEM_USER_ID, groupName);
        if (groupId == null) {
            WatchlistGroup group = new WatchlistGroup();
            group.setUserId(SYSTEM_USER_ID);
            group.setName(groupName);
            group.setSortOrder(0);
            groupMapper.insert(group);
            groupId = group.getId();
        }

        List<WatchlistStock> stocks = new ArrayList<>();
        int sortOrder = 0;
        for (String tsCode : hitCodes) {
            StockInfo info = infoMap.get(tsCode);
            WatchlistStock s = new WatchlistStock();
            s.setGroupId(groupId);
            s.setTsCode(tsCode);
            s.setStockName(info != null ? info.getName() : null);
            s.setSortOrder(sortOrder++);
            stocks.add(s);
        }
        stockMapper.batchInsert(stocks);

        log.info("[upPoint] 创建自选组 '{}'，共 {} 只股票: {}", groupName, stocks.size(), hitCodes);
    }

    // ==================== Python 脚本运行状态监控 ====================

    /**
     * 每 10 分钟输出一次当前正在运行的 Python 脚本列表
     * 方便运维时查看后台任务执行进度
     */
    @Scheduled(cron = "0 */10 * * * ?")
    public void reportRunningScripts() {
        if (RUNNING_SCRIPTS.isEmpty()) {
            log.info("[sched] 当前无运行中的 Python 脚本");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("当前运行中的 Python 脚本:\n");
        for (Map.Entry<String, RunningScript> entry : RUNNING_SCRIPTS.entrySet()) {
            RunningScript rs = entry.getValue();
            long runningMin = (System.currentTimeMillis() - rs.startTime) / 60000;
            boolean alive = rs.process.isAlive();
            sb.append(String.format("  %s PID=%d 已运行%dmin %s\n",
                    entry.getKey(), rs.pid, runningMin, alive ? "运行中" : "已退出但未清理"));
        }
        log.info("[sched] {}", sb.toString().trim());
    }

    private void runPython(String scriptName, String... args) {
        String threadName = "py-" + scriptName.replace(".py", "");
        new Thread(() -> runPythonSync(scriptName, args), threadName).start();
    }

    private void runPythonSync(String scriptName, String... args) {
        String[] cmd = new String[args.length + 3];
        cmd[0] = PYTHON;
        cmd[1] = "-u";
        cmd[2] = SCRIPT_DIR + scriptName;
        System.arraycopy(args, 0, cmd, 3, args.length);

        long t0 = System.currentTimeMillis();
        String joinedCmd = String.join(" ", cmd);
        log.info("[sched] 启动脚本: {}", joinedCmd);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            long pid = p.pid();
            String threadName = Thread.currentThread().getName();

            // 注册到运行跟踪表
            RunningScript rs = new RunningScript(p, pid, threadName);
            RUNNING_SCRIPTS.put(scriptName, rs);
            log.info("[sched] {} PID={} 已启动", scriptName, pid);

            // 读取脚本输出
            long lastLog = System.currentTimeMillis();
            int lineCount = 0;
            String line;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                while ((line = reader.readLine()) != null) {
                    log.info("  [{}] {}", scriptName, line);
                    lineCount++;
                    // 每 5 分钟输出一次运行时长心跳
                    long now = System.currentTimeMillis();
                    if (now - lastLog > 300_000) {
                        log.info("[sched] {} PID={} 仍在运行，已耗时 {}min，输出 {} 行",
                                scriptName, pid, (now - t0) / 60000, lineCount);
                        lastLog = now;
                    }
                }
            }

            int exitCode = p.waitFor();
            long elapsed = System.currentTimeMillis() - t0;
            log.info("[sched] {} PID={} 已退出（exitCode={}），耗时 {}s，输出 {} 行",
                    scriptName, pid, exitCode, elapsed / 1000, lineCount);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - t0;
            log.error("[sched] {} 执行失败（耗时 {}s）: {}", scriptName, elapsed / 1000, e.getMessage(), e);
        } finally {
            RUNNING_SCRIPTS.remove(scriptName);
        }
    }
}
