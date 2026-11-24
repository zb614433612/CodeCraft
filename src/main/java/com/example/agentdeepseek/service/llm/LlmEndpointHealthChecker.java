package com.example.agentdeepseek.service.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LLM 端点健康探测任务 —— 定时对每个已跟踪 host 的<b>所有</b> IP 做 TCP 探活，
 * 把「健康的、且最快连上」的 IP 写入 {@link LlmEndpointHealthRegistry}。
 *
 * <h3>解决什么问题</h3>
 * <p>
 * 实测（2026-11-25，本机）20 次全新 TCP 建连到 {@code api.deepseek.com:443}：
 * 17 次约 50ms，但各有 1 次卡 <b>1057ms</b> 与 <b>11073ms</b>；同一时刻 ping 20 次 0% 丢包。
 * Windows 的 {@code Initial RTO=1000ms} / {@code Max SYN Retransmissions=4} 决定了重传退避为
 * 1s→2s→4s→8s —— 丢几个 SYN 就累积成 1/3/7/15 秒，且<b>最终建连成功、不抛异常</b>，
 * 所以应用层看不到重试日志，只表现为「首 token 卡 10 秒」。
 * </p>
 * <p>
 * 而 DNS 返回多个 CDN IP 且<b>顺序会轮换</b>，Netty 的默认解析只取第一个 IP（无 Happy Eyeballs），
 * 于是「坏 IP 排第一」时必然卡满重传窗口。本任务提前把这些坏 IP 探测出来并排除。
 * </p>
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li><b>不依赖 LLMClientManager</b>：只从 registry 的 trackedHosts 取目标，
 *       避免与 {@code LLMWebClientManager} / {@code LLMClientManager} 形成循环依赖。</li>
 *   <li><b>独立线程池</b>：探活是阻塞 socket 调用，放在专用线程池而非 Reactor 的 boundedElastic，
 *       避免与业务请求争抢线程。</li>
 *   <li><b>并发探活 + 总超时</b>：每个 IP 一个任务，整体等待 {@code probeTimeoutMs + 余量}，
 *       多个 IP 不会串行累加（否则 4 个 IP × 1.5s = 6 秒/轮）。</li>
 *   <li><b>全部失败时写空列表</b>：让 resolver 回退到默认 DNS 解析 —— 宁可慢也不能连不上。</li>
 * </ul>
 */
@Slf4j
@Component
public class LlmEndpointHealthChecker {

    private final LlmEndpointHealthRegistry registry;

    /** 是否启用健康优选（false 时完全不探活，resolver 自动回退默认解析） */
    @Value("${llm.resolver.prefer-healthy:true}")
    private boolean enabled;

    /** 单个 IP 的探活超时（毫秒）。取值需大于正常建连耗时（实测约 50ms），又要明显小于 SYN 重传窗口 */
    @Value("${llm.resolver.probe-timeout-ms:1500}")
    private int probeTimeoutMs;

    /** 探活线程池：daemon + 带名字，便于 jstack 定位 */
    private final ExecutorService probePool = Executors.newCachedThreadPool(new ThreadFactory() {
        private final AtomicInteger seq = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "llm-ip-probe-" + seq.incrementAndGet());
            t.setDaemon(true);   // daemon：不阻止 JVM 退出
            return t;
        }
    });

    public LlmEndpointHealthChecker(LlmEndpointHealthRegistry registry) {
        this.registry = registry;
    }

    /**
     * 定时探活入口。
     *
     * <p>{@code initialDelay} 给 8 秒：等应用启动稳定、WebClient 建好并登记 host 之后再开始，
     * 避免首轮扫到空集合。</p>
     */
    @Scheduled(initialDelayString = "${llm.resolver.probe-initial-delay-ms:8000}",
            fixedDelayString = "${llm.resolver.probe-interval-ms:60000}")
    public void probeAll() {
        if (!enabled) {
            return;
        }
        List<String> hosts = registry.trackedHosts();
        if (hosts.isEmpty()) {
            // 还没有任何 WebClient 被创建（用户尚未发起请求），无需探活
            log.debug("[LLM-IP] 暂无可跟踪端点，跳过本轮探活");
            return;
        }
        int ok = 0;
        for (String host : hosts) {
            try {
                probeHost(host);
                ok++;
            } catch (Exception e) {
                // 单个 host 失败不影响其他 host
                log.warn("[LLM-IP] 探测 host={} 异常（本轮跳过）: {}", host, e.getMessage());
            }
        }
        log.debug("[LLM-IP] 本轮探活结束：{}/{} 个 host 完成", ok, hosts.size());
    }

    /**
     * 探测单个 host：解析全部 IP → 并发 TCP 探活 → 按耗时升序写回 registry。
     */
    private void probeHost(String host) {
        // ① 解析全部 IP（走 JVM DNS 缓存，30 秒 TTL；过期会重新查询，正好符合 60 秒一轮的节奏）
        InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(host);
        } catch (Exception e) {
            log.warn("[LLM-IP] DNS 解析失败 host={}，本轮保留原有记录: {}", host, e.getMessage());
            return;
        }
        if (resolved == null || resolved.length == 0) {
            return;
        }
        List<InetAddress> all = List.of(resolved);
        int port = registry.portOf(host);

        // ② 并发探活：每个 IP 一个任务，记录成功连通的耗时（毫秒）
        Map<InetAddress, Long> costByIp = new ConcurrentHashMap<>();
        List<Future<?>> futures = new ArrayList<>(all.size());
        for (InetAddress ip : all) {
            futures.add(probePool.submit(() -> {
                long cost = tcpProbe(ip, port);
                if (cost >= 0) {
                    costByIp.put(ip, cost);
                }
            }));
        }

        // ③ 统一等待（总超时 = 单 IP 超时 + 1 秒余量），避免某个任务卡住拖垮整轮
        long deadline = System.currentTimeMillis() + probeTimeoutMs + 1000L;
        for (Future<?> f : futures) {
            long remain = deadline - System.currentTimeMillis();
            try {
                f.get(Math.max(1L, remain), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                // 超时/中断：该 IP 视为不健康，不写入 costByIp
                f.cancel(true);
            }
        }

        // ④ 按耗时升序 → 最快的排第一（resolver 直接取 index 0）
        List<InetAddress> healthy = costByIp.entrySet().stream()
                .sorted(Comparator.comparingLong(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toList());

        // ⑤ 写回（healthy 为空时 registry 会移除旧记录 → resolver 回退默认解析）
        registry.update(host, all, healthy);
    }

    /**
     * 单次 TCP 探活。
     *
     * <p>用最短路径的裸 Socket 连接，<b>不发任何 TLS / HTTP</b>：目的是判断「SYN 能否被对端接受」，
     * 而不是验证 API 可用性（那是业务请求的事）。因此即便服务端要求 TLS，SYN→SYN-ACK 阶段已经能说明链路可用。</p>
     *
     * @return 连通耗时（毫秒，≥0）；失败或超时返回 -1
     */
    private long tcpProbe(InetAddress ip, int port) {
        long start = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), probeTimeoutMs);
            return (System.nanoTime() - start) / 1_000_000L;
        } catch (Exception e) {
            log.debug("[LLM-IP] {}:{} 探活失败: {}", ip.getHostAddress(), port, e.getMessage());
            return -1L;
        }
    }

    @PreDestroy
    public void shutdown() {
        probePool.shutdownNow();
        log.info("[LLM-IP] 探活线程池已关闭");
    }
}
