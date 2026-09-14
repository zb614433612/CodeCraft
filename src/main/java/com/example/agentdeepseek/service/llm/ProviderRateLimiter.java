package com.example.agentdeepseek.service.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LLM 并发限流器（Phase 18 P4，按 DeepSeek 官方限流规则调整）
 * <p>
 * 依据 DeepSeek 官方限流规则：<b>仅按并发数限制，不按 QPS 限制</b>
 * （deepseek-v4-pro 500 并发 / deepseek-v4-flash 2500 并发，以账号粒度计，与 API Key 无关）。
 * 因此本实现只保留「全局并发信号量」这一个兜底维度，移除了原 QPS 令牌桶与 per-provider 分桶隔离：
 * </p>
 * <ul>
 *   <li><b>全局并发信号量</b>：所有 Provider 共享同一并发上限（默认 500，对应官方 pro 档），
 *       仅作极端情况兜底——日常多 Agent 并行远达不到该上限，几乎不会触发排队</li>
 *   <li><b>等待策略</b>：超限时在 {@code maxWaitMs} 内排队等待而非直接失败；
 *       超时返回 {@link RateLimitTimeoutException}（调用方不再重试，避免放大压力）</li>
 * </ul>
 * <p>
 * 接入点：{@link LLMWebClientManager#buildWebClient} 的 ExchangeFilter 链——
 * 所有经 LLMWebClientManager 创建的 WebClient 请求（含工具循环直连路径）统一经过此限流器。
 * </p>
 */
@Slf4j
@Component
public class ProviderRateLimiter {

    /** 是否启用限流（false 时全部放行，用于压测/排查） */
    @Value("${llm.rate-limit.enabled:true}")
    private boolean enabled;

    /** 全局最大并发在飞请求数（DeepSeek 官方：v4-pro 500 / v4-flash 2500，默认取 pro 档兜底） */
    @Value("${llm.rate-limit.max-concurrency:500}")
    private int maxConcurrency;

    /** 限流等待超时（毫秒），超时抛 RateLimitTimeoutException */
    @Value("${llm.rate-limit.max-wait-ms:5000}")
    private long maxWaitMs;

    /** 全局并发信号量（所有 Provider 共享，不按 providerCode 隔离） */
    private Semaphore semaphore;

    @PostConstruct
    void init() {
        semaphore = new Semaphore(Math.max(1, maxConcurrency), true);
        log.info("ProviderRateLimiter 初始化: 全局并发上限={}（DeepSeek 官方: v4-pro 500 / v4-flash 2500），maxWaitMs={}",
                maxConcurrency, maxWaitMs);
    }

    /**
     * 获取并发许可（非阻塞式 Reactor API）
     * <p>
     * 信号量获取在 boundedElastic 上执行，避免阻塞 Netty event loop。
     * 成功返回 {@link Lease}（调用方必须在 finally 中 {@link Lease#close()} 释放并发许可）；
     * 等待超时返回 {@link RateLimitTimeoutException}。
     * </p>
     *
     * @param providerCode 保留参数仅为兼容调用方；当前为全局并发控制，不再按 Provider 隔离
     */
    public Mono<Lease> acquireAsync(String providerCode) {
        if (!enabled) {
            return Mono.just(Lease.NOOP);
        }
        String code = providerCode != null ? providerCode : "default";
        return Mono.fromCallable(() -> {
                    boolean acquired = semaphore.tryAcquire(maxWaitMs, TimeUnit.MILLISECONDS);
                    if (!acquired) {
                        throw new RateLimitTimeoutException(code, maxWaitMs);
                    }
                    return new Lease(semaphore);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 当前全局在飞请求数（监控/日志用） */
    public int getInFlight() {
        return maxConcurrency - semaphore.availablePermits();
    }

    /**
     * 显式重配置（测试/热更新用）：重建全局信号量
     */
    public synchronized void configure(boolean enabled, int maxConcurrency, long maxWaitMs) {
        this.enabled = enabled;
        this.maxConcurrency = maxConcurrency;
        this.maxWaitMs = maxWaitMs;
        this.semaphore = new Semaphore(Math.max(1, maxConcurrency), true);
        log.info("ProviderRateLimiter 已重配置: enabled={}, maxConcurrency={}, maxWaitMs={}",
                enabled, maxConcurrency, maxWaitMs);
    }

    /** 限流等待超时异常（调用方不应重试——限流器内部已等待过） */
    public static class RateLimitTimeoutException extends RuntimeException {
        public RateLimitTimeoutException(String providerCode, long maxWaitMs) {
            super("LLM Provider [" + providerCode + "] 繁忙，等待并发许可超过 " + maxWaitMs + "ms");
        }
    }

    /** 并发许可（AutoCloseable：finally 中释放信号量） */
    public static class Lease implements AutoCloseable {
        static final Lease NOOP = new Lease(null);
        private final Semaphore semaphore;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        Lease(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        @Override
        public void close() {
            if (semaphore != null && closed.compareAndSet(false, true)) {
                semaphore.release();
            }
        }
    }
}
