package com.example.agentdeepseek.util;

import reactor.core.publisher.Flux;

import java.time.Duration;

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

    private SseBatchUtil() {
    }

    /**
     * 将事件流按时间窗口聚合为批量帧：窗口内多个事件以 "\n" 连接为一个 payload 输出。
     * 单事件批输出原样（join 单元素无额外开销）。
     *
     * @param source 事件流（每个元素为一帧 payload，不含 "data: " 前缀）
     * @return 聚合后的事件流
     */
    public static Flux<String> batch(Flux<String> source) {
        return source
                .bufferTimeout(BATCH_MAX_SIZE, BATCH_WINDOW)
                .map(batch -> String.join("\n", batch))
                .filter(s -> !s.isEmpty());
    }
}
