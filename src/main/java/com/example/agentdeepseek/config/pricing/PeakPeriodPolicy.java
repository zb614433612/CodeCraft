package com.example.agentdeepseek.config.pricing;

import com.example.agentdeepseek.service.llm.profile.ProviderProfile.PeakPricingSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 峰谷时段判定策略（纯计算，无 IO、无网络）。
 *
 * <h3>判定口径（DeepSeek 官方价格页 2026-11-18）</h3>
 * <ol>
 *   <li>落在法定节假日 → <b>空闲</b>（含全天）</li>
 *   <li>非工作日（默认周一至周五之外）→ <b>空闲</b></li>
 *   <li>工作日内落在高峰窗口（默认 09:00–12:00、14:00–18:00，<b>左闭右开</b>）→ <b>高峰</b></li>
 *   <li>其余 → <b>空闲</b></li>
 * </ol>
 *
 * <h3>两个容易踩的坑，已在实现里堵住</h3>
 * <ol>
 *   <li><b>时区</b>：必须按平台档案声明的时区（默认 {@code Asia/Shanghai}）换算，
 *       绝不能用 JVM 默认时区 —— 服务器跑在 UTC 时北京时间 10:00 会被判成凌晨 2:00，
 *       于是全天按空闲价，金额直接少一半。</li>
 *   <li><b>窗口解析失败</b>：配置写错（如 {@code 9:00~12:00}）时不能「一条都不匹配 → 全天空闲」，
 *       那等于静默打折；实现里整组不可解析时回退默认窗口，宁可保守也不能算少。</li>
 * </ol>
 */
@Slf4j
@Component
public class PeakPeriodPolicy {

    /** 时段标识：高峰 */
    public static final String PERIOD_PEAK = "peak";
    /** 时段标识：空闲 */
    public static final String PERIOD_OFF_PEAK = "off-peak";

    /** 窗口解析全部失败时的兜底（与官网一致） */
    private static final List<String> DEFAULT_WINDOWS = List.of("09:00-12:00", "14:00-18:00");
    /** 工作日兜底（与官网一致） */
    private static final List<String> DEFAULT_WORKDAYS = List.of("MON", "TUE", "WED", "THU", "FRI");
    /** 时区兜底 */
    private static final ZoneId FALLBACK_ZONE = ZoneId.of("Asia/Shanghai");

    /** 星期简写 → {@link DayOfWeek}（大小写不敏感，兼容 MON / Monday / 周一 的英文写法） */
    private static final Map<String, DayOfWeek> DAY_ALIASES = buildDayAliases();

    private final PeakPeriodProperties props;
    private final HolidayCalendar holidayCalendar;

    public PeakPeriodPolicy(PeakPeriodProperties props, HolidayCalendar holidayCalendar) {
        this.props = props;
        this.holidayCalendar = holidayCalendar;
    }

    // ==================== 核心判定 ====================

    /**
     * 给定时刻是否为高峰时段。
     *
     * @param spec 平台峰谷规格（可为 null / 未启用 → 返回 false，即按基准价）
     * @param at   判定时刻（任意时区，内部会换算到平台时区）
     */
    public boolean isPeak(PeakPricingSpec spec, ZonedDateTime at) {
        if (!props.isEnabled() || spec == null || !spec.isActive() || at == null) {
            return false;
        }
        try {
            ZonedDateTime local = at.withZoneSameInstant(zoneOf(spec));
            LocalDate date = local.toLocalDate();
            LocalTime time = local.toLocalTime();

            boolean makeupWorkday = spec.isHonorMakeupWorkdays()
                    && holidayCalendar.isMakeupWorkday(date);
            boolean weekday = makeupWorkday
                    || workdaysOf(spec).contains(date.getDayOfWeek());
            if (!weekday) {
                return false;
            }
            // 节假日优先于一切（官网：周一至周五「不含中国法定节假日」）
            if (holidayCalendar.isHoliday(holidayRegionOf(spec), date)) {
                return false;
            }
            return inAnyWindow(spec, time);
        } catch (Exception e) {
            // 判定异常时按空闲（基准价）处理：宁可少收，也不能给出一个随机的高峰结论
            log.debug("峰谷判定异常，按空闲处理: {}", e.getMessage());
            return false;
        }
    }

    /** 当前时刻是否高峰（便于调用方直接使用） */
    public boolean isPeakNow(PeakPricingSpec spec) {
        return isPeak(spec, nowOf(spec));
    }

    /**
     * 时段标识：{@link #PERIOD_PEAK} / {@link #PERIOD_OFF_PEAK}。
     * <p>平台未启用峰谷时统一返回 {@code null}，表示「这个概念不适用于该平台」，
     * 落库时同样写 null，避免历史数据里出现无意义的 off-peak 噪音。</p>
     */
    public String periodOf(PeakPricingSpec spec, ZonedDateTime at) {
        if (!props.isEnabled() || spec == null || !spec.isActive()) {
            return null;
        }
        return isPeak(spec, at) ? PERIOD_PEAK : PERIOD_OFF_PEAK;
    }

    /** 按平台时区的「现在」 */
    public ZonedDateTime nowOf(PeakPricingSpec spec) {
        return ZonedDateTime.now(zoneOf(spec));
    }

    /** 平台判定时区（档案未声明时用全局默认，再兜底 Asia/Shanghai） */
    public ZoneId zoneOf(PeakPricingSpec spec) {
        String zone = (spec != null && spec.getZone() != null && !spec.getZone().isBlank())
                ? spec.getZone()
                : props.getZone();
        try {
            return (zone == null || zone.isBlank()) ? FALLBACK_ZONE : ZoneId.of(zone.trim());
        } catch (Exception e) {
            log.warn("峰谷时区配置非法，回退 {}: {}", FALLBACK_ZONE, zone);
            return FALLBACK_ZONE;
        }
    }

    /** 生效的高峰窗口列表（已校验，可直接展示给前端） */
    public List<String> windowsOf(PeakPricingSpec spec) {
        List<Window> parsed = parseWindows(spec);
        List<String> result = new ArrayList<>(parsed.size());
        for (Window w : parsed) {
            result.add(w.from + "-" + w.to);
        }
        return result;
    }

    /** 节假日地区（未声明时按 CN） */
    public String holidayRegionOf(PeakPricingSpec spec) {
        if (spec == null || spec.getHolidayRegion() == null || spec.getHolidayRegion().isBlank()) {
            return PeakPricingSpec.REGION_CN;
        }
        return spec.getHolidayRegion().trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 下一次「峰↔谷」切换时刻（供前端显示「18:00 后转空闲」）。
     *
     * <p>实现按分钟步进（最多向前找 8 天）—— 调用频率低（只在成本看板/聊天页拉一次），
     * 换来的是「跨周末、跨节假日、跨调休」都能自动算对，不需要手写一堆边界推导。</p>
     *
     * @return 切换时刻；平台未启用峰谷或 8 天内无切换时返回 {@code null}
     */
    public ZonedDateTime nextSwitchAt(PeakPricingSpec spec, ZonedDateTime from) {
        if (!props.isEnabled() || spec == null || !spec.isActive() || from == null) {
            return null;
        }
        ZonedDateTime start = from.withZoneSameInstant(zoneOf(spec));
        boolean current = isPeak(spec, start);
        ZonedDateTime cursor = start.withSecond(0).withNano(0).plusMinutes(1);
        for (int i = 0; i < 8 * 24 * 60; i++) {
            if (isPeak(spec, cursor) != current) {
                return cursor;
            }
            cursor = cursor.plusMinutes(1);
        }
        return null;
    }

    /** 全局开关是否开启 */
    public boolean isGloballyEnabled() {
        return props.isEnabled();
    }

    /** 兜底倍率（高峰价 = 基准价 × 本值） */
    public double peakMultiplier() {
        double m = props.getPeakMultiplier();
        return m > 0 ? m : 2.0;
    }

    // ==================== 内部解析 ====================

    private boolean inAnyWindow(PeakPricingSpec spec, LocalTime time) {
        for (Window w : parseWindows(spec)) {
            // 左闭右开：[from, to)
            if (!time.isBefore(w.from) && time.isBefore(w.to)) {
                return true;
            }
        }
        return false;
    }

    private List<DayOfWeek> workdaysOf(PeakPricingSpec spec) {
        // 平台档案优先；档案留空则继承全局配置（application.yml），最后才回退内置默认
        List<String> raw = (spec != null && spec.getWorkdays() != null && !spec.getWorkdays().isEmpty())
                ? spec.getWorkdays()
                : globalWorkdays();
        List<DayOfWeek> result = new ArrayList<>(raw.size());
        for (String s : raw) {
            if (s == null || s.isBlank()) {
                continue;
            }
            DayOfWeek day = DAY_ALIASES.get(s.trim().toUpperCase(Locale.ROOT));
            if (day == null) {
                log.warn("峰谷 workdays 含非法星期，已忽略: {}", s);
                continue;
            }
            result.add(day);
        }
        return result.isEmpty() ? List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY) : result;
    }

    /** 全局默认窗口：配置优先，配置缺失时用内置默认 */
    private List<String> globalWindows() {
        List<String> configured = props.getWindows();
        return (configured == null || configured.isEmpty()) ? DEFAULT_WINDOWS : configured;
    }

    /** 全局默认工作日：配置优先，配置缺失时用内置默认 */
    private List<String> globalWorkdays() {
        List<String> configured = props.getWorkdays();
        return (configured == null || configured.isEmpty()) ? DEFAULT_WORKDAYS : configured;
    }

    /**
     * 解析高峰窗口。
     * <p>支持 {@code HH:mm-HH:mm}；整组都解析不出来时<b>回退默认窗口</b>，
     * 避免「配置写错 → 全天空闲 → 静默少算」。</p>
     */
    private List<Window> parseWindows(PeakPricingSpec spec) {
        // 平台档案优先；档案留空则继承全局配置（application.yml），最后才回退内置默认
        List<String> raw = (spec != null && spec.getWindows() != null && !spec.getWindows().isEmpty())
                ? spec.getWindows()
                : globalWindows();
        List<Window> result = new ArrayList<>(raw.size());
        for (String item : raw) {
            if (item == null || item.isBlank()) {
                continue;
            }
            Window window = parseWindow(item);
            if (window != null) {
                result.add(window);
            } else {
                log.warn("峰谷窗口格式非法，已忽略: {}（应为 HH:mm-HH:mm）", item);
            }
        }
        if (result.isEmpty()) {
            List<Window> fallback = new ArrayList<>(DEFAULT_WINDOWS.size());
            for (String item : DEFAULT_WINDOWS) {
                Window w = parseWindow(item);
                if (w != null) {
                    fallback.add(w);
                }
            }
            log.warn("峰谷窗口全部不可解析，已回退默认窗口: {}", DEFAULT_WINDOWS);
            return fallback;
        }
        return result;
    }

    private Window parseWindow(String text) {
        try {
            String[] parts = text.trim().split("-");
            if (parts.length != 2) {
                return null;
            }
            LocalTime from = LocalTime.parse(parts[0].trim());
            LocalTime to = LocalTime.parse(parts[1].trim());
            if (from.equals(to)) {
                return null;
            }
            return new Window(from, to);
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, DayOfWeek> buildDayAliases() {
        Map<String, DayOfWeek> map = new LinkedHashMap<>();
        map.put("MON", DayOfWeek.MONDAY);
        map.put("MONDAY", DayOfWeek.MONDAY);
        map.put("TUE", DayOfWeek.TUESDAY);
        map.put("TUESDAY", DayOfWeek.TUESDAY);
        map.put("WED", DayOfWeek.WEDNESDAY);
        map.put("WEDNESDAY", DayOfWeek.WEDNESDAY);
        map.put("THU", DayOfWeek.THURSDAY);
        map.put("THURSDAY", DayOfWeek.THURSDAY);
        map.put("FRI", DayOfWeek.FRIDAY);
        map.put("FRIDAY", DayOfWeek.FRIDAY);
        map.put("SAT", DayOfWeek.SATURDAY);
        map.put("SATURDAY", DayOfWeek.SATURDAY);
        map.put("SUN", DayOfWeek.SUNDAY);
        map.put("SUNDAY", DayOfWeek.SUNDAY);
        return Collections.unmodifiableMap(map);
    }

    /** 解析后的窗口（左闭右开） */
    private static final class Window {
        private final LocalTime from;
        private final LocalTime to;

        private Window(LocalTime from, LocalTime to) {
            this.from = from;
            this.to = to;
        }
    }
}
