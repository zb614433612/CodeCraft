package com.example.agentdeepseek.util;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE 传输层事件聚合工具（M5 流式渲染性能改造）。
 *
 * <p><b>背景</b>：长任务流式期间，每个 LLM chunk / 工具事件都作为独立 SSE 帧推送，
 * 网络帧数、服务端 flush 次数、前端读取次数均为 O(事件数)。本工具把时间窗口内
 * 到达的多个事件合并为单个 SSE 帧（多行 data 传输），降低上述成本。</p>
 *
 * <p><b>语义安全性（源码级证据）</b>：
 * <ul>
 *   <li>合并方式为 "\n" 连接：Spring {@code ServerSentEventHttpMessageWriter} 对 data 中
 *       每个换行替换为 "\ndata: "（{@code SseUtils.appendFieldValue}），即一个帧被编码为
 *       多行 data、每行仍带 data: 前缀，不会被转义；</li>
 *   <li>前端 {@code readSseStream} 按行解析（extractDataChunks + processDataChunk），
 *       "一帧多行"与"多帧"在行粒度完全等价（已用真实前端模块微测试验证：聚合前后事件序列逐项一致）；</li>
 *   <li>聚合发生在 SSE 输出边界（Controller）。AgentEventBus 的 seq 分配、重连游标
 *       （skipWhile seq &lt;= cursor）与 replay 缓冲均为事件粒度，完全不受影响；</li>
 *   <li>bufferTimeout 语义（reactor-core 3.7.0 源码确认）：批内首元素到达时启动计时，
 *       最多等待窗口时长；满 maxSize 立即发出；上游 complete 时 flush 剩余缓冲
 *       （[DONE] 不丢）；cancel/error 时丢弃缓冲（客户端已断开/出错，可接受）。</li>
 * </ul>
 * </p>
 *
 * <p><b>适用范围</b>：仅用于「面向浏览器的 SSE 出口」（DeepSeekController 的两个流式端点）。
 * 内部逐元素消费者（P2pAgentService、AgentInvokeServiceImpl 等）直接消费 Service 返回的流，
 * 聚合会破坏其逐帧解析假设——禁止在 Service 入口聚合。</p>
 */
public final class SseBatchUtil {

    /** 聚合窗口：窗口内到达的事件合并为一帧。50ms 与前端更新去重窗同量级，保持既有响应性，可按实测调整 */
    private static final Duration BATCH_WINDOW = Duration.ofMillis(50);

    /** 单批最大事件数（保险上限：密集爆发时提前发出，防单帧过大） */
    private static final int BATCH_MAX_SIZE = 32;

    /**
     * 心跳静默阈值：上游连续无事件达到该时长，才补发一次心跳（2026-11-16 新增）。
     *
     * <p>取 15s 的理由：链路中间层（代理 / 防火墙 / 反代 / 连接跟踪表）的空闲判定普遍
     * 是 30~60s，15s 留足 2~4 倍安全余量；同时比实测的最长轮内静默（90~125s）短一个
     * 数量级，能可靠覆盖工具执行期。</p>
     */
    private static final Duration HEARTBEAT_QUIET = Duration.ofSeconds(15);

    /**
     * 心跳探测周期：轻量 tick，只用于判断「上游是否结束 / 是否静默够久」，本身不直接发帧。
     *
     * <p>为什么不用 {@code Flux.interval(HEARTBEAT_QUIET)} 直接发：那样上游正常结束后
     * 收尾要等满一个周期（15s），连接会悬挂不关。1s tick 让收尾最多延迟 1s。</p>
     */
    private static final Duration HEARTBEAT_TICK = Duration.ofSeconds(1);

    /**
     * 心跳载荷：单一键的 JSON 事件（<b>不要改成 SSE 注释行</b>）。
     *
     * <p>本方法的输出会经 {@code ServerSentEvent.builder(chunk)} 编码，Spring 给 payload 的
     * 每个换行补 "data: " 前缀。若用 {@code : keepalive} 注释行，会被编码成
     * {@code data: : keepalive}——它不是合法 JSON，前端 {@code processDataChunk} 会落到
     * 「非 JSON → 当正文」分支，把 ": keepalive" 追加进正文污染界面。</p>
     *
     * <p>用单键 JSON 后，前端解析器沿「parsed.event 不匹配任何已知类型 / 无 choices /
     * 无 thinking / 无 content / 无 sessionId」一路落到 {@code return null}，
     * 被 {@code if (event) yield event} 自然丢弃——<b>前端无需任何改动</b>。</p>
     */
    public static final String HEARTBEAT_PAYLOAD = "{\"event\":\"heartbeat\"}";

    private SseBatchUtil() {
    }

    /**
     * 将事件流按时间窗口聚合为批量帧，并附加静默期心跳保活。
     *
     * <p>输出既包含聚合后的真实事件帧，也包含静默超过 {@link #HEARTBEAT_QUIET} 时补发的
     * {@link #HEARTBEAT_PAYLOAD} 帧；上游正常结束（或取消/异常）后最多
     * {@link #HEARTBEAT_TICK} 即完成收尾，不会悬挂连接。</p>
     *
     * @param source 事件流（每个元素为一帧 payload，不含 "data: " 前缀）
     * @return 聚合 + 心跳后的数据流
     */
    public static Flux<String> batch(Flux<String> source) {
        return batch(source, HEARTBEAT_QUIET, HEARTBEAT_TICK);
    }

    /**
     * 可注入周期版本的聚合 + 心跳（供自检/单测使用，避免真等 15 秒）。
     *
     * <p>与 {@link #batch(Flux)} 的差异仅在于心跳阈值与探测周期可配；生产代码请一律走
     * {@link #batch(Flux)}，否则各出口的心跳节奏会不一致（中间层空闲判定就摸不准）。</p>
     *
     * @param source     事件流
     * @param quiet      静默多久才补心跳
     * @param tickPeriod 心跳探测周期
     * @return 聚合 + 心跳后的数据流
     */
    static Flux<String> batch(Flux<String> source, Duration quiet, Duration tickPeriod) {
        // 上游终止标志：complete / error / cancel 都会置位，供心跳分支快速收敛
        AtomicBoolean upstreamDone = new AtomicBoolean(false);
        // 最近一次真实事件到达时刻：用于判断是否进入静默期
        AtomicLong lastEventAtMs = new AtomicLong(System.currentTimeMillis());

        Flux<String> batched = source
                .doOnNext(event -> lastEventAtMs.set(System.currentTimeMillis()))
                .doFinally(signal -> upstreamDone.set(true))
                .bufferTimeout(BATCH_MAX_SIZE, BATCH_WINDOW)
                .map(batch -> String.join("\n", batch))
                .filter(payload -> !payload.isEmpty());

        /*
         * 心跳分支：与事件分支平行，**不经过 bufferTimeout**——并入批窗口会在静默期
         * 被攒住（批窗口靠「首元素到达」启动计时，静默期没有事件唤醒它，心跳发不出去）。
         */
        Flux<String> heartbeats = Flux.interval(tickPeriod, Schedulers.parallel())
                // 上游已结束 → 立即收敛，避免连接悬挂
                .takeWhile(tick -> !upstreamDone.get())
                // 仅静默够久才补心跳：密集输出期不产生多余帧
                .filter(tick -> System.currentTimeMillis() - lastEventAtMs.get() >= quiet.toMillis())
                .map(tick -> HEARTBEAT_PAYLOAD);

        // merge 而非 concat：心跳必须能在事件静默期间独立发出
        return Flux.merge(batched, heartbeats);
    }
}
