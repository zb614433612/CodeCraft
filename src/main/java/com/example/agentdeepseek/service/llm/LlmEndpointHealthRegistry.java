package com.example.agentdeepseek.service.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LLM 端点 IP 健康注册表 —— 记录每个 host「全部 IP」与「健康 IP」，供自定义 resolver 择优返回。
 *
 * <h3>为什么需要它（2026-11-25 实测结论）</h3>
 * <p>
 * 本机 20 次全新 TCP 建连到 {@code api.deepseek.com:443} 的实测分布：
 * </p>
 * <pre>
 *   48,48,49,49,49,49,49,49,50,50,50,50,50,50,50,51,56   ← 17 次正常（约 50ms）
 *   1057                                                 ← 1 次卡 1 秒
 *   11073                                                ← 1 次卡 11 秒
 *
 *   同一时刻 ping 同一域名 20 次：0% 丢包、7~9ms
 *   Windows：Initial RTO = 1000ms、Max SYN Retransmissions = 4
 * </pre>
 * <p>
 * 结论：<b>ICMP 不丢包，丢的是 TCP SYN</b>。内核重传退避为 1s→2s→4s→8s，
 * 丢几个包就累积成 1/3/7/15 秒，且最终建连成功、不抛异常，
 * 所以应用层看不到任何重试日志，只表现为「首 token 卡 10 秒」
 * （实测 TTFB=10550ms 中，本地准备仅 9ms、网络段 10541ms）。
 * </p>
 *
 * <h3>为什么「偶发」而不是「一直慢」</h3>
 * <p>
 * DNS 对同一域名返回多个 CDN IP，且<b>顺序会轮换</b>（实测两次解析顺序相反）。
 * Netty 的 {@code DefaultNameResolver} 走 {@code InetAddress.getByName()}，
 * <b>只取第一个 IP</b>，没有 Happy Eyeballs。
 * 于是「DNS 恰好把坏 IP 排第一」时就会卡满重传窗口，排第一是好 IP 时只要 50ms。
 * </p>
 *
 * <h3>本类做什么</h3>
 * <p>
 * 由 {@link LlmEndpointHealthChecker} 定时写入探测结果；
 * 由 {@link HealthyAddressResolverGroup} 在解析时读取，<b>优先返回健康的、且探活最快的 IP</b>，
 * 从而把「坏 IP 排第一」的概率压到接近 0。
 * </p>
 * <p>
 * 所有方法线程安全（ConcurrentHashMap + 不可变快照）。无任何外部依赖，不参与 Spring 循环依赖。
 * </p>
 */
@Slf4j
@Component
public class LlmEndpointHealthRegistry {

    /** host → 最近一次解析出的全部 IP（含不健康的，仅用于展示/诊断） */
    private final Map<String, List<InetAddress>> allByHost = new ConcurrentHashMap<>();

    /** host → 健康 IP，按「探活耗时升序」排列，最快的排第一 */
    private final Map<String, List<InetAddress>> healthyByHost = new ConcurrentHashMap<>();

    /** host → 最近一次探活完成的时刻（epoch millis），用于防止陈旧数据长期生效 */
    private final Map<String, Long> probedAtMs = new ConcurrentHashMap<>();

    /** host → 探活应使用的端口（由 WebClient 创建时的 baseUrl 推导；HTTPS 默认 443、HTTP 默认 80） */
    private final Map<String, Integer> portByHost = new ConcurrentHashMap<>();

    /** 已经被注册、需要参与探活的 host 集合（由 WebClient 创建时登记，避免依赖 LLMClientManager 造成循环依赖） */
    private final Set<String> trackedHosts = Collections.synchronizedSet(new LinkedHashSet<>());

    /** 累计完成的探活轮次（观测用） */
    private final AtomicLong probeRounds = new AtomicLong();

    // ==================== 登记 ====================

    /**
     * 登记一个需要参与健康探测的端点（由 {@link LLMWebClientManager} 在创建 WebClient 时调用）。
     *
     * <p>幂等：同一 host 重复登记只会更新端口，不产生额外开销。</p>
     *
     * @param host 域名（如 {@code api.deepseek.com}）
     * @param port 探活端口（HTTPS 通常 443；Ollama 等本地服务可能是 11434）
     */
    public void trackHost(String host, int port) {
        if (host == null || host.isBlank()) {
            return;
        }
        String normalized = host.trim().toLowerCase();
        int effectivePort = port > 0 ? port : 443;
        boolean isNew = trackedHosts.add(normalized);
        portByHost.put(normalized, effectivePort);
        if (isNew) {
            log.info("[LLM-IP] 开始跟踪端点 host={}:{}（等待首次健康探测）", normalized, effectivePort);
        }
    }

    /** 该 host 探活应使用的端口；未登记时回退 443（LLM API 绝大多数是 HTTPS） */
    public int portOf(String host) {
        if (host == null || host.isBlank()) {
            return 443;
        }
        Integer port = portByHost.get(host.trim().toLowerCase());
        return port == null ? 443 : port;
    }

    /** 当前所有被跟踪的 host 快照（探活任务遍历用） */
    public List<String> trackedHosts() {
        synchronized (trackedHosts) {
            return new ArrayList<>(trackedHosts);
        }
    }

    // ==================== 写入探测结果 ====================

    /**
     * 写入一次探测结果。
     *
     * @param host    域名（会被归一化为小写）
     * @param allIps  本次解析出的全部 IP（按 DNS 原始顺序）
     * @param healthy 其中探活通过的 IP（调用方需已按耗时升序排序）
     */
    public void update(String host, List<InetAddress> allIps, List<InetAddress> healthy) {
        if (host == null || host.isBlank()) {
            return;
        }
        String normalized = host.trim().toLowerCase();
        List<InetAddress> allSnapshot = allIps == null ? List.of() : List.copyOf(allIps);
        List<InetAddress> healthySnapshot = healthy == null ? List.of() : List.copyOf(healthy);

        allByHost.put(normalized, allSnapshot);
        if (healthySnapshot.isEmpty()) {
            // ⚠️ 一个健康的都没有：主动移除旧记录，让 resolver 回退到默认解析。
            //   不能保留上一次的旧健康列表 —— 否则坏 IP 会被长期当成可用（正是要修的问题）。
            healthyByHost.remove(normalized);
        } else {
            healthyByHost.put(normalized, healthySnapshot);
        }
        probedAtMs.put(normalized, System.currentTimeMillis());
        probeRounds.incrementAndGet();

        log.info("[LLM-IP] host={} 探测完成：健康 {}/{}，首选={}，全部={}",
                normalized, healthySnapshot.size(), allSnapshot.size(),
                healthySnapshot.isEmpty() ? "(无，回退默认解析)" : healthySnapshot.get(0).getHostAddress(),
                allSnapshot.stream().map(InetAddress::getHostAddress).toList());
    }

    // ==================== 读取（供 resolver 使用） ====================

    /**
     * 取出首选健康 IP。
     *
     * @return 健康 IP（探活最快者）；<b>无健康记录时返回 {@code null}</b>，
     *         表示调用方应回退到默认 DNS 解析（这是「首次启动尚未探活」与「全部 IP 都不健康」的统一语义）
     */
    public InetAddress pickHealthy(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        List<InetAddress> list = healthyByHost.get(host.trim().toLowerCase());
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(0);
    }

    /** 该 host 的全部健康 IP（按探活耗时升序）；无记录返回空列表 */
    public List<InetAddress> healthyOf(String host) {
        if (host == null || host.isBlank()) {
            return List.of();
        }
        List<InetAddress> list = healthyByHost.get(host.trim().toLowerCase());
        return list == null ? List.of() : list;
    }

    /** 该 host 最近一次解析出的全部 IP；无记录返回空列表 */
    public List<InetAddress> allOf(String host) {
        if (host == null || host.isBlank()) {
            return List.of();
        }
        List<InetAddress> list = allByHost.get(host.trim().toLowerCase());
        return list == null ? List.of() : list;
    }

    /** 该 host 是否已有探测结果（用于区分「未探活」与「探活后全不健康」） */
    public boolean hasProbeResult(String host) {
        return host != null && probedAtMs.containsKey(host.trim().toLowerCase());
    }

    /** 累计完成的探活轮次（观测用） */
    public long probeRounds() {
        return probeRounds.get();
    }

    /** 诊断快照：host → "健康 x/总 y，首选 z" */
    public Map<String, String> snapshot() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String host : trackedHosts()) {
            List<InetAddress> healthy = healthyOf(host);
            List<InetAddress> all = allOf(host);
            InetAddress first = healthy.isEmpty() ? null : healthy.get(0);
            result.put(host, "健康 " + healthy.size() + "/" + all.size()
                    + (first == null ? "，无首选（回退默认）" : "，首选=" + first.getHostAddress()));
        }
        return result;
    }

    /** 清空全部记录（配置热更新或排查用） */
    public void clear() {
        allByHost.clear();
        healthyByHost.clear();
        probedAtMs.clear();
        log.info("[LLM-IP] 健康注册表已清空（保留 host 跟踪与端口登记）");
    }
}
