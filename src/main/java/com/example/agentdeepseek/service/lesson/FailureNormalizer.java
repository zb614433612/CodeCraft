package com.example.agentdeepseek.service.lesson;

import com.example.agentdeepseek.mapper.LessonRuleMapper;
import com.example.agentdeepseek.model.entity.LessonRule;
import com.example.agentdeepseek.util.ProjectRootContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 失败归一化器
 * 把原始工具失败压成「确定性字段」：错误码 + 错误类别 + 结构化参数，并生成同坑去重指纹。
 * 这是三级检索漏斗的入口：归一化质量直接决定检索命中率。
 */
@Slf4j
@Component
public class FailureNormalizer {

    private final ObjectMapper objectMapper;
    /** P0：错误码字典（规则通道第一优先级，类别+错误码联动带出，减少 UNKNOWN/OTHER 落空） */
    private final ErrorCodeDictionary dictionary;
    /** P0：规则自学习表（LLM 沉淀的报错文本特征，优先级最高；转正规则优先） */
    private final LessonRuleMapper ruleMapper;

    /** 指标：规则表命中次数（T6 看板：归一化落空率 / 自学习效果） */
    private final java.util.concurrent.atomic.AtomicLong ruleMatchCount = new java.util.concurrent.atomic.AtomicLong();

    /** L4 修复：规则表 TTL 内存缓存（60s 刷新，避免每次 normalize 全表 selectAll 的 N+1 式 DB 负载） */
    private volatile List<LessonRule> ruleCache;
    private volatile long ruleCacheTime;
    private static final long RULE_CACHE_TTL_MS = 60_000L;

    /**
     * 加载规则表（带 TTL 缓存；规则插入/转正/淘汰后最多 60s 生效，可接受）。
     * L5 修复：双检锁 synchronized——并发下多线程同时观察到缓存过期时只让一个线程执行 selectAll，
     * 避免重复全表查询与刷新窗口期读到不一致引用。
     */
    private List<LessonRule> loadRules() {
        long now = System.currentTimeMillis();
        List<LessonRule> cache = ruleCache;
        if (cache == null || now - ruleCacheTime > RULE_CACHE_TTL_MS) {
            synchronized (this) {
                cache = ruleCache;
                if (cache == null || System.currentTimeMillis() - ruleCacheTime > RULE_CACHE_TTL_MS) {
                    cache = ruleMapper.selectAll();
                    ruleCache = cache;
                    ruleCacheTime = System.currentTimeMillis();
                }
            }
        }
        return cache;
    }

    public FailureNormalizer(ObjectMapper objectMapper, ErrorCodeDictionary dictionary,
                             LessonRuleMapper ruleMapper) {
        this.objectMapper = objectMapper;
        this.dictionary = dictionary;
        this.ruleMapper = ruleMapper;
    }

    /** 规则表命中次数（指标访问） */
    public long getRuleMatchCount() {
        return ruleMatchCount.get();
    }

    /** 归一化结果 */
    public static class NormalizedFailure {
        public String toolName;
        public String errorCategory;
        public String errorCode;
        public String symptom;
        public String paramsJson;

        @Override
        public String toString() {
            return "NormalizedFailure{tool=" + toolName + ", category=" + errorCategory
                    + ", code=" + errorCode + ", params=" + paramsJson + "}";
        }
    }

    // ============================================================
    // 错误类别正则库（按优先级匹配）
    // ============================================================

    private record CategoryPattern(String category, Pattern pattern) {}

    private static final List<CategoryPattern> CATEGORY_PATTERNS = List.of(
            // 依赖问题（最高优先：NoClassDefFoundError 常伴随编译失败文本）
            cat("DEPENDENCY", "NoClassDefFoundError|ClassNotFoundException|Could not (resolve|find) (dependency|artifact|class)|Cannot resolve symbol|Failed to (execute|resolve) goal|dependency:|缺少依赖|缺依赖"),
            // 编译/构建
            cat("COMPILE", "compilation failed|编译失败|BUILD FAILURE|cannot find symbol|incompatible types|error:|语法错误|编译错误"),
            // 网络
            cat("NETWORK", "Connection refused|ECONNREFUSED|connect timed out|SocketTimeout|UnknownHost|Network is unreachable|网络连接失败|无法连接|timeout"),
            // 认证/权限
            cat("AUTH", "401|403|Unauthorized|permission denied|Access denied|认证失败|无权限|not authorized"),
            // MCP 握手/协议（收窄：仅当 MCP/initialize/handshake 伴随失败语义时才归类，避免 mcp 字样误判）
            cat("MCP_HANDSHAKE", "MCP.*(fail|error|exception|reject)|initialize.*(fail|error|exception|reject)|handshake.*(fail|error|exception|reject)|protocol version|MCP 握手失败|MCP 协议|MCP initialize"),
            // SQL/数据库
            cat("SQL", "SQLException|syntax error|jdbc|JDBC|数据库|sql"),
            // 参数/解析
            cat("PARAM", "参数解析失败|arguments|parse error|JSON parse|Jackson|Deserialization|参数缺失"),
            // 环境/文件
            cat("ENV", "No such file|FileNotFound|command not found|不是内部或外部命令|环境变量|No such directory|does not exist")
    );

    /** 错误码提取正则（按优先级） */
    private static final List<Pattern> ERROR_CODE_PATTERNS = List.of(
            Pattern.compile("([A-Za-z_][A-Za-z0-9_]*Exception)"),          // Java 异常类
            Pattern.compile("([A-Za-z_][A-Za-z0-9_]*Error)"),              // Java Error 类
            Pattern.compile("\\b(ECONNREFUSED|ETIMEDOUT|EACCES|ENOENT|EHOSTUNREACH|EPIPE)\\b"), // errno
            Pattern.compile("\\b(4\\d\\d|5\\d\\d)\\b"),                    // HTTP 状态码
            Pattern.compile("(BUILD FAILURE|command not found|syntax error)") // 常见文本
    );

    /** 从参数中忽略的元字段（不参与 params_json 与指纹） */
    private static final List<String> IGNORED_PARAM_FIELDS = List.of(
            "action", "force", "skip_lsp", "format", "cwd", "timeout", "wait_for", "tail",
            "start_line", "end_line", "page", "page_size", "max_results", "context_lines",
            "depth", "show_file_count", "regex", "ignore_case", "exclude_pattern", "root",
            "include", "count", "limit", "offset", "staged", "graph", "max_count", "all"
    );

    // ============================================================
    // 归一化
    // ============================================================

    /**
     * 归一化失败信息：提取错误码、错误类别、现象摘要、结构化参数
     * <p>
     * P0 二级管线 · 规则通道（四级优先级，根因优先）：
     * ⓪ 规则表优先（LLM 自学习沉淀的文本特征，转正规则优先）
     * ① 错误码字典（类别+错误码联动带出）——先匹配 Caused-by 根因文本，再全文
     * ② 正则错误码——先匹配 Caused-by 根因文本（取根因而非表层异常），再全文
     * ③ 正则类别（全文匹配——类别信号可能出现在报错头部）
     */
    public NormalizedFailure normalize(String toolName, String argumentsJson, String errorMessage) {
        NormalizedFailure failure = new NormalizedFailure();
        failure.toolName = (toolName == null || toolName.isBlank()) ? "unknown" : toolName;
        String msg = errorMessage == null ? "" : errorMessage;

        // P0-⓪：规则表优先（LLM 沉淀特征，转正优先；匹配失败静默降级）
        // 命中计数在 matchRule 内部完成（ruleMatchCount + match_hit_count 使用度）
        LessonRule ruleHit = matchRule(msg);
        if (ruleHit != null) {
            failure.errorCategory = ruleHit.getErrorCategory();
            failure.errorCode = truncate(ruleHit.getErrorCode(), 128);
        } else {
            // Caused-by 根因文本（无 Caused by 时等于原文）
            String codeSource = extractRootCauseText(msg);
            boolean hasRootCause = !codeSource.equals(msg);

            // P0-①：根因文本优先（字典 → 正则）——根因比表层 BUILD FAILURE 等更具体
            ErrorCodeDictionary.DictEntry hit = dictionary.match(failure.toolName, codeSource);
            String code = hit != null ? hit.getCode() : matchErrorCode(codeSource);
            if (hit == null && code == null && hasRootCause) {
                // 根因文本无结果 → 退全文（字典 → 正则）
                hit = dictionary.match(failure.toolName, msg);
                code = hit != null ? hit.getCode() : matchErrorCode(msg);
            }
            if (hit != null) {
                failure.errorCategory = hit.getCategory();
                failure.errorCode = truncate(hit.getCode(), 128);
            } else {
                failure.errorCode = code == null ? "UNKNOWN" : truncate(code, 128);
                // P0-②：正则类别（全文匹配——类别信号可能出现在报错头部）
                failure.errorCategory = matchCategory(msg);
            }
        }

        // 3. 现象摘要
        failure.symptom = truncate(msg, 512);

        // 4. 结构化参数（提取关键标量字段，供二级参数过滤 + 指纹）
        failure.paramsJson = extractParams(argumentsJson);

        return failure;
    }

    /** 正则错误码提取（优先具体异常类，其次 errno/HTTP 码） */
    private String matchErrorCode(String text) {
        for (Pattern p : ERROR_CODE_PATTERNS) {
            Matcher m = p.matcher(text);
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }

    /** 正则类别匹配（按正则库顺序） */
    private String matchCategory(String text) {
        for (CategoryPattern cp : CATEGORY_PATTERNS) {
            if (cp.pattern().matcher(text).find()) {
                return cp.category();
            }
        }
        return "OTHER";
    }

    /**
     * 规则表匹配：报错文本（转小写）包含规则片段即命中；转正规则优先于候选。
     * 命中时同步计数 match_hit_count（使用度追踪，P2 淘汰依据——替代时间淘汰）。
     * 数据量小（自学习初期），全量加载内存 contains 匹配；异常静默降级（不影响主流程）。
     */
    private LessonRule matchRule(String errorText) {
        try {
            String lower = errorText.toLowerCase();
            // L4 修复：走 TTL 缓存加载（不再每次全表 selectAll）
            List<LessonRule> rules = loadRules();
            LessonRule best = null;
            for (LessonRule r : rules) {
                if (r.getTextPattern() == null || r.getTextPattern().isBlank()
                        || !lower.contains(r.getTextPattern())) {
                    continue;
                }
                // 转正规则优先；同为候选取先插入的
                if (best == null || (r.getStatus() == LessonRule.STATUS_ACTIVE
                        && best.getStatus() != LessonRule.STATUS_ACTIVE)) {
                    best = r;
                }
            }
            if (best != null) {
                ruleMatchCount.incrementAndGet();
                // P2 使用度计数（同步 UPDATE 单行，失败静默不影响主流程）
                try {
                    ruleMapper.incrementMatchHit(best.getId(), LocalDateTime.now());
                } catch (Exception hitErr) {
                    log.debug("规则命中计数失败（不影响主流程）: {}", hitErr.getMessage());
                }
            }
            return best;
        } catch (Exception e) {
            log.debug("规则表匹配失败（降级字典/正则）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 提取 Caused-by 根因文本：取最后一个 "Caused by:" 之后的内容。
     * 无 Caused by 时返回原文（与旧逻辑兼容）。
     */
    private String extractRootCauseText(String msg) {
        int idx = msg.lastIndexOf("Caused by:");
        return idx >= 0 ? msg.substring(idx + "Caused by:".length()) : msg;
    }

    /**
     * 从工具参数 JSON 中提取关键参数为 [{name, value}] 数组
     * 过滤掉 action/分页/开关等元字段，只保留影响结果的业务参数
     */
    private String extractParams(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank() || "null".equals(argumentsJson)) {
            return "[]";
        }
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            if (args == null || !args.isObject()) {
                return "[]";
            }
            List<ObjectNode> params = new ArrayList<>();
            args.fields().forEachRemaining(entry -> {
                String name = entry.getKey();
                JsonNode value = entry.getValue();
                if (IGNORED_PARAM_FIELDS.contains(name) || value == null || value.isNull()) {
                    return;
                }
                if (value.isValueNode()) {
                    ObjectNode p = objectMapper.createObjectNode();
                    p.put("name", name);
                    p.put("value", truncate(value.asText(), 200));
                    params.add(p);
                }
            });
            // 按 name 排序，保证指纹稳定
            params.sort(Comparator.comparing(p -> p.path("name").asText()));
            ArrayNode array = objectMapper.createArrayNode();
            params.forEach(array::add);
            return objectMapper.writeValueAsString(array);
        } catch (Exception e) {
            log.debug("提取参数失败: {}", e.getMessage());
            return "[]";
        }
    }

    // ============================================================
    // 指纹
    // ============================================================

    /**
     * 生成同坑去重指纹：md5(projectKey|toolName|category|errorCode|paramsHash)
     * 相同指纹 = 同一个坑（允许参数值差异时由 params 参与区分）
     */
    public String fingerprint(String projectKey, NormalizedFailure failure) {
        String paramsHash = md5(failure.paramsJson == null ? "[]" : failure.paramsJson).substring(0, 8);
        String raw = projectKey + "|" + failure.toolName + "|" + failure.errorCategory
                + "|" + failure.errorCode + "|" + paramsHash;
        return md5(raw);
    }

    /**
     * 生成跨项目同坑指纹：md5(toolName|category|errorCode|paramsHash)
     * 不含 projectKey —— 专供 F3 被动反馈的追踪比对使用：
     * 全局经验（project_key='__global__'）入库签名含 '__global__' 前缀，与当前项目失败
     * 重新计算的含项目签名必然不等 → 「再次失败判无效」对全局经验永不触发。
     * 无项目签名在项目内/全局两个维度一致，追踪比对统一用它即可两端命中。
     */
    public String fingerprintNoProject(NormalizedFailure failure) {
        String paramsHash = md5(failure.paramsJson == null ? "[]" : failure.paramsJson).substring(0, 8);
        String raw = failure.toolName + "|" + failure.errorCategory
                + "|" + failure.errorCode + "|" + paramsHash;
        return md5(raw);
    }

    /**
     * 从项目根目录提取 projectKey（取路径最后一段，如 CodeCraft）
     */
    public String extractProjectKey() {
        return extractProjectKey(ProjectRootContext.get());
    }

    /**
     * 从显式路径提取 projectKey（供 ThreadLocal 已清空的场景使用，如工具循环后段）
     */
    public String extractProjectKey(String rootPath) {
        if (rootPath == null || rootPath.isBlank()) {
            return "default";
        }
        try {
            Path path = Path.of(rootPath);
            String name = path.getFileName() == null ? null : path.getFileName().toString();
            return (name == null || name.isBlank()) ? "default" : name;
        } catch (Exception e) {
            return "default";
        }
    }

    // ============================================================
    // 工具方法
    // ============================================================

    private static CategoryPattern cat(String category, String regex) {
        return new CategoryPattern(category, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    private static String md5(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // 理论不可达：MD5 必然存在
            return Integer.toHexString(input.hashCode());
        }
    }
}
