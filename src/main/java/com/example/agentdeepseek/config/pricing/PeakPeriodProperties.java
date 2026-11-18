package com.example.agentdeepseek.config.pricing;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 峰谷计价 - 全局时段策略配置（{@code llm.pricing.peak-period.*}）。
 *
 * <h3>分工：这里是「全局策略」，平台档案是「平台事实」，模型配置是「单价」</h3>
 * <ul>
 *   <li>{@link com.example.agentdeepseek.service.llm.profile.ProviderProfile.PeakPricingSpec}
 *       —— <b>哪个平台分峰谷、窗口几点、节假日按哪个地区</b>（代码档案，随平台新增而新增）</li>
 *   <li>本类 —— <b>全局开关 / 默认时区 / 兜底倍率 / 节假日数据源</b></li>
 *   <li>{@code llm.pricing.models.<模型>} —— <b>空闲价与高峰价的具体数字</b></li>
 * </ul>
 *
 * <h3>为什么要有 {@link #enabled} 这个总开关</h3>
 * <p>
 * 峰谷判定一旦出问题（时区、节假日数据错），会直接影响金额。总开关 + 各模型
 * {@code peak-pricing} 开关构成了<b>两级回滚点</b>：关掉即回到「全天一个价」，
 * 不需要改代码、不需要回滚版本。
 * </p>
 */
@Configuration
@Data
@ConfigurationProperties(prefix = "llm.pricing.peak-period")
public class PeakPeriodProperties {

    /**
     * 峰谷计价总开关。
     * <p>{@code false} 时所有平台一律按空闲价（基准价）计算，等价于改造前的行为。</p>
     */
    private boolean enabled = true;

    /** 默认判定时区（平台档案未指定时使用；<b>永远不要依赖 JVM 默认时区</b>） */
    private String zone = "Asia/Shanghai";

    /**
     * <b>全局默认高峰窗口</b>（{@code HH:mm-HH:mm}，左闭右开）。
     *
     * <p>平台档案（{@code PeakPricingSpec}）<b>未声明</b>窗口时使用本配置；
     * 档案声明了则以档案为准（平台事实优先）。这样「改配置就能调时段」是真的生效，
     * 而不是写死在代码里。</p>
     */
    private List<String> windows = new ArrayList<>(List.of("09:00-12:00", "14:00-18:00"));

    /** 全局默认工作日（档案未声明时使用；空表示按周一到周五） */
    private List<String> workdays = new ArrayList<>(
            List.of("MON", "TUE", "WED", "THU", "FRI"));

    /**
     * 兜底倍率：模型只配了基准价、没配 {@code peak-*} 单价时，高峰价 = 基准价 × 本值。
     * <p>仅对「显式开启 {@code peak-pricing} 的模型」生效，其余模型不受影响。</p>
     */
    private double peakMultiplier = 2.0;

    /** 节假日数据源配置 */
    private Holiday holiday = new Holiday();

    /**
     * 中国法定节假日数据源配置。
     *
     * <h3>数据源优先级（后者不覆盖前者的「已确认」状态，只补缺）</h3>
     * <ol>
     *   <li>内置表 {@code classpath:holidays/cn-holidays.json} —— <b>离线保底，永远可用</b></li>
     *   <li>本地缓存 {@code <cache-dir>/cf-<year>.json} —— 上次远端拉取的结果</li>
     *   <li>远端（可选）—— 补齐内置表未覆盖的年份（如次年安排刚公布）</li>
     *   <li>{@link #extraHolidays} / {@link #extraWorkdays} —— <b>手工覆盖，优先级最高</b></li>
     * </ol>
     */
    @Data
    public static class Holiday {
        /** 内置表里已带的地区标识 */
        public static final String REGION_CN = "CN";

        /**
         * 数据源模式：
         * <ul>
         *   <li>{@code AUTO}（默认）：内置表 + 本地缓存 + 异步尝试远端</li>
         *   <li>{@code BUILTIN_CN}：只用内置表（完全离线）</li>
         *   <li>{@code REMOTE}：远端优先，失败回退内置表</li>
         *   <li>{@code CONFIG_ONLY}：只用 {@code extra-holidays}（自定义场景）</li>
         *   <li>{@code NONE}：不使用节假日日历（仅按星期判定）</li>
         * </ul>
         */
        private String source = "AUTO";

        /**
         * 远端地址模板，{@code {year}} 会被替换为年份。
         * <p>默认用 holiday-cn 的静态 JSON（含法定节假日与调休上班日，每年更新）。</p>
         */
        private String remoteUrl = "https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/{year}.json";

        /** 远端请求超时（毫秒）；超时按失败处理，不阻塞主链路 */
        private int remoteTimeoutMs = 3000;

        /** 本地缓存目录（相对项目根目录） */
        private String cacheDir = "data/holiday-cache";

        /** 预热时向后覆盖的年数（当前年 + N） */
        private int yearsAhead = 1;

        /** 手工补充的节假日（{@code yyyy-MM-dd}），优先级最高 */
        private List<String> extraHolidays = new ArrayList<>();

        /** 手工补充的调休上班日（{@code yyyy-MM-dd}），优先级最高 */
        private List<String> extraWorkdays = new ArrayList<>();
    }
}
