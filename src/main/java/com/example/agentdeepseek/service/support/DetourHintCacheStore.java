package com.example.agentdeepseek.service.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 弯路预警文本的「跨进程持久化」会话级缓存（2026-11-17 新增）。
 *
 * <p><b>解决什么问题</b>：弯路预警文本被注入到<b>第一条 system 消息</b>里（内容固定 + 位置固定
 * 才能保住前缀缓存）。原先这份文本只存在进程内 {@code Map} 中，进程重启后缓存清空 →
 * 下一轮重新检索 → 检索结果依赖「本轮用户消息关键词」，与上次的字节不同 →
 * <b>system 第一个 token 就变了 → 整段上下文全部 miss</b>。</p>
 *
 * <p>实测代价（重启后第一次请求）：{@code iteration=0} 的
 * {@code 输入=154490(命中0/未命中154490)}、估算费用 {@code ¥0.155}，
 * 而同轮内 {@code iteration>=1} 命中 99.7%、单轮费用仅 {@code ¥0.007} —— 相差 21 倍。</p>
 *
 * <p><b>本类的做法</b>：把「会话 id → 预警文本 + 落盘时间」写到
 * {@code ${app.userdata}/runtime/detour-hint-cache.json}（开发态回退到 {@code ${user.dir}/runtime/}），
 * 启动时加载回内存。这样重启后仍能复用<b>同一份字节</b>，不再产生首次 miss。</p>
 *
 * <p><b>为什么带 TTL</b>：弯路预警来自经验库检索，经验库会随使用不断更新。若永久缓存，
 * 预警内容会一直停在首次检索那一刻，越来越过时。取 24 小时：既消除「重启即 miss」，
 * 又保证最长一天后能跟上最新经验（届时那一次 miss 属于内容升级的正常代价）。</p>
 *
 * <p><b>失败策略</b>：所有磁盘操作一律 try-catch 吞掉异常 —— 缓存只是性能优化，
 * 读写失败最多退回「内存缓存」的老行为，绝不影响聊天主流程。</p>
 */
@Component
public class DetourHintCacheStore {

    private static final Logger log = LoggerFactory.getLogger(DetourHintCacheStore.class);

    /** 缓存有效期：24 小时（到期后重新检索，让预警跟上经验库更新） */
    private static final long TTL_MS = 24L * 60 * 60 * 1000;

    /** 容量上限：与原先内存 Map 的清理阈值保持一致，防膨胀 */
    private static final int MAX_ENTRIES = 1000;

    /** 运行期数据目录名（与 Electron 侧约定一致：{@code <userData>/runtime/}） */
    private static final String RUNTIME_DIR = "runtime";

    /** 缓存文件名 */
    private static final String FILE_NAME = "detour-hint-cache.json";

    /** 落盘的一条记录 */
    private record Entry(String text, long savedAtMs) {
    }

    /** 落盘用的 DTO（Jackson 需要可访问的字段/getter，用 Map 序列化以保持简单） */
    private final Map<Long, Entry> cache = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 解析出的缓存文件路径（{@code null} 表示没有可用路径，退化为纯内存缓存） */
    private volatile Path cacheFile;

    /** 是否已从磁盘加载过（只加载一次） */
    private volatile boolean loaded = false;

    /** 脏标记：避免每次 put 都无脑写盘 */
    private volatile boolean dirty = false;

    @PostConstruct
    public void init() {
        this.cacheFile = resolveCacheFile();
        loadFromDisk();
    }

    /**
     * 取会话的预警文本。
     *
     * @param conversationId 会话 id
     * @return 缓存的文本；无记录或已过期返回 {@code null}（调用方据此重新检索）
     */
    public String get(Long conversationId) {
        if (conversationId == null) {
            return null;
        }
        ensureLoaded();
        Entry entry = cache.get(conversationId);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() - entry.savedAtMs() > TTL_MS) {
            cache.remove(conversationId);
            dirty = true;
            log.debug("弯路预警持久化缓存已过期，将会重新检索: conversationId={}", conversationId);
            return null;
        }
        return entry.text();
    }

    /**
     * 写入并落盘。
     *
     * <p>注意：只有「首次检索」（成功或确认无命中）时才应调用 —— 每轮都写会让磁盘 I/O
     * 跟着对话节奏抖动，而文本本身在一轮内是稳定的。</p>
     */
    public void put(Long conversationId, String text) {
        if (conversationId == null) {
            return;
        }
        ensureLoaded();
        if (text == null) {
            text = "";
        }
        if (cache.size() >= MAX_ENTRIES && !cache.containsKey(conversationId)) {
            evictOldest();
        }
        cache.put(conversationId, new Entry(text, System.currentTimeMillis()));
        dirty = true;
        saveToDisk();
    }

    /** 清掉某会话的缓存（会话删除 / 排查时手动失效用） */
    public void evict(Long conversationId) {
        if (conversationId == null) {
            return;
        }
        ensureLoaded();
        if (cache.remove(conversationId) != null) {
            dirty = true;
            saveToDisk();
        }
    }

    // ==================== 磁盘读写 ====================

    /**
     * 解析缓存文件路径。
     *
     * <p>优先级与 {@code BrowserBridgeClient} 的握手文件保持一致：</p>
     * <ol>
     *   <li>打包运行：{@code -Dapp.userdata=<userData>} + {@code /runtime/}；</li>
     *   <li>开发运行：{@code user.dir}（IDE 下是项目根）+ {@code /runtime/}；</li>
     *   <li>都没有则返回 {@code null}，退化为纯内存缓存（不影响功能）。</li>
     * </ol>
     */
    private Path resolveCacheFile() {
        String appUserData = System.getProperty("app.userdata");
        if (appUserData != null && !appUserData.isBlank()) {
            return Paths.get(appUserData, RUNTIME_DIR, FILE_NAME);
        }
        String userDir = System.getProperty("user.dir");
        if (userDir != null && !userDir.isBlank()) {
            return Paths.get(userDir, RUNTIME_DIR, FILE_NAME);
        }
        return null;
    }

    /** 首次访问时补一次加载（@PostConstruct 之外的安全网，测试/懒初始化场景也能用） */
    private void ensureLoaded() {
        if (!loaded) {
            synchronized (this) {
                if (!loaded) {
                    if (cacheFile == null) {
                        cacheFile = resolveCacheFile();
                    }
                    loadFromDisk();
                }
            }
        }
    }

    private void loadFromDisk() {
        loaded = true;
        Path file = cacheFile;
        if (file == null || !Files.exists(file)) {
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return;
            }
            Map<String, Map<String, Object>> raw = objectMapper.readValue(
                    json, new TypeReference<Map<String, Map<String, Object>>>() {
                    });
            long now = System.currentTimeMillis();
            int loadedCount = 0;
            int expiredCount = 0;
            for (Map.Entry<String, Map<String, Object>> e : raw.entrySet()) {
                Long conversationId = parseLong(e.getKey());
                Map<String, Object> value = e.getValue();
                if (conversationId == null || value == null) {
                    continue;
                }
                Object textObj = value.get("text");
                Object savedAtObj = value.get("savedAtMs");
                String text = textObj == null ? "" : String.valueOf(textObj);
                long savedAtMs = savedAtObj instanceof Number n ? n.longValue() : 0L;
                if (savedAtMs <= 0 || now - savedAtMs > TTL_MS) {
                    expiredCount++;
                    continue;
                }
                cache.put(conversationId, new Entry(text, savedAtMs));
                loadedCount++;
            }
            log.info("弯路预警持久化缓存已加载: file={}, 有效={}, 过期丢弃={}", file, loadedCount, expiredCount);
        } catch (Exception e) {
            // 文件损坏 / 格式变更都按「无缓存」处理，不阻塞启动
            log.warn("弯路预警持久化缓存加载失败（按无缓存处理）: file={}, err={}", file, e.getMessage());
        }
    }

    private void saveToDisk() {
        Path file = cacheFile;
        if (file == null || !dirty) {
            return;
        }
        synchronized (this) {
            try {
                Files.createDirectories(file.getParent());
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<Long, Entry> e : cache.entrySet()) {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("text", e.getValue().text());
                    value.put("savedAtMs", e.getValue().savedAtMs());
                    out.put(String.valueOf(e.getKey()), value);
                }
                String json = objectMapper.writeValueAsString(out);
                // 先写临时文件再原子替换：避免进程中途退出留下半截 JSON
                Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (Exception atomicErr) {
                    // 某些文件系统不支持 ATOMIC_MOVE，退化为普通替换
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
                dirty = false;
            } catch (Exception e) {
                log.warn("弯路预警持久化缓存写入失败（不影响主流程）: file={}, err={}", file, e.getMessage());
            }
        }
    }

    /** 淘汰最旧的一批（按 savedAtMs 升序取前 1/4），保留最近活跃会话 */
    private void evictOldest() {
        try {
            List<Map.Entry<Long, Entry>> sorted = new ArrayList<>(cache.entrySet());
            sorted.sort((a, b) -> Long.compare(a.getValue().savedAtMs(), b.getValue().savedAtMs()));
            int evictCount = Math.max(1, MAX_ENTRIES / 4);
            for (int i = 0; i < evictCount && i < sorted.size(); i++) {
                cache.remove(sorted.get(i).getKey());
            }
            dirty = true;
        } catch (Exception e) {
            log.debug("弯路预警缓存淘汰失败: {}", e.getMessage());
        }
    }

    private static Long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return null;
        }
    }
}
