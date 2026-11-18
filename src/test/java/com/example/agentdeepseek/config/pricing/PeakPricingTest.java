package com.example.agentdeepseek.config.pricing;

import com.example.agentdeepseek.config.TokenPricingConfig;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile.PeakPricingSpec;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * 峰谷计价回归测试（手写 main 风格，与项目其它 Test 保持一致）。
 *
 * <h3>覆盖范围</h3>
 * <ol>
 *   <li><b>时段边界</b>：09:00 / 11:59 / 12:00 / 13:59 / 14:00 / 17:59 / 18:00 七个点（左闭右开）</li>
 *   <li><b>周末 / 法定节假日 / 调休上班日</b>（两种口径）</li>
 *   <li><b>时区</b>：用 UTC 时刻验证「按北京时间判定」，防止服务器跑 UTC 时全天误判空闲</li>
 *   <li><b>总开关</b>：关闭时一律空闲且时段标识为 null</li>
 *   <li><b>双价</b>：未启用峰谷返回同一对象（行为零变化）、显式 peak 价、倍率兜底、cacheWrite 不双重放大</li>
 *   <li><b>下次切换</b>：09:30 → 12:00</li>
 * </ol>
 *
 * <p>运行方式（与项目惯例一致，不依赖 JUnit）：在 IDE 里直接跑 {@code main}，
 * 或用 {@code mvn test-compile} 后以 {@code java -cp} 挂上 target/classes + 依赖 jar 运行。</p>
 */
public class PeakPricingTest {

    private static int passed = 0;
    private static int failed = 0;
    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    public static void main(String[] args) {
        System.out.println("=== 峰谷计价回归测试 ===\n");

        PeakPeriodProperties props = new PeakPeriodProperties();
        HolidayCalendar calendar = new HolidayCalendar(props, new ObjectMapper());
        PeakPeriodPolicy policy = new PeakPeriodPolicy(props, calendar);
        PeakPricingSpec spec = spec();

        windows(policy, spec);
        weekdaysAndHolidays(policy, spec);
        timezone(policy, spec);
        masterSwitch(props, calendar, spec);
        priceItem();
        nextSwitch(policy, spec);
        globalFallback();

        System.out.println("\n通过 " + passed + " / 失败 " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== 1. 时段边界 ====================

    private static void windows(PeakPeriodPolicy policy, PeakPricingSpec spec) {
        System.out.println("[1] 时段边界（2026-11-18 周三）");
        check("09:00 整为高峰（左闭）", policy.isPeak(spec, at(2026, 11, 18, 9, 0)));
        check("11:59 仍为高峰", policy.isPeak(spec, at(2026, 11, 18, 11, 59)));
        check("12:00 转空闲（右开）", !policy.isPeak(spec, at(2026, 11, 18, 12, 0)));
        check("13:59 仍空闲", !policy.isPeak(spec, at(2026, 11, 18, 13, 59)));
        check("14:00 起为高峰", policy.isPeak(spec, at(2026, 11, 18, 14, 0)));
        check("17:59 仍为高峰", policy.isPeak(spec, at(2026, 11, 18, 17, 59)));
        check("18:00 转空闲", !policy.isPeak(spec, at(2026, 11, 18, 18, 0)));
        check("凌晨 03:00 为空闲", !policy.isPeak(spec, at(2026, 11, 18, 3, 0)));
    }

    // ==================== 2. 周末 / 节假日 / 调休 ====================

    private static void weekdaysAndHolidays(PeakPeriodPolicy policy, PeakPricingSpec spec) {
        System.out.println("[2] 周末 / 法定节假日 / 调休");
        check("周六全天空闲（2026-11-21）", !policy.isPeak(spec, at(2026, 11, 21, 10, 0)));
        check("周日全天空闲（2026-11-22）", !policy.isPeak(spec, at(2026, 11, 22, 10, 0)));
        // 内置表 2026 年数据来自国办发明电〔2025〕7号
        check("国庆（2026-10-01 周四）全天空闲", !policy.isPeak(spec, at(2026, 10, 1, 10, 0)));
        check("春节（2026-02-16 周一）全天空闲", !policy.isPeak(spec, at(2026, 2, 16, 10, 0)));
        check("清明（2026-04-06 周一）全天空闲", !policy.isPeak(spec, at(2026, 4, 6, 10, 0)));

        // 调休上班日：默认严格按官网字面（周末一律空闲）
        check("调休上班日（2026-10-10 周六）默认算空闲",
                !policy.isPeak(spec, at(2026, 10, 10, 10, 0)));
        PeakPricingSpec honor = spec();
        honor.setHonorMakeupWorkdays(true);
        check("honorMakeupWorkdays=true 时调休上班日算高峰",
                policy.isPeak(honor, at(2026, 10, 10, 10, 0)));
    }

    // ==================== 3. 时区 ====================

    private static void timezone(PeakPeriodPolicy policy, PeakPricingSpec spec) {
        System.out.println("[3] 时区（必须按北京时间判定）");
        // 北京 09:30 = UTC 01:30
        check("UTC 01:30 → 北京 09:30 高峰",
                policy.isPeak(spec, ZonedDateTime.of(2026, 11, 18, 1, 30, 0, 0, ZoneOffset.UTC)));
        // 北京 12:30 = UTC 04:30
        check("UTC 04:30 → 北京 12:30 空闲",
                !policy.isPeak(spec, ZonedDateTime.of(2026, 11, 18, 4, 30, 0, 0, ZoneOffset.UTC)));
    }

    // ==================== 4. 总开关 ====================

    private static void masterSwitch(PeakPeriodProperties base, HolidayCalendar calendar,
                                     PeakPricingSpec spec) {
        System.out.println("[4] 总开关（紧急回滚点）");
        PeakPeriodProperties off = new PeakPeriodProperties();
        off.setEnabled(false);
        PeakPeriodPolicy offPolicy = new PeakPeriodPolicy(off, calendar);
        check("总开关关闭：工作日白天也算空闲",
                !offPolicy.isPeak(spec, at(2026, 11, 18, 10, 0)));
        check("总开关关闭：时段标识为 null", offPolicy.periodOf(spec, at(2026, 11, 18, 10, 0)) == null);
        check("总开关开启：时段标识为 peak",
                PeakPeriodPolicy.PERIOD_PEAK.equals(policyOf(base, calendar).periodOf(spec, at(2026, 11, 18, 10, 0))));
    }

    // ==================== 5. 双价 ====================

    private static void priceItem() {
        System.out.println("[5] 双价（PriceItem.effective / TokenPricingConfig.resolveFor）");

        TokenPricingConfig.PriceItem base = new TokenPricingConfig.PriceItem(0.02, 1.0, 4.0);
        check("未启用峰谷：返回同一对象（行为零变化）", base.effective(true, 2.0) == base);

        base.setPeakPricing(true);
        base.setPeakCacheHitInput(0.04);
        base.setPeakCacheMissInput(2.0);
        base.setPeakOutput(8.0);
        TokenPricingConfig.PriceItem peak = base.effective(true, 2.0);
        check("高峰命中 0.04", near(peak.getCacheHitInput(), 0.04));
        check("高峰未命中 2.0", near(peak.getCacheMissInput(), 2.0));
        check("高峰输出 8.0", near(peak.getOutput(), 8.0));
        check("空闲仍为基准价 4.0", near(base.effective(false, 2.0).getOutput(), 4.0));

        TokenPricingConfig.PriceItem scaled = new TokenPricingConfig.PriceItem(0.02, 1.0, 4.0);
        scaled.setPeakPricing(true);
        TokenPricingConfig.PriceItem scaledPeak = scaled.effective(true, 2.0);
        check("未配 peak 字段：命中按倍率 0.04", near(scaledPeak.getCacheHitInput(), 0.04));
        check("未配 peak 字段：输出按倍率 8.0", near(scaledPeak.getOutput(), 8.0));

        TokenPricingConfig.PriceItem cacheWrite = new TokenPricingConfig.PriceItem(0.02, 1.0, 4.0, 1.25);
        cacheWrite.setPeakPricing(true);
        check("cacheWrite 按倍率放大且不被 miss 覆盖",
                near(cacheWrite.effective(true, 2.0).cacheWritePrice(), 2.5));

        TokenPricingConfig config = new TokenPricingConfig();
        config.getModels().put("deepseek-flash", base);
        check("resolveFor 精确命中高峰价", near(config.resolveFor("deepseek-flash", true, 2.0).getOutput(), 8.0));
        check("resolveFor 未配模型返回 null", config.resolveFor("gpt-4o", false, 2.0) == null);
    }

    // ==================== 6. 下次切换 ====================

    private static void nextSwitch(PeakPeriodPolicy policy, PeakPricingSpec spec) {
        System.out.println("[6] 下次切换时刻");
        ZonedDateTime morning = at(2026, 11, 18, 9, 30);
        ZonedDateTime next = policy.nextSwitchAt(spec, morning);
        check("09:30 的下次切换为 12:00",
                next != null && next.getHour() == 12 && next.getMinute() == 0);

        ZonedDateTime afternoon = at(2026, 11, 18, 15, 30);
        ZonedDateTime next2 = policy.nextSwitchAt(spec, afternoon);
        check("15:30 的下次切换为 18:00",
                next2 != null && next2.getHour() == 18 && next2.getMinute() == 0);
    }

    // ==================== 7. 全局配置继承 ====================

    /**
     * 档案留空（windows / workdays 未显式声明）时必须继承全局配置。
     *
     * <p>这条用例守住一个真实 bug：早期实现把窗口写死在平台档案里，
     * {@code llm.pricing.peak-period.windows} 虽然配了却<b>从未被读取</b>（死配置）。</p>
     */
    private static void globalFallback() {
        System.out.println("[7] 全局配置继承（档案留空 → application.yml）");

        PeakPeriodProperties custom = new PeakPeriodProperties();
        custom.setWindows(List.of("10:00-11:00"));
        custom.setWorkdays(List.of("MON"));
        HolidayCalendar calendar = new HolidayCalendar(custom, new ObjectMapper());
        PeakPeriodPolicy policy = new PeakPeriodPolicy(custom, calendar);

        // 模拟 ProviderRegistry 的写法：只声明 enabled/zone/节假日，窗口与工作日留空
        PeakPricingSpec empty = new PeakPricingSpec();
        empty.setEnabled(true);
        empty.setZone("Asia/Shanghai");

        check("自定义窗口内 10:00（周一）为高峰", policy.isPeak(empty, at(2026, 11, 16, 10, 0)));
        check("自定义窗口外 09:00（周一）为空闲", !policy.isPeak(empty, at(2026, 11, 16, 9, 0)));
        check("自定义窗口外 14:00（周一）为空闲", !policy.isPeak(empty, at(2026, 11, 16, 14, 0)));
        check("周三不在自定义工作日 → 空闲", !policy.isPeak(empty, at(2026, 11, 18, 10, 0)));
        check("windowsOf 返回配置的窗口", policy.windowsOf(empty).equals(List.of("10:00-11:00")));
    }

    // ==================== 工具 ====================

    private static PeakPeriodPolicy policyOf(PeakPeriodProperties props, HolidayCalendar calendar) {
        return new PeakPeriodPolicy(props, calendar);
    }

    private static PeakPricingSpec spec() {
        PeakPricingSpec spec = new PeakPricingSpec();
        spec.setEnabled(true);
        spec.setZone("Asia/Shanghai");
        spec.setWindows(List.of("09:00-12:00", "14:00-18:00"));
        spec.setWorkdays(List.of("MON", "TUE", "WED", "THU", "FRI"));
        spec.setHolidayRegion(PeakPricingSpec.REGION_CN);
        spec.setHonorMakeupWorkdays(false);
        return spec;
    }

    private static ZonedDateTime at(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, CN);
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-9;
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }
}
