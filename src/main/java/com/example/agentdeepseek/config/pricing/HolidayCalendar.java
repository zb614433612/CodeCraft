package com.example.agentdeepseek.config.pricing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 中国法定节假日日历（峰谷计价用）。
 *
 * <h3>铁律：计费主链路绝不联网</h3>
 * <p>
 * 本类在<b>启动预热</b>与<b>每日凌晨 03:00</b>做一次后台刷新，判定方法
 * （{@link #isHoliday} / {@link #isMakeupWorkday}）只读内存，绝不发起网络请求 ——
 * 否则一次 DNS 抖动就会让「计价」这种事变成不确定行为。
 * </p>
 *
 * <h3>数据源与优先级</h3>
 * <ol>
 *   <li>内置表 {@code classpath:holidays/cn-holidays.json}（离线保底，构造期同步加载）</li>
 *   <li>本地缓存 {@code <cache-dir>/cn-<year>.json}（上次远端拉取结果，构造期加载）</li>
 *   <li>远端（仅 {@code AUTO} / {@code REMOTE} 模式，异步补次年等内置表未覆盖年份）</li>
 *   <li>{@code extra-holidays} / {@code extra-workdays}（手工覆盖，优先级最高）</li>
 * </ol>
 *
 * <h3>防脏数据</h3>
 * <p>
 * 远端/缓存数据必须通过校验：日期格式非法、年份与请求年份不符、条数为 0 →
 * <b>整份丢弃</b>，退回内置表。宁可少判一个节假日，也不能用错数据把高峰算成空闲。
 * </p>
 */
@Slf4j
@Component
public class HolidayCalendar {

    /** 内置表 classpath 位置 */
    private static final String BUILTIN_RESOURCE = "holidays/cn-holidays.json";
    /** 本地缓存文件名（按年份区分） */
    private static final String CACHE_FILE = "cn-%d.json";

    private final PeakPeriodProperties props;
    private final ObjectMapper objectMapper;

    /** 年份 → 该年节假日 / 调休上班日 */
    private final Map<Integer, YearDays> years = new ConcurrentHashMap<>();

    /** 最近一次刷新时间（便于前端/排查查看数据新鲜度） */
    private volatile LocalDateTime lastRefreshAt;
    /** 最近一次刷新结果说明 */
    private volatile String lastRefreshSource = "BUILTIN_CN";

    public HolidayCalendar(PeakPeriodProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        // 构造期同步加载「离线可用」的两层，保证任何时刻判定都有数据
        loadBuiltin();
        loadCacheFiles();
        applyExtraOverrides();
    }

    // ==================== 对外判定入口 ====================

    /**
     * 给定日期是否为法定节假日（该地区日历中「全体公民放假」的日子）。
     *
     * @param region 地区标识（{@code CN}；其它地区一律返回 false，按「无节假日」处理）
     * @param date   日期（<b>已按平台时区换算后的本地日期</b>）
     */
    public boolean isHoliday(String region, LocalDate date) {
        if (date == null || !isCnRegion(region)) {
            return false;
        }
        YearDays yd = years.get(date.getYear());
        return yd != null && yd.holidays.contains(date);
    }

    /**
     * 给定日期是否为「调休上班日」（官方安排上班的周末）。
     * <p>仅当平台档案 {@code honorMakeupWorkdays=true} 时才参与工作日判定。</p>
     */
    public boolean isMakeupWorkday(LocalDate date) {
        if (date == null) {
            return false;
        }
        YearDays yd = years.get(date.getYear());
        return yd != null && yd.makeupWorkdays.contains(date);
    }

    /** 已加载的年份（排查用） */
    public Set<Integer> loadedYears() {
        return new LinkedHashSet<>(years.keySet());
    }

    /** 某年是否已有数据（无数据时判定会退化为「只看星期」） */
    public boolean hasYear(int year) {
        YearDays yd = years.get(year);
        return yd != null && (!yd.holidays.isEmpty() || !yd.makeupWorkdays.isEmpty());
    }

    public LocalDateTime lastRefreshAt() {
        return lastRefreshAt;
    }

    public String lastRefreshSource() {
        return lastRefreshSource;
    }

    // ==================== 刷新（异步、失败静默） ====================

    /**
     * 启动预热：内置表已在构造期加载，这里只补「次年安排」等远端数据。
     * <p>异步执行，任何异常都不影响启动。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        CompletableFuture.runAsync(this::refreshFromRemote);
    }

    /** 每日 03:00 静默刷新一次（跨年、政策临时调整都能在一天内生效） */
    @Scheduled(cron = "0 0 3 * * ?")
    public void refreshDaily() {
        CompletableFuture.runAsync(this::refreshFromRemote);
    }

    private void refreshFromRemote() {
        PeakPeriodProperties.Holiday cfg = props.getHoliday();
        String mode = normalizeMode(cfg.getSource());
        try {
            if ("NONE".equals(mode) || "CONFIG_ONLY".equals(mode) || "BUILTIN_CN".equals(mode)) {
                lastRefreshAt = LocalDateTime.now();
                lastRefreshSource = mode;
                return;
            }
            int baseYear = LocalDate.now(zone()).getYear();
            int ahead = Math.max(cfg.getYearsAhead(), 0);
            boolean anySuccess = false;
            for (int y = baseYear; y <= baseYear + ahead; y++) {
                anySuccess |= fetchYear(y, cfg);
            }
            lastRefreshSource = anySuccess ? "REMOTE" : "BUILTIN_CN";
        } catch (Exception e) {
            // 刷新失败绝不影响计费：继续用手上的数据
            log.debug("节假日远端刷新失败（继续使用内置/缓存数据）: {}", e.getMessage());
            lastRefreshSource = "BUILTIN_CN";
        } finally {
            lastRefreshAt = LocalDateTime.now();
        }
    }

    private boolean fetchYear(int year, PeakPeriodProperties.Holiday cfg) {
        String templateUrl = cfg.getRemoteUrl();
        if (templateUrl == null || templateUrl.isBlank()) {
            return false;
        }
        String url = templateUrl.replace("{year}", String.valueOf(year));
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(Math.max(cfg.getRemoteTimeoutMs(), 500)))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(cfg.getRemoteTimeoutMs(), 500)))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
                log.debug("节假日远端返回异常: year={}, status={}", year, response.statusCode());
                return false;
            }
            JsonNode root = objectMapper.readTree(response.body());
            int parsed = parseInto(year, root, "REMOTE");
            if (parsed <= 0) {
                // 整份无效：绝不半信半疑地合并
                log.debug("节假日远端数据校验未通过（丢弃）: year={}, url={}", year, url);
                return false;
            }
            writeCacheFile(year, root);
            return true;
        } catch (Exception e) {
            log.debug("节假日远端拉取失败: year={}, err={}", year, e.getMessage());
            return false;
        }
    }

    // ==================== 加载 / 解析 ====================

    private void loadBuiltin() {
        try {
            JsonNode root = objectMapper.readTree(
                    new java.io.InputStreamReader(
                            java.util.Objects.requireNonNull(
                                    getClass().getClassLoader().getResourceAsStream(BUILTIN_RESOURCE),
                                    "内置节假日表缺失: " + BUILTIN_RESOURCE),
                            java.nio.charset.StandardCharsets.UTF_8));
            int count = parseInto(-1, root, "BUILTIN");
            log.info("节假日内置表加载完成: 年份={}, 解析条数={}", loadedYears(), count);
        } catch (Exception e) {
            // 内置表都读不到时，判定退化为「只看星期」，仍需保证不抛错
            log.warn("节假日内置表加载失败（将退化为仅按星期判定）: {}", e.getMessage());
        }
    }

    private void loadCacheFiles() {
        PayloadCache cache = resolveCacheDir();
        if (cache == null) {
            return;
        }
        try {
            int baseYear = LocalDate.now(zone()).getYear();
            for (int y = baseYear; y <= baseYear + Math.max(props.getHoliday().getYearsAhead(), 0); y++) {
                Path file = cache.dir.resolve(String.format(CACHE_FILE, y));
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                try {
                    JsonNode root = objectMapper.readTree(Files.readString(file));
                    parseInto(y, root, "CACHE");
                } catch (Exception e) {
                    log.debug("节假日缓存文件解析失败（忽略）: file={}, err={}", file, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("节假日缓存目录扫描失败（忽略）: {}", e.getMessage());
        }
    }

    private void applyExtraOverrides() {
        PeakPeriodProperties.Holiday cfg = props.getHoliday();
        for (String s : safeList(cfg.getExtraHolidays())) {
            LocalDate d = safeParseDate(s);
            if (d == null) {
                log.warn("extra-holidays 日期非法，已忽略: {}", s);
                continue;
            }
            years.computeIfAbsent(d.getYear(), k -> new YearDays()).holidays.add(d);
            // 手工把它标成节假日时，同时移出调休上班日集合，避免两条规则互相打架
            YearDays yd = years.get(d.getYear());
            if (yd != null) {
                yd.makeupWorkdays.remove(d);
            }
        }
        for (String s : safeList(cfg.getExtraWorkdays())) {
            LocalDate d = safeParseDate(s);
            if (d == null) {
                log.warn("extra-workdays 日期非法，已忽略: {}", s);
                continue;
            }
            years.computeIfAbsent(d.getYear(), k -> new YearDays()).makeupWorkdays.add(d);
            YearDays yd = years.get(d.getYear());
            if (yd != null) {
                yd.holidays.remove(d);
            }
        }
    }

    /**
     * 解析一份节假日 JSON 并合并进内存。
     *
     * <p>支持两种格式：</p>
     * <ol>
     *   <li><b>holiday-cn 格式</b>：{@code {"year":2026,"days":[{"date":"2026-01-01","isOffDay":true}]}}</li>
     *   <li><b>本项目统一格式</b>：{@code {"region":"CN","years":{"2026":{"holidays":[...],"makeupWorkdays":[...]}}}}
     *       ，或单年对象 {@code {"holidays":[...],"makeupWorkdays":[...]}}</li>
     * </ol>
     *
     * @param expectedYear 期望年份（-1 表示由内容自定；>0 时与内容年份不一致则丢弃）
     * @return 成功合并的有效日期条数；0 表示整份无效
     */
    private int parseInto(int expectedYear, JsonNode root, String origin) {
        if (root == null || root.isNull() || !root.isObject()) {
            return 0;
        }
        int merged = 0;

        // ---------- 格式 1：holiday-cn {"days":[{date,isOffDay}]} ----------
        JsonNode days = root.path("days");
        if (days.isArray()) {
            Set<LocalDate> holidays = new HashSet<>();
            Set<LocalDate> makeup = new HashSet<>();
            for (JsonNode day : days) {
                LocalDate date = safeParseDate(day.path("date").asText(null));
                if (date == null) {
                    continue;
                }
                if (expectedYear > 0 && date.getYear() != expectedYear) {
                    continue;
                }
                if (day.path("isOffDay").asBoolean(true)) {
                    holidays.add(date);
                } else {
                    makeup.add(date);
                }
            }
            if (holidays.isEmpty() && makeup.isEmpty()) {
                return 0;
            }
            mergeYear(resolveYearOf(root, expectedYear, holidays, makeup), holidays, makeup);
            return holidays.size() + makeup.size();
        }

        // ---------- 格式 2：统一格式（years 映射 / 单年对象） ----------
        JsonNode yearsNode = root.path("years");
        if (yearsNode.isObject()) {
            java.util.Iterator<Map.Entry<String, JsonNode>> it = yearsNode.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                Integer year = safeParseInt(entry.getKey());
                if (year == null || (expectedYear > 0 && year != expectedYear)) {
                    continue;
                }
                merged += parseSingleYear(year, entry.getValue());
            }
            return merged;
        }
        if (root.has("holidays") || root.has("makeupWorkdays")) {
            Integer year = resolveYearOf(root, expectedYear, Collections.emptySet(), Collections.emptySet());
            if (year == null) {
                return 0;
            }
            return parseSingleYear(year, root);
        }
        return 0;
    }

    private int parseSingleYear(int year, JsonNode node) {
        if (node == null || !node.isObject()) {
            return 0;
        }
        Set<LocalDate> holidays = readDateArray(node.path("holidays"));
        Set<LocalDate> makeup = readDateArray(node.path("makeupWorkdays"));
        // 年份校验：有日期但全部落在别的年份 → 说明数据错位，整份丢弃
        if (!holidays.isEmpty() && holidays.stream().allMatch(d -> d.getYear() != year)) {
            return 0;
        }
        if (!makeup.isEmpty() && makeup.stream().allMatch(d -> d.getYear() != year)) {
            return 0;
        }
        if (holidays.isEmpty() && makeup.isEmpty()) {
            return 0;
        }
        mergeYear(year, holidays, makeup);
        return holidays.size() + makeup.size();
    }

    private Set<LocalDate> readDateArray(JsonNode arr) {
        Set<LocalDate> set = new HashSet<>();
        if (arr == null || !arr.isArray()) {
            return set;
        }
        for (JsonNode item : arr) {
            LocalDate date = safeParseDate(item.asText(null));
            if (date != null) {
                set.add(date);
            }
        }
        return set;
    }

    private void mergeYear(int year, Set<LocalDate> holidays, Set<LocalDate> makeup) {
        YearDays yd = years.computeIfAbsent(year, k -> new YearDays());
        yd.holidays.addAll(holidays);
        yd.makeupWorkdays.addAll(makeup);
    }

    private Integer resolveYearOf(JsonNode root, int expectedYear,
                                  Set<LocalDate> holidays, Set<LocalDate> makeup) {
        if (expectedYear > 0) {
            return expectedYear;
        }
        Integer declared = safeParseInt(root.path("year").asText(null));
        if (declared != null) {
            return declared;
        }
        if (!holidays.isEmpty()) {
            return holidays.iterator().next().getYear();
        }
        if (!makeup.isEmpty()) {
            return makeup.iterator().next().getYear();
        }
        return null;
    }

    // ==================== 缓存文件 ====================

    private void writeCacheFile(int year, JsonNode root) {
        PayloadCache cache = resolveCacheDir();
        if (cache == null) {
            return;
        }
        try {
            Files.createDirectories(cache.dir);
            Files.writeString(cache.dir.resolve(String.format(CACHE_FILE, year)),
                    objectMapper.writeValueAsString(root));
        } catch (Exception e) {
            log.debug("节假日缓存写入失败（忽略）: year={}, err={}", year, e.getMessage());
        }
    }

    /** 缓存目录上下文（路径解析失败时返回 null，整体跳过缓存能力） */
    private PayloadCache resolveCacheDir() {
        try {
            String dir = props.getHoliday().getCacheDir();
            if (dir == null || dir.isBlank()) {
                return null;
            }
            return new PayloadCache(Paths.get(dir));
        } catch (Exception e) {
            log.debug("节假日缓存目录不可用（忽略）: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 小工具 ====================

    private ZoneId zone() {
        try {
            String z = props.getZone();
            return (z == null || z.isBlank()) ? ZoneId.of("Asia/Shanghai") : ZoneId.of(z);
        } catch (Exception e) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    private static boolean isCnRegion(String region) {
        return region != null && "CN".equalsIgnoreCase(region.trim());
    }

    private static String normalizeMode(String mode) {
        return mode == null ? "AUTO" : mode.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static LocalDate safeParseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer safeParseInt(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> safeList(List<String> list) {
        return list == null ? Collections.emptyList() : new ArrayList<>(list);
    }

    /** 单年数据 */
    private static final class YearDays {
        /*
          必须用并发 Set：每日 03:00 的异步刷新（mergeYear → addAll）与计费判定（isHoliday → contains）
          可能同时发生。普通 LinkedHashSet 虽不易抛错，但存在「刷新到一半」的可见性问题，
          可能让某次计价读到不完整的节假日集合。
         */
        private final Set<LocalDate> holidays = ConcurrentHashMap.newKeySet();
        private final Set<LocalDate> makeupWorkdays = ConcurrentHashMap.newKeySet();
    }

    /** 缓存目录上下文 */
    private static final class PayloadCache {
        private final Path dir;

        private PayloadCache(Path dir) {
            this.dir = dir;
        }
    }
}
