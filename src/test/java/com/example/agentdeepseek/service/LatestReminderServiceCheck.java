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
 * <p>重点验证：位置由 App 时区决定、语言由位置决定，而不是跟着系统语言走；
 * 以及非法时区要能安全回退，不能因为元信息构建失败影响对话。</p>
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
        check(shanghai.endsWith(",中国,App,中文"),
                "上海应输出 中国/中文（不能跟 en-US 走），实际=" + shanghai);

        String newYork = service.buildCurrent("America/New_York", "zh-CN");
        check(newYork.endsWith(",United States,App,English"),
                "纽约应输出 United States/English（不能跟 zh-CN 走），实际=" + newYork);

        String fallback = service.buildCurrent("Invalid/Zone", "zh-CN");
        check(fallback.contains(",App,"), "非法时区回退后仍应有 App 平台，实际=" + fallback);
        check(fallback.endsWith(",中文"), "非法时区应按 zh-CN 回退中文，实际=" + fallback);

        // 适配器出口过滤：DeepSeek 保留 latest_reminder；其他适配器必须剔除
        Map<String, Object> reminder = Map.of("role", "latest_reminder", "content", "2026-11-26,星期三,中国,App,中文");
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
