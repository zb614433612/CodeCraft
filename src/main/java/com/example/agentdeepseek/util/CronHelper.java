package com.example.agentdeepseek.util;

import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cron 表达式辅助生成器
 * 支持自然语言时间描述 → Cron 表达式互转
 *
 * 覆盖场景：
 *   每天X点 / 每天早上/下午X点 / 每周N X点 / 每月N号 X点
 *   每小时 / 每N分钟 / 每N小时
 */
@Slf4j
public class CronHelper {

    /** 中文数字 → 阿拉伯数字映射 */
    private static final Map<String, Integer> CN_NUMBERS = new LinkedHashMap<>();

    /** 星期映射 */
    private static final Map<String, String> WEEKDAY_MAP = new LinkedHashMap<>();

    // ======================== 🟡5: 预编译 Pattern 常量 ========================

    private static final Pattern PAT_DAILY_DIGIT =
            Pattern.compile("每天\\s*(早上|上午|中午|下午|晚上)?\\s*(\\d{1,2})\\s*点");
    private static final Pattern PAT_DAILY_CN =
            Pattern.compile("每天\\s*(早上|上午|中午|下午|晚上)?\\s*([零一二两三四五六七八九十]+)\\s*点");
    private static final Pattern PAT_WEEKLY_POINT =
            Pattern.compile("每\\s*(周|星期)([一二三四五六日天])\\s*(早上|上午|中午|下午|晚上)?\\s*(\\d{1,2})\\s*点");
    private static final Pattern PAT_WEEKLY_COLON =
            Pattern.compile("每\\s*(周|星期)([一二三四五六日天])\\s*(早上|上午|中午|下午|晚上)?\\s*(\\d{1,2})[：:]\\s*(\\d{2})");
    private static final Pattern PAT_MONTHLY_DIGIT =
            Pattern.compile("每月\\s*(\\d{1,2})\\s*[号日]\\s*(早上|上午|中午|下午|晚上)?\\s*(\\d{1,2})\\s*点");
    private static final Pattern PAT_MONTHLY_CN =
            Pattern.compile("每月\\s*([零一二两三四五六七八九十]+)\\s*[号日]\\s*(早上|上午|中午|下午|晚上)?\\s*([零一二两三四五六七八九十]+)\\s*点");
    private static final Pattern PAT_EVERY_MINUTES =
            Pattern.compile("每\\s*(\\d{1,3})\\s*分[钟]?");
    private static final Pattern PAT_EVERY_HOURS =
            Pattern.compile("每\\s*(\\d{1,2})\\s*(个)?小?时");

    static {
        CN_NUMBERS.put("零", 0);
        CN_NUMBERS.put("一", 1);
        CN_NUMBERS.put("二", 2);
        CN_NUMBERS.put("两", 2);
        CN_NUMBERS.put("三", 3);
        CN_NUMBERS.put("四", 4);
        CN_NUMBERS.put("五", 5);
        CN_NUMBERS.put("六", 6);
        CN_NUMBERS.put("七", 7);
        CN_NUMBERS.put("八", 8);
        CN_NUMBERS.put("九", 9);
        CN_NUMBERS.put("十", 10);

        WEEKDAY_MAP.put("一", "MON");
        WEEKDAY_MAP.put("二", "TUE");
        WEEKDAY_MAP.put("三", "WED");
        WEEKDAY_MAP.put("四", "THU");
        WEEKDAY_MAP.put("五", "FRI");
        WEEKDAY_MAP.put("六", "SAT");
        WEEKDAY_MAP.put("日", "SUN");
        WEEKDAY_MAP.put("天", "SUN");
        WEEKDAY_MAP.put("周一", "MON");
        WEEKDAY_MAP.put("周二", "TUE");
        WEEKDAY_MAP.put("周三", "WED");
        WEEKDAY_MAP.put("周四", "THU");
        WEEKDAY_MAP.put("周五", "FRI");
        WEEKDAY_MAP.put("周六", "SAT");
        WEEKDAY_MAP.put("周日", "SUN");
        WEEKDAY_MAP.put("星期天", "SUN");
        WEEKDAY_MAP.put("星期一", "MON");
        WEEKDAY_MAP.put("星期二", "TUE");
        WEEKDAY_MAP.put("星期三", "WED");
        WEEKDAY_MAP.put("星期四", "THU");
        WEEKDAY_MAP.put("星期五", "FRI");
        WEEKDAY_MAP.put("星期六", "SAT");
        WEEKDAY_MAP.put("星期日", "SUN");
    }

    /**
     * 将自然语言时间描述转为 Cron 表达式
     *
     * @param desc 自然语言描述，如 "每天早上9点"、"每周一上午10点"、"每30分钟"
     * @return Cron 表达式，无法识别时返回 Optional.empty()
     */
    public static Optional<String> parseNaturalLanguage(String desc) {
        if (desc == null || desc.trim().isEmpty()) {
            return Optional.empty();
        }
        String s = desc.trim();

        // ── 尝试每N分钟（必须在"每小时"之前，避免子串干扰）──
        Optional<String> everyMin = parseEveryMinutes(s);
        if (everyMin.isPresent()) return everyMin;

        // ── 尝试每N小时（必须在"每小时"之前："每3小时".contains("每小时")=true）──
        Optional<String> everyHour = parseEveryHours(s);
        if (everyHour.isPresent()) return everyHour;

        // ── 尝试每小时（纯"每小时"，不含前缀数字）──
        if (s.contains("每小时")) {
            return Optional.of("0 0 * * * ?");
        }

        // ── 尝试每月N号 ──
        Optional<String> monthly = parseMonthly(s);
        if (monthly.isPresent()) return monthly;

        // ── 尝试每周N ──
        Optional<String> weekly = parseWeekly(s);
        if (weekly.isPresent()) return weekly;

        // ── 尝试每天X点（含早上/下午/晚上） ──
        Optional<String> daily = parseDaily(s);
        if (daily.isPresent()) return daily;

        return Optional.empty();
    }

    /**
     * 验证 Cron 表达式是否合法（6位或7位，含范围校验）
     */
    public static boolean validate(String cron) {
        if (cron == null || cron.trim().isEmpty()) return false;
        String[] parts = cron.trim().split("\\s+");
        if (parts.length != 6 && parts.length != 7) return false;

        // 🟡6: 范围校验 — 每个字段尝试 parseInt，成功则做范围校验
        int[][] ranges = {
                {0, 59},  // 秒
                {0, 59},  // 分
                {0, 23},  // 时
                {1, 31},  // 日
                {1, 12},  // 月
                {0, 7}    // 周
        };

        for (int i = 0; i < 6; i++) {
            try {
                int val = Integer.parseInt(parts[i]);
                if (val < ranges[i][0] || val > ranges[i][1]) {
                    return false;
                }
            } catch (NumberFormatException e) {
                // 含特殊字符（* / ? L W # 等），跳过范围校验
            }
        }

        // 第7位（年）可选校验
        if (parts.length == 7) {
            try {
                int year = Integer.parseInt(parts[6]);
                if (year < 1970 || year > 2099) return false;
            } catch (NumberFormatException e) {
                // 含特殊字符，跳过
            }
        }

        return true;
    }

    /**
     * 将 Cron 表达式转为人类可读描述
     */
    public static String describe(String cron) {
        if (!validate(cron)) return "无效的 Cron 表达式";
        String[] p = cron.trim().split("\\s+");
        String minute = p[1];
        String hour = p[2];
        String dayOfMonth = p[3];
        String month = p[4];
        String dayOfWeek = p[5];

        // 🟡7: 每月重复任务检测（修正注释）
        // 🔴3: 条件改为也匹配 dayOfWeek="?"
        if (!"*".equals(dayOfMonth) && !"?".equals(dayOfMonth)
                && ("*".equals(dayOfWeek) || "?".equals(dayOfWeek))
                && "*".equals(month)) {
            return "每月" + dayOfMonth + "号 " + hour + ":" + padMinute(minute) + " 执行";
        }

        // 每周
        if (!"?".equals(dayOfWeek) && !"*".equals(dayOfWeek)) {
            String weekDesc = cronWeekToChinese(dayOfWeek);
            return "每" + weekDesc + " " + hour + ":" + padMinute(minute) + " 执行";
        }

        // 🔴2: 每N分钟 / 每N小时 移到「每天」之前
        // 每N分钟
        if (minute.startsWith("*/")) {
            String n = minute.substring(2);
            return "每" + n + "分钟执行一次";
        }

        // 每N小时
        if (hour.startsWith("*/")) {
            String n = hour.substring(2);
            return "每" + n + "小时执行一次";
        }

        // 每天
        if ("*".equals(dayOfMonth) && ("?".equals(dayOfWeek) || "*".equals(dayOfWeek)) && "*".equals(month)) {
            return "每天 " + hour + ":" + padMinute(minute) + " 执行";
        }

        return cron;
    }

    // ======================== 私有解析方法 ========================

    /** 解析 "每天X点" / "每天早上X点" / "每天下午X点" */
    private static Optional<String> parseDaily(String s) {
        Matcher m = PAT_DAILY_DIGIT.matcher(s);
        if (m.find()) {
            String period = m.group(1);
            int hour = Integer.parseInt(m.group(2));
            hour = adjustHour(hour, period);
            return Optional.of("0 0 " + hour + " * * ?");
        }
        // 也支持中文数字
        m = PAT_DAILY_CN.matcher(s);
        if (m.find()) {
            String period = m.group(1);
            String cnNum = m.group(2);
            int hour = cnNumToInt(cnNum);
            if (hour < 0) return Optional.empty();
            hour = adjustHour(hour, period);
            return Optional.of("0 0 " + hour + " * * ?");
        }
        return Optional.empty();
    }

    /** 解析 "每周N X点" */
    private static Optional<String> parseWeekly(String s) {
        Matcher m = PAT_WEEKLY_POINT.matcher(s);
        if (m.find()) {
            String weekday = m.group(2);
            String cronWeek = WEEKDAY_MAP.get(weekday);
            if (cronWeek == null) cronWeek = WEEKDAY_MAP.get("周" + weekday);
            if (cronWeek == null) return Optional.empty();
            String period = m.group(3);
            int hour = Integer.parseInt(m.group(4));
            hour = adjustHour(hour, period);
            return Optional.of("0 0 " + hour + " ? * " + cronWeek);
        }
        // 也支持 "每周一 10:00"
        m = PAT_WEEKLY_COLON.matcher(s);
        if (m.find()) {
            String weekday = m.group(2);
            String cronWeek = WEEKDAY_MAP.get(weekday);
            if (cronWeek == null) cronWeek = WEEKDAY_MAP.get("周" + weekday);
            if (cronWeek == null) return Optional.empty();
            String period = m.group(3);
            int hour = Integer.parseInt(m.group(4));
            hour = adjustHour(hour, period);
            int minute = Integer.parseInt(m.group(5));
            return Optional.of("0 " + minute + " " + hour + " ? * " + cronWeek);
        }
        return Optional.empty();
    }

    /** 解析 "每月N号 X点"（🟡8: 增加中文数字分支） */
    private static Optional<String> parseMonthly(String s) {
        Matcher m = PAT_MONTHLY_DIGIT.matcher(s);
        if (m.find()) {
            int day = Integer.parseInt(m.group(1));
            String period = m.group(2);
            int hour = Integer.parseInt(m.group(3));
            hour = adjustHour(hour, period);
            if (day < 1 || day > 31) return Optional.empty();
            return Optional.of("0 0 " + hour + " " + day + " * ?");
        }
        // 🟡8: 中文数字分支
        m = PAT_MONTHLY_CN.matcher(s);
        if (m.find()) {
            int day = cnNumToInt(m.group(1));
            String period = m.group(2);
            int hour = cnNumToInt(m.group(3));
            if (day < 1 || day > 31 || hour < 0) return Optional.empty();
            hour = adjustHour(hour, period);
            return Optional.of("0 0 " + hour + " " + day + " * ?");
        }
        return Optional.empty();
    }

    /** 解析 "每N分钟" */
    private static Optional<String> parseEveryMinutes(String s) {
        Matcher m = PAT_EVERY_MINUTES.matcher(s);
        if (!m.find()) return Optional.empty();
        int n = Integer.parseInt(m.group(1));
        if (n < 1 || n > 59) return Optional.empty();
        return Optional.of("0 */" + n + " * * * ?");
    }

    /** 解析 "每N小时" */
    private static Optional<String> parseEveryHours(String s) {
        Matcher m = PAT_EVERY_HOURS.matcher(s);
        if (!m.find()) return Optional.empty();
        int n = Integer.parseInt(m.group(1));
        if (n < 1 || n > 23) return Optional.empty();
        return Optional.of("0 0 */" + n + " * * ?");
    }

    // ======================== 辅助方法 ========================

    /**
     * 🔴1: 中文数字 → int，支持多字符如 "十二"、"二十"、"十三"
     * 长1：直接查 CN_NUMBERS
     * 长2：charAt(0)=='十' → 10 + charAt(1)；charAt(1)=='十' → charAt(0) * 10
     *
     * @return 转换后的整数，失败返回 -1
     */
    private static int cnNumToInt(String s) {
        if (s == null || s.isEmpty()) return -1;
        if (s.length() == 1) {
            Integer v = CN_NUMBERS.get(s);
            return v != null ? v : -1;
        }
        if (s.length() == 2) {
            if (s.charAt(0) == '十') {
                Integer v = CN_NUMBERS.get(String.valueOf(s.charAt(1)));
                return v != null ? 10 + v : -1;
            }
            if (s.charAt(1) == '十') {
                Integer v = CN_NUMBERS.get(String.valueOf(s.charAt(0)));
                return v != null ? v * 10 : -1;
            }
        }
        return -1;
    }

    /** 根据时段调整小时数（🔴4: 晚上12点 → 0） */
    private static int adjustHour(int hour, String period) {
        if (period == null) return hour;
        return switch (period) {
            case "下午" -> hour == 12 ? hour : hour + 12;
            case "晚上" -> hour == 12 ? 0 : hour + 12;
            case "中午" -> hour == 12 ? hour : hour + 12;
            default -> hour;
        };
    }

    private static String padMinute(String minute) {
        if ("0".equals(minute)) return "00";
        if (minute.length() == 1) return "0" + minute;
        return minute;
    }

    private static String cronWeekToChinese(String cronWeek) {
        return switch (cronWeek.toUpperCase()) {
            case "MON" -> "周一";
            case "TUE" -> "周二";
            case "WED" -> "周三";
            case "THU" -> "周四";
            case "FRI" -> "周五";
            case "SAT" -> "周六";
            case "SUN" -> "周日";
            default -> cronWeek;
        };
    }
}
