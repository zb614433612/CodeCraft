package com.example.agentdeepseek.scheduler;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * A股交易时间工具类
 * 交易时段：9:30-11:30（上午），13:00-15:00（下午），周一至周五
 */
public class MarketTimeUtil {

    private static final ZoneId CN_ZONE = ZoneId.of("Asia/Shanghai");

    private static final LocalTime MORNING_START = LocalTime.of(9, 30);
    private static final LocalTime MORNING_END = LocalTime.of(11, 30);
    private static final LocalTime AFTERNOON_START = LocalTime.of(13, 0);
    private static final LocalTime AFTERNOON_END = LocalTime.of(15, 0);

    /** 当前时间是否为交易日交易时段 */
    public static boolean isTradingTime() {
        LocalDate now = LocalDate.now(CN_ZONE);
        if (now.getDayOfWeek() == DayOfWeek.SATURDAY || now.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime t = LocalTime.now(CN_ZONE);
        return (t.equals(MORNING_START) || t.isAfter(MORNING_START)) && t.isBefore(MORNING_END)
                || (t.equals(AFTERNOON_START) || t.isAfter(AFTERNOON_START)) && t.isBefore(AFTERNOON_END);
    }

    /** 当前时间是否处于上午交易时段 */
    public static boolean isMorningSession() {
        LocalTime t = LocalTime.now(CN_ZONE);
        return (t.equals(MORNING_START) || t.isAfter(MORNING_START)) && t.isBefore(MORNING_END);
    }

    /** 当前时间是否处于下午交易时段 */
    public static boolean isAfternoonSession() {
        LocalTime t = LocalTime.now(CN_ZONE);
        return (t.equals(AFTERNOON_START) || t.isAfter(AFTERNOON_START)) && t.isBefore(AFTERNOON_END);
    }

    /** 当前日期是否为交易日（周一至周五） */
    public static boolean isTradingDay() {
        return LocalDate.now(CN_ZONE).getDayOfWeek().getValue() <= 5;
    }
}
