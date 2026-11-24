package com.example.agentdeepseek.service.llm;

import io.netty.resolver.AbstractAddressResolver;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * 健康优先的地址解析器组 —— 让 Netty 在建立连接时<b>优先使用探测过的健康 IP</b>。
 *
 * <h3>为什么需要自定义 resolver</h3>
 * <p>
 * Netty 默认的 {@code DefaultNameResolver} 实现是：
 * </p>
 * <pre>
 *   InetAddress address = InetAddress.getByName(hostname);
 *   // getByName() 只返回 DNS 结果中的【第一个】IP
 * </pre>
 * <p>
 * 而 DNS 对同一域名（如 {@code api.deepseek.com}）会返回多个 CDN IP，且<b>顺序轮换</b>
 * （实测两次解析顺序相反）。于是「DNS 恰好把丢 SYN 的坏 IP 排第一」时，
 * Netty 只能用那个坏 IP，卡满内核 SYN 重传窗口（1s→2s→4s→8s，实测 11073ms），
 * 且最终建连成功、不报任何错误 —— 这就是「首 token 偶发卡 10 秒」的完整链路。
 * </p>
 * <p>
 * 本类把 {@link LlmEndpointHealthRegistry} 中「探测通过的、且最快」的 IP 交给 Netty，
 * 从而跳过坏 IP。没有探测结果时<b>原样回退到默认解析</b>，行为与未启用时完全一致。
 * </p>
 *
 * <h3>安全性设计</h3>
 * <ul>
 *   <li><b>零阻塞</b>：只读内存中的不可变快照，不做任何网络调用（探测由定时任务负责）。</li>
 *   <li><b>失败即回退</b>：任何异常（DNS 失败、无记录）都退回 Netty 默认行为，不会让请求连不上。</li>
 *   <li><b>可关闭</b>：{@code enabled=false} 时完全绕过本类逻辑。</li>
 * </ul>
 */
@Slf4j
public class HealthyAddressResolverGroup extends AddressResolverGroup<InetSocketAddress> {

    private final LlmEndpointHealthRegistry registry;

    /** 是否启用健康优选（关闭时全部走默认解析） */
    private final boolean enabled;

    public HealthyAddressResolverGroup(LlmEndpointHealthRegistry registry, boolean enabled) {
        this.registry = registry;
        this.enabled = enabled;
    }

    @Override
    protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
        return new AbstractAddressResolver<InetSocketAddress>(executor) {

            @Override
            protected boolean doIsResolved(InetSocketAddress address) {
                // 已带具体 IP 的地址无需再解析
                return !address.isUnresolved();
            }

            /**
             * 单地址解析 —— 这是 Netty {@code Bootstrap.connect()} 实际走的路径。
             *
             * <p>优先返回 registry 中的首选健康 IP；无记录则回退默认解析（等价于 Netty 原生行为）。</p>
             */
            @Override
            protected void doResolve(InetSocketAddress unresolved, Promise<InetSocketAddress> promise) {
                try {
                    String host = unresolved.getHostString();
                    InetAddress healthy = enabled ? registry.pickHealthy(host) : null;
                    if (healthy != null) {
                        promise.setSuccess(new InetSocketAddress(healthy, unresolved.getPort()));
                        if (log.isDebugEnabled()) {
                            log.debug("[LLM-IP] 解析 {}:{} → 使用健康 IP {}（跳过 DNS 首选）",
                                    host, unresolved.getPort(), healthy.getHostAddress());
                        }
                        return;
                    }
                    // 回退：默认解析（只取第一个 IP，与 Netty 原生行为一致）
                    InetAddress fallback = InetAddress.getByName(host);
                    promise.setSuccess(new InetSocketAddress(fallback, unresolved.getPort()));
                } catch (Throwable e) {
                    // 交给 Netty 的错误处理链路（会带原异常向上抛出）
                    promise.setFailure(e);
                }
            }

            /**
             * 多地址解析 —— 部分调用路径（如 Happy Eyeballs 实现、{@code resolveAll}）会走这里。
             *
             * <p>有健康列表时按「探活耗时升序」返回（最快的排第一）；
             * 无记录时返回 DNS 原始全部地址。</p>
             */
            @Override
            protected void doResolveAll(InetSocketAddress unresolved, Promise<List<InetSocketAddress>> promise) {
                try {
                    String host = unresolved.getHostString();
                    List<InetAddress> healthy = enabled ? registry.healthyOf(host) : List.of();
                    List<InetSocketAddress> result = new ArrayList<>();
                    if (!healthy.isEmpty()) {
                        for (InetAddress ip : healthy) {
                            result.add(new InetSocketAddress(ip, unresolved.getPort()));
                        }
                        promise.setSuccess(result);
                        return;
                    }
                    // 回退：DNS 原生顺序
                    InetAddress[] all = InetAddress.getAllByName(host);
                    if (all != null) {
                        for (InetAddress ip : all) {
                            result.add(new InetSocketAddress(ip, unresolved.getPort()));
                        }
                    }
                    promise.setSuccess(result);
                } catch (Throwable e) {
                    promise.setFailure(e);
                }
            }
        };
    }
}
