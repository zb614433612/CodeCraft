package com.example.agentdeepseek.service;

import com.example.agentdeepseek.service.llm.DeepSeekClient;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link LatestReminderService} 自检（main 方法，非 JUnit）。
 *
 * <p>重点验证：</p>
 * <ol>
 *   <li>位置由 App 时区决定、语言由位置决定，而不是跟着系统语言走；</li>
 *   <li><b>语言值必须是 DeepSeek 官方白名单里的本地语言名</b>（{@code 中文} / {@code 中文繁體} /
 *       {@code 中文香港}），不能是 JVM 回退出的英文名（{@code Chinese}）——安装包内置 JRE
 *       由 jlink 裁剪、未含 jdk.localedata 时 {@code getDisplayLanguage} 会返回英文，
 *       实测线上报错 {@code unknown language 'Chinese'}；</li>
 *   <li>weekday 与 language 同语言（中文配星期几，英文配 Monday）；</li>
 *   <li>历史落库的脏值要被 {@link LatestReminderService#sanitizeForSend(String)} 拦下。</li>
 * </ol>
 *
 * <p>运行方式：</p>
 * <pre>
 * mvn -q -DskipTests test-compile
 * mvn -q org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
 *   -Dexec.mainClass=com.example.agentdeepseek.service.LatestReminderServiceCheck \
 *   -Dexec.classpathScope=test
 * </pre>
 */
public class LatestReminderServiceCheck {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        LatestReminderService service = new LatestReminderService();

        String shanghai = service.buildCurrent("Asia/Shanghai", "en-US");
        check(shanghai.startsWith(LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()),
                "日期应为上海当天，实际=" + shanghai);
        check(shanghai.endsWith(",App,中文"),
                "上海应输出中文（不能跟 en-US 走），实际=" + shanghai);
        check(weekdayOf(shanghai).matches("星期[一二三四五六日]"),
                "中文 reminder 的 weekday 必须是中文星期，实际=" + weekdayOf(shanghai));

        String newYork = service.buildCurrent("America/New_York", "zh-CN");
        check(newYork.endsWith(",App,English"),
                "纽约应输出 English（不能跟 zh-CN 走），实际=" + newYork);
        check(weekdayOf(newYork).matches("(Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)"),
                "英文 reminder 的 weekday 必须是英文星期，实际=" + weekdayOf(newYork));

        String taipei = service.buildCurrent("Asia/Taipei", "zh-CN");
        check(taipei.endsWith(",App,中文繁體"),
                "台北应输出官方值 中文繁體，实际=" + taipei);

        String hongKong = service.buildCurrent("Asia/Hong_Kong", "zh-CN");
        check(hongKong.endsWith(",App,中文香港"),
                "香港应输出官方值 中文香港，实际=" + hongKong);

        String berlin = service.buildCurrent("Europe/Berlin", "zh-CN");
        check(berlin.endsWith(",App,Deutsch"),
                "柏林应输出本地语言名 Deutsch，实际=" + berlin);

        // 挪威语不在 DeepSeek 官方白名单里，必须回退 English，否则整轮 400
        String oslo = service.buildCurrent("Europe/Oslo", "zh-CN");
        check(oslo.endsWith(",App,English"),
                "挪威语不在官方白名单，应回退 English，实际=" + oslo);

        String fallback = service.buildCurrent("Invalid/Zone", "zh-CN");
        check(fallback.contains(",App,"), "非法时区回退后仍应有 App 平台，实际=" + fallback);
        check(fallback.endsWith(",中文"), "非法时区应按 zh-CN 回退中文，实际=" + fallback);

        // ==================== 历史脏值清洗 ====================

        String good = "2026-11-26,星期四,中国,App,中文";
        check(good.equals(service.sanitizeForSend(good)), "合法 reminder 应原样下发");

        check(service.sanitizeForSend("2026-11-26,Thursday,China,App,Chinese") == null,
                "旧版落库的 language=Chinese 必须丢弃（否则 unknown language 400）");
        check(service.sanitizeForSend("2026-11-26,Thursday,China,App,English") != null,
                "language=English 属于官方白名单，应保留");
        check(service.sanitizeForSend("2026-11-26,星期四,韩国,App,Korean") == null,
                "language=Korean 不是官方值（应为 한국어），必须丢弃");
        check(service.sanitizeForSend(null) == null, "null 应返回 null");
        check(service.sanitizeForSend("   ") == null, "空白应返回 null");
        check(service.sanitizeForSend("2026-11-26,星期四,中国,App") == null, "段数不足 5 应丢弃");
        check(service.sanitizeForSend("not-a-date,星期四,中国,App,中文") == null, "日期非法应丢弃");
        check(service.sanitizeForSend("2026-11-26,Thursday,\"Korea, Republic of\",App,English") != null,
                "location 含逗号是官方允许的，应保留");

        // ==================== 适配器出口过滤 ====================

        Map<String, Object> reminder = Map.of("role", "latest_reminder", "content", good);
        Map<String, Object> user = Map.of("role", "user", "content", "你好");
        List<Map<String, Object>> messages = new ArrayList<>(List.of(reminder, user));

        DeepSeekClient deepseek = new DeepSeekClient(null, new ObjectMapper(), "deepseek");
        check(deepseek.prepareMessages(messages).size() == 2,
                "DeepSeek 适配器应保留 latest_reminder");

        DeepSeekClient unsupported = new DeepSeekClient(null, new ObjectMapper(), "openai", false);
        check(unsupported.prepareMessages(messages).size() == 1,
                "非 DeepSeek 适配器必须剔除 latest_reminder");

        System.out.printf("LatestReminderServiceCheck: passed=%d failed=%d%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 取 reminder 的第二段（weekday，按 ASCII 逗号切分；location 在更后面不受影响） */
    private static String weekdayOf(String reminder) {
        String[] parts = reminder.split(",", -1);
        return parts.length > 1 ? parts[1] : "";
    }

    private static void check(boolean condition, String message) {
        if (condition) {
            passed++;
            System.out.println("[PASS] " + message);
        } else {
            failed++;
            System.out.println("[FAIL] " + message);
        }
    }
}
