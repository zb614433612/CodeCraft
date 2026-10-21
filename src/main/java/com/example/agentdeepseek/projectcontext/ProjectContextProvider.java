package com.example.agentdeepseek.projectcontext;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 项目知识库注入层：把 {@code .codecraft/} 的内容压成一段紧凑摘要，塞进请求的系统消息。
 *
 * <p><b>铁律一：只读不写。</b>本类没有任何 {@code Files.create*} 调用。
 * {@code .codecraft/} 是否存在完全由模型是否调用过 {@code bootstrap} 决定——
 * 「用户只是让我改某个目录下的一个文件」时，整个功能对仓库零影响。</p>
 *
 * <p><b>铁律二：只读一个文件。</b>数据源是 {@code .state.json}（内含每个模块的职责首行），
 * 不是 {@code INDEX.md}，也不是 N 个 {@code map/*.md}。每轮请求最多读一次文件，
 * 命中缓存时连内容都不读。</p>
 *
 * <p><b>铁律三：每轮现注入、不落库。</b>注入的 system 消息只存在于本次请求的 messages 列表里，
 * 下一轮从 DB 重建历史时它本来就不在，因此会自动重新注入。指纹标记的真实作用是
 * <b>防止同一轮内重复注入</b>（重试 / 多条补注路径被调用两次），而不是跨轮去重。</p>
 */
@Slf4j
@Component
public class ProjectContextProvider {

    /** 注入指纹（HTML 注释，前端不渲染但会进入模型上下文；压缩后消失即触发补注） */
    private static final String FINGERPRINT_MARK = "<!--pc v=1";

    /** 缓存条目上限（多项目切换时不至于堆积） */
    private static final int MAX_CACHE_ENTRIES = 50;

    private record CacheEntry(long stateMtime, String snippet) {
    }

    private final ProjectContextConfig config;
    private final ProjectMapService mapService;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public ProjectContextProvider(ProjectContextConfig config, ProjectMapService mapService) {
        this.config = config;
        this.mapService = mapService;
    }

    /**
     * 把项目知识库摘要注入到消息列表末尾（靠近响应位置，与语言指令同范式）。
     *
     * <p>历史里已存在指纹则直接返回，不重复占用 token。</p>
     *
     * @param messages 已构建好的历史消息列表（会被就地修改）
     * @param root     当前会话的工作目录
     */
    public void injectBootstrap(List<Map<String, Object>> messages, String root) {
        if (!config.isEnabled() || !config.isInjectBootstrap()) {
            return;
        }
        if (messages == null || messages.isEmpty() || root == null || root.isBlank()) {
            return;
        }
        if (alreadyInjected(messages)) {
            return;
        }
        String snippet = snippetCached(root);
        if (snippet == null || snippet.isEmpty()) {
            return;     // 知识库不存在 → 什么都不做（绝不创建）
        }
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", "system");
        msg.put("content", "【目录知识库】以下是本工作目录的索引（自动维护，可能与实际有偏差，冲突时以文件为准）：\n"
                + snippet
                + "\n查看某项详情：project_map action=read module=<模块名或文件名>；"
                + "补充职责/摘要：action=annotate；检查是否过期：action=status。\n"
                + FINGERPRINT_MARK + " -->");
        messages.add(msg);
        log.debug("项目知识库已注入: root={}, 摘要长度={}", root, snippet.length());
    }

    /**
     * 单文件上下文提示（P2 接入写类工具）：该文件属于哪个模块、这个模块负责什么。
     * 知识库不存在或无匹配模块时返回 null。
     */
    public String fileHint(String root, String relPath) {
        if (!config.isEnabled() || !config.isInjectFileHint() || root == null || relPath == null) {
            return null;
        }
        try {
            Path stateFile = Paths.get(root, ProjectMapService.DIR_NAME, ".state.json");
            if (!Files.isRegularFile(stateFile)) {
                return null;
            }
            String hint = mapService.moduleHintFor(Paths.get(root), relPath);
            if (hint == null || hint.isEmpty()) {
                return null;
            }
            int budget = config.getBudgetFileHint();
            return hint.length() <= budget ? hint : hint.substring(0, budget) + "…";
        } catch (RuntimeException e) {
            log.debug("生成文件上下文提示失败: {} - {}", relPath, e.getMessage());
            return null;
        }
    }

    /** 当前是否已有可用知识库（供工具判断是否提示「先建库」） */
    public boolean hasKnowledgeBase(String root) {
        if (root == null || root.isBlank()) {
            return false;
        }
        return Files.isRegularFile(Paths.get(root, ProjectMapService.DIR_NAME, "INDEX.md"));
    }

    /**
     * 技能规则片段：{@code rules.md} 里 key 以 {@code skill.all.} / {@code skill.<技能名>.} /
     * {@code skill.<技能ID>.} 开头的条目，随 {@code skill action=load} 一起返回。
     *
     * <p>为什么放在这里而不是塞进 system prompt：技能目录每轮都在，
     * 而具体某条技能规则只在真正加载该技能时才有意义；混在一起会把常驻预算吃光。</p>
     *
     * @return 形如「- `skill.all.x`：正文（证据：…）」的多行文本；无匹配时返回 null
     */
    public String skillRuleHint(String root, String skillName, long skillId) {
        if (!config.isEnabled() || !config.isInjectSkillRules()
                || root == null || root.isBlank()) {
            return null;
        }
        try {
            Path rulesFile = Paths.get(root, ProjectMapService.DIR_NAME, "rules.md");
            if (!Files.isRegularFile(rulesFile)) {
                return null;
            }
            return mapService.skillRules(Paths.get(root), skillName, skillId, config.getBudgetSkillRule());
        } catch (RuntimeException e) {
            log.debug("生成技能规则片段失败: skill={} - {}", skillName, e.getMessage());
            return null;
        }
    }

    // ==================== 内部实现 ====================

    private boolean alreadyInjected(List<Map<String, Object>> messages) {
        // 只扫最近 6 条：注入点固定在末尾，绝大多数情况一次就命中，避免每轮全量遍历长历史
        int from = Math.max(0, messages.size() - 6);
        for (int i = from; i < messages.size(); i++) {
            Object content = messages.get(i).get("content");
            if (content instanceof String text && text.contains(FINGERPRINT_MARK)) {
                return true;
            }
        }
        // 尾部没找到（压缩可能把注入块挪到了中部）→ 全量扫一次
        for (Map<String, Object> message : messages) {
            Object content = message.get("content");
            if (content instanceof String text && text.contains(FINGERPRINT_MARK)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 带缓存的摘要获取：以 {@code .state.json} 的 mtime 作为失效依据——
     * {@code annotate} 会同时改 map 文件与 state，所以 mtime 变化能准确反映「职责变了」。
     */
    private String snippetCached(String root) {
        Path stateFile = Paths.get(root, ProjectMapService.DIR_NAME, ".state.json");
        try {
            if (!Files.isRegularFile(stateFile)) {
                cache.remove(root);
                return null;
            }
            long mtime = Files.getLastModifiedTime(stateFile).toMillis();
            CacheEntry cached = cache.get(root);
            if (cached != null && cached.stateMtime() == mtime) {
                return cached.snippet();
            }
            String snippet = mapService.buildContextSnippet(Paths.get(root), config.getBudgetBootstrap());
            if (snippet == null) {
                return null;
            }
            if (cache.size() > MAX_CACHE_ENTRIES) {
                cache.clear();
            }
            cache.put(root, new CacheEntry(mtime, snippet));
            return snippet;
        } catch (IOException | RuntimeException e) {
            log.debug("读取项目知识库状态失败: root={} - {}", root, e.getMessage());
            return null;
        }
    }
}
