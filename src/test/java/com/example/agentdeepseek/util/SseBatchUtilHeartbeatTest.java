package com.example.agentdeepseek.util;

import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * {@link SseBatchUtil} 心跳保活自检（main 方法，非 JUnit）。
 *
 * <h3>为什么需要这个自检</h3>
 * <p>心跳属于「不生效也看不出来」的改动：写错了不会编译失败、不会抛异常，
 * 只会表现为「断连照旧」。所以必须用真实时间轴验证三件事：</p>
 * <ol>
 *   <li><b>静默期必须发心跳</b>——否则中间层照样掐连接，改动等于没做；</li>
 *   <li><b>密集输出期不该发心跳</b>——否则白白增加帧数与流量；</li>
 *   <li><b>上游结束后必须快速收敛</b>——否则连接悬挂 15 秒，前端「任务已完成」
 *       却迟迟不收尾，比断连更难受。</li>
 * </ol>
 *
 * <p>运行方式（用 test classpath，因为要 reactor）：</p>
 * <pre>
 * mvn -q -f "C:\work\IdeaProjects\CodeCraft\pom.xml" \
 *   org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
 *   -Dexec.mainClass=com.example.agentdeepseek.util.SseBatchUtilHeartbeatTest \
 *   -Dexec.classpathScope=test -DskipFrontend=true
 * </pre>
 */
public class SseBatchUtilHeartbeatTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== SseBatchUtil 心跳自检开始 ===");

        // 自检用缩短的周期（真实 15s/1s 会让自检跑几十秒，失去快速反馈意义）
        Duration quiet = Duration.ofMillis(300);
        Duration tick = Duration.ofMillis(100);

        testQuietPeriodEmitsHeartbeat(quiet, tick);
        testBusyPeriodEmitsNoHeartbeat(quiet, tick);
        testUpstreamCompletionConvergesQuickly(quiet, tick);
        testHeartbeatPayloadIgnorableByFrontend();

        System.out.println("=== 结果: " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 用例 1：上游静默超过阈值 → 必须补发心跳（这是本次修复的核心目标） */
    private static void testQuietPeriodEmitsHeartbeat(Duration quiet, Duration tick) throws Exception {
        // 上游先发一条事件，然后静默 900ms 才结束 —— 静默期足够覆盖 3 个 quiet 周期
        Flux<String> source = Flux.just("{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}")
                .concatWith(Flux.<String>never())
                .take(Duration.ofMillis(900));

        List<String> received = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        SseBatchUtil.batch(source, quiet, tick)
                .subscribe(received::add, e -> latch.countDown(), latch::countDown);
        boolean done = latch.await(5, TimeUnit.SECONDS);

        long heartbeatCount = received.stream()
                .filter(s -> s.contains("heartbeat"))
                .count();

        // 900ms 静默 / 300ms 阈值 → 至少应有 2 次心跳（留 1 次余量防调度抖动）
        boolean ok = done && heartbeatCount >= 2;
        report("静默期必须发心跳（实际 " + heartbeatCount + " 次，期望 >=2，上游结束=" + done + "）", ok);
    }

    /** 用例 2：上游持续密集输出 → 不应产生多余心跳（否则纯属浪费帧） */
    private static void testBusyPeriodEmitsNoHeartbeat(Duration quiet, Duration tick) throws Exception {
        // 每 50ms 发一条，持续 800ms —— 始终短于 300ms 静默阈值，理论上不该有心跳
        Flux<String> source = Flux.interval(Duration.ofMillis(50))
                .take(16)
                .map(i -> "{\"choices\":[{\"delta\":{\"content\":\"c" + i + "\"}}]}");

        List<String> received = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        SseBatchUtil.batch(source, quiet, tick)
                .subscribe(received::add, e -> latch.countDown(), latch::countDown);
        boolean done = latch.await(5, TimeUnit.SECONDS);

        long heartbeatCount = received.stream()
                .filter(s -> s.contains("heartbeat"))
                .count();
        long contentCount = received.stream()
                .filter(s -> s.contains("content"))
                .count();

        // 密集输出期心跳应为 0；内容事件必须全部送达（不能被心跳挤掉）
        boolean ok = done && heartbeatCount == 0 && contentCount > 0;
        report("密集输出期不发心跳（实际 " + heartbeatCount + " 次，内容帧 " + contentCount
                + " 个，上游结束=" + done + "）", ok);
    }

    /** 用例 3：上游完成 → 心跳分支必须快速收敛，不能悬挂一个周期 */
    private static void testUpstreamCompletionConvergesQuickly(Duration quiet, Duration tick) throws Exception {
        Flux<String> source = Flux.just("a", "b", "c").delayElements(Duration.ofMillis(20));

        long startMs = System.currentTimeMillis();
        CountDownLatch latch = new CountDownLatch(1);
        SseBatchUtil.batch(source, quiet, tick)
                .subscribe(s -> { }, e -> latch.countDown(), latch::countDown);
        boolean done = latch.await(5, TimeUnit.SECONDS);
        long elapsedMs = System.currentTimeMillis() - startMs;

        /*
         * 上游约 60ms 结束；心跳 tick 为 100ms，因此收尾应在 ~1 个 tick 内完成。
         * 阈值取 600ms：既容忍调度抖动，又能在「心跳分支没收敛」时明确失败
         * （未收敛会等 quiet=300ms 之后才可能停，甚至一直挂着直到 5s 超时）。
         */
        boolean ok = done && elapsedMs < 600;
        report("上游结束后快速收敛（耗时 " + elapsedMs + "ms，期望 <600ms，上游结束=" + done + "）", ok);
    }

    /** 用例 4：心跳载荷必须能被前端解析器安全忽略（防止污染正文） */
    private static void testHeartbeatPayloadIgnorableByFrontend() {
        String payload = SseBatchUtil.HEARTBEAT_PAYLOAD;
        /*
         * 前端 SseParser.processDataChunk 的判定链：
         *   1) JSON.parse 成功 → parsed.event 不匹配任何已知类型
         *   2) 无 choices / thinking / content 字段
         *   3) 非 string、对象仅 1 个键且不含 'sessionId'
         *   → 落到最后的 else，return null → 被 if (event) yield event 丢弃
         *
         * 这里用纯字符串断言复刻关键前提：必须是合法 JSON、必须只有一个键、
         * 且键名不是 sessionId（否则前端会把它当成「纯 sessionId 事件」推给上层）。
         */
        boolean isJsonObject = payload.startsWith("{") && payload.endsWith("}");
        boolean onlyEventKey = payload.contains("\"event\"")
                && !payload.contains("\"sessionId\"")
                && !payload.contains("\"choices\"")
                && !payload.contains("\"content\"")
                && !payload.contains("\"thinking\"");
        boolean notCommentLine = !payload.trim().startsWith(":");
        report("心跳载荷可被前端安全忽略（合法JSON=" + isJsonObject
                + ", 单键且不含会话/内容字段=" + onlyEventKey
                + ", 非注释行=" + notCommentLine + "）",
                isJsonObject && onlyEventKey && notCommentLine);
    }

    private static void report(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }
}
