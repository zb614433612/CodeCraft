package com.example.agentdeepseek.projectcontext;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单文件符号扫描器：从源码里抽「公开面」（类型名、公开方法、Props/Emits），供地图生成使用。
 *
 * <p><b>为什么不引 JavaParser / TS 编译器</b>：只为一句话级别的结构索引引入 AST 解析器，
 * 会让构建体积和启动开销都上一个台阶，而收益仅是「符号更精确」。这里用行级正则 + 长度上限
 * 换取零依赖，牺牲的是极端语法（多行声明、匿名类内部方法）的覆盖率，对「找代码在哪」这个目标足够。</p>
 *
 * <p><b>正则安全铁律</b>（血泪教训：曾因一条不限长的贪婪正则把 3 万字文本拖成 10 秒 CPU）：
 * 所有模式的重复量词都必须带 {@code {0,N}} 上限，且用 {@code ^} 锚定行首，绝不写跨行无限贪婪。
 * 单次扫描上限 {@link ProjectContextConfig#getMaxScanLinesPerFile()} 行。</p>
 */
@Slf4j
@Component
public class SymbolScanner {

    /** 一个符号（类型 / 方法 / 导出 / Prop） */
    public record Symbol(String name, String kind, int line) {
    }

    /** 单文件扫描结果 */
    public record FileSymbols(String relPath, String lang, String typeName, String description,
                              List<Symbol> symbols) {
        public boolean isEmpty() {
            return symbols == null || symbols.isEmpty();
        }
    }

    /** Java 类型声明：public class / interface / enum / record（行首锚定 + 修饰符个数上限） */
    private static final Pattern JAVA_TYPE = Pattern.compile(
            "^\\s{0,4}(?:(?:public|protected|private|static|final|abstract|sealed|non-sealed|strictfp)\\s+){0,6}"
                    + "(class|interface|enum|record)\\s+([A-Za-z_$][\\w$]{0,80})",
            Pattern.MULTILINE);

    /** Java 公开方法：返回类型 + 方法名，长度全面限死 */
    private static final Pattern JAVA_METHOD = Pattern.compile(
            "^\\s{0,8}(?:public|protected)\\s+"
                    + "(?:(?:static|final|synchronized|abstract|default|native|strictfp)\\s+){0,5}"
                    + "(?:<[\\w\\s,.<>\\[\\]?]{0,80}>\\s+)?"
                    + "[\\w$.<>\\[\\],?\\s]{0,120}?\\s+([a-zA-Z_$][\\w$]{0,80})\\s*\\(",
            Pattern.MULTILINE);

    /** TS/JS 导出声明 */
    private static final Pattern TS_EXPORT = Pattern.compile(
            "^\\s{0,4}export\\s+(?:default\\s+)?(?:declare\\s+)?(?:async\\s+)?"
                    + "(function|const|let|var|class|interface|type|enum)\\s+([A-Za-z_$][\\w$]{0,60})",
            Pattern.MULTILINE);

    /** Vue defineProps 类型块（块长度硬上限 6000，杜绝跨文件级回溯） */
    private static final Pattern VUE_DEFINE_PROPS = Pattern.compile(
            "defineProps\\s*<\\s*\\{([\\s\\S]{0,6000}?)\\}\\s*>");

    /** Props 字段行：name?: Type */
    private static final Pattern PROPS_FIELD = Pattern.compile(
            "^\\s{0,4}([a-zA-Z_$][\\w$]{0,60})\\??\\s*:");

    /** Vue defineEmits 类型块 */
    private static final Pattern VUE_DEFINE_EMITS = Pattern.compile(
            "defineEmits\\s*<\\s*\\{([\\s\\S]{0,4000}?)\\}\\s*>");

    /** 方法名过滤：getter/setter 与 Object 通用方法不构成「项目知识」 */
    private static final Pattern ACCESSOR = Pattern.compile("^(?:get|set|is)[A-Z][\\w$]{0,60}$");
    private static final List<String> OBJECT_METHODS = List.of(
            "toString", "equals", "hashCode", "canEqual", "compareTo", "clone", "finalize");

    /**
     * Markdown 一二级标题。这个功能不只服务代码开发——
     * 运维查「部署文档在哪一章」、产品查「需求文档讲了什么」，靠的都是标题结构。
     */
    private static final Pattern MD_HEADING = Pattern.compile(
            "^(#{1,2})[ \\t]+([^\\n]{1,80})", Pattern.MULTILINE);

    /** YAML 顶层键（缩进 0）：抽 docker-compose 的 services、application.yml 的配置块 */
    private static final Pattern YAML_TOP_KEY = Pattern.compile(
            "^([A-Za-z_][\\w.\\-]{0,60})[ \\t]*:", Pattern.MULTILINE);

    /** JSON 顶层键（缩进 ≤3）：抽 package.json 的 scripts/dependencies 等大块 */
    private static final Pattern JSON_TOP_KEY = Pattern.compile(
            "^( {0,3})\"([^\"\\n]{1,60})\"[ \\t]*:", Pattern.MULTILINE);

    /** properties / ini / toml / env 的键 */
    private static final Pattern CONF_KEY = Pattern.compile(
            "^([A-Za-z_][\\w.\\-]{0,60})[ \\t]*[=:]", Pattern.MULTILINE);

    /** SQL 建表 / 建视图（运维排查数据问题时最常找的东西） */
    private static final Pattern SQL_TABLE = Pattern.compile(
            "^\\s{0,4}CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:TEMP(?:ORARY)?\\s+)?(TABLE|VIEW)\\s+"
                    + "(?:IF\\s+NOT\\s+EXISTS\\s+)?([A-Za-z_][\\w.$]{0,60})",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    /** Shell / bat / ps1 函数定义 */
    private static final Pattern SH_FUNC = Pattern.compile(
            "^\\s{0,4}(?:function[ \\t]+)?([A-Za-z_][\\w\\-]{0,60})[ \\t]*\\([ \\t]*\\)[ \\t]*\\{",
            Pattern.MULTILINE);

    /**
     * 描述抽取时跳过的头部行前缀。
     * <p>除代码语句外，还要跳过 Markdown 的「门面行」：引用块常放语言切换链接与 badge，
     * 表格行、图片、分隔线同理——把它们当描述，不如继续往下找真正的标题。</p>
     */
    private static final List<String> SKIP_PREFIXES = List.of(
            "package ", "import ", "<template", "<script", "<style", "@", "#!", "use strict",
            ">", "|", "![", "[![", "---", "===", "```");

    private final ProjectContextConfig config;

    public SymbolScanner(ProjectContextConfig config) {
        this.config = config;
    }

    /**
     * 扫描单个文件。任何异常都吞掉并返回「空结果」，绝不让一个坏文件阻断整次建库。
     */
    public FileSymbols scan(Path root, String relPath) {
        Path file = root.resolve(relPath.replace('/', java.io.File.separatorChar));
        String lang = detectLang(relPath);
        try {
            if (!Files.isRegularFile(file)) {
                return new FileSymbols(relPath, lang, null, null, List.of());
            }
            if (Files.size(file) > config.getMaxFileSize()) {
                return new FileSymbols(relPath, lang, null, null, List.of());
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int limit = Math.min(lines.size(), Math.max(50, config.getMaxScanLinesPerFile()));
            // 必须先剥 BOM：Windows 下大量文档带 U+FEFF，而 String.trim() 不去它
            // （U+FEFF 不是 Character.isWhitespace），会让所有 startsWith 判断失效
            String head = stripBom(String.join("\n", lines.subList(0, limit)));
            String description = extractDescription(lines);

            List<Symbol> symbols = switch (lang) {
                case "java" -> scanJava(head, lines);
                case "vue" -> scanVue(head, relPath);
                case "ts", "js" -> scanTs(head);
                case "md" -> scanMarkdown(head);
                case "yaml", "conf" -> scanColonKeys(head);
                case "json" -> scanJsonKeys(head);
                case "sql" -> scanSql(head);
                case "sh" -> scanShell(head);
                default -> List.of();
            };
            String typeName = symbols.stream()
                    .filter(s -> "type".equals(s.kind()) || "component".equals(s.kind()))
                    .map(Symbol::name)
                    .findFirst()
                    .orElse(null);
            return new FileSymbols(relPath, lang, typeName, description, symbols);
        } catch (IOException | RuntimeException e) {
            log.debug("符号扫描失败（已跳过）: {} - {}", relPath, e.getMessage());
            return new FileSymbols(relPath, lang, null, null, List.of());
        }
    }

    /**
     * 由扩展名推断语言。
     *
     * <p>覆盖的不只是「源码」：文档（md）、配置（yaml/json/conf）、脚本（sh）、SQL 都有独立分支——
     * 因为这个知识库同时要服务运维、测试、写文档等非编码任务。</p>
     */
    public static String detectLang(String relPath) {
        String lower = relPath.toLowerCase();
        if (lower.endsWith(".java")) {
            return "java";
        }
        if (lower.endsWith(".vue")) {
            return "vue";
        }
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) {
            return "ts";
        }
        if (lower.endsWith(".js") || lower.endsWith(".mjs") || lower.endsWith(".cjs") || lower.endsWith(".jsx")) {
            return "js";
        }
        if (lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".mdx")) {
            return "md";
        }
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) {
            return "yaml";
        }
        if (lower.endsWith(".json") || lower.endsWith(".json5")) {
            return "json";
        }
        if (lower.endsWith(".sql")) {
            return "sql";
        }
        if (lower.endsWith(".sh") || lower.endsWith(".bash") || lower.endsWith(".zsh")
                || lower.endsWith(".bat") || lower.endsWith(".cmd") || lower.endsWith(".ps1")) {
            return "sh";
        }
        if (lower.endsWith(".properties") || lower.endsWith(".ini") || lower.endsWith(".toml")
                || lower.endsWith(".env") || lower.endsWith(".conf") || lower.endsWith(".cfg")) {
            return "conf";
        }
        if (lower.endsWith(".py")) {
            return "py";
        }
        if (lower.endsWith(".go")) {
            return "go";
        }
        return "other";
    }

    // ==================== 语言分支 ====================

    private List<Symbol> scanJava(String head, List<String> lines) {
        List<Symbol> symbols = new ArrayList<>();
        String primaryType = null;

        Matcher typeMatcher = JAVA_TYPE.matcher(head);
        while (typeMatcher.find() && symbols.size() < 20) {
            String kind = typeMatcher.group(1);
            String name = typeMatcher.group(2);
            if (primaryType == null) {
                primaryType = name;
            }
            symbols.add(new Symbol(name, "type", lineOf(head, typeMatcher.start()) + 1));
        }

        Matcher methodMatcher = JAVA_METHOD.matcher(head);
        while (methodMatcher.find() && symbols.size() < 60) {
            String name = methodMatcher.group(1);
            if (isNoiseMethod(name, primaryType)) {
                continue;
            }
            symbols.add(new Symbol(name, "method", lineOf(head, methodMatcher.start()) + 1));
        }
        return symbols;
    }

    private List<Symbol> scanTs(String head) {
        List<Symbol> symbols = new ArrayList<>();
        Matcher matcher = TS_EXPORT.matcher(head);
        while (matcher.find() && symbols.size() < 40) {
            String declKind = matcher.group(1);
            String name = matcher.group(2);
            String kind = switch (declKind) {
                case "interface", "type", "enum", "class" -> "type";
                default -> "export";
            };
            symbols.add(new Symbol(name, kind, lineOf(head, matcher.start()) + 1));
        }
        return symbols;
    }

    private List<Symbol> scanVue(String head, String relPath) {
        List<Symbol> symbols = new ArrayList<>();
        // 组件名：取文件名（去扩展名），信息量最高且零成本
        String fileName = relPath.substring(relPath.lastIndexOf('/') + 1);
        int dot = fileName.lastIndexOf('.');
        String componentName = dot > 0 ? fileName.substring(0, dot) : fileName;
        symbols.add(new Symbol(componentName, "component", 1));

        // Props：defineProps<{...}> 里的字段名
        Matcher propsMatcher = VUE_DEFINE_PROPS.matcher(head);
        if (propsMatcher.find()) {
            String block = propsMatcher.group(1);
            int baseLine = lineOf(head, propsMatcher.start()) + 1;
            for (String line : block.split("\n")) {
                if (symbols.size() >= 40) {
                    break;
                }
                Matcher fieldMatcher = PROPS_FIELD.matcher(line);
                if (fieldMatcher.find()) {
                    symbols.add(new Symbol(fieldMatcher.group(1), "prop", baseLine));
                }
                baseLine++;
            }
        }

        // Emits：defineEmits<{...}> 里的字段名
        Matcher emitsMatcher = VUE_DEFINE_EMITS.matcher(head);
        if (emitsMatcher.find()) {
            String block = emitsMatcher.group(1);
            for (String line : block.split("\n")) {
                if (symbols.size() >= 60) {
                    break;
                }
                Matcher fieldMatcher = PROPS_FIELD.matcher(line);
                if (fieldMatcher.find()) {
                    symbols.add(new Symbol(fieldMatcher.group(1), "emit", 0));
                }
            }
        }

        // script 里的 export（如 defineExpose 之外的工具函数）
        symbols.addAll(scanTs(head));
        return symbols;
    }

    // ==================== 非源码分支（运维 / 文档 / 配置 / 数据） ====================

    /**
     * Markdown 章节：只要一二级标题。
     * 三级及以下会太多（一份文档几十个），反而把「这份文档讲什么」的信号稀释掉。
     */
    private List<Symbol> scanMarkdown(String head) {
        List<Symbol> symbols = new ArrayList<>();
        Matcher matcher = MD_HEADING.matcher(head);
        while (matcher.find() && symbols.size() < 12) {
            String title = matcher.group(2).trim()
                    .replaceAll("[`*_]{1,3}", "")   // 去 markdown 装饰（字符类 + 限长，安全）
                    .trim();
            if (title.isEmpty() || title.length() > 40) {
                continue;
            }
            symbols.add(new Symbol(title, "section", lineOf(head, matcher.start()) + 1));
        }
        return symbols;
    }

    /** YAML / properties / ini / toml：顶层键 */
    private List<Symbol> scanColonKeys(String head) {
        List<Symbol> symbols = new ArrayList<>();
        Matcher matcher = YAML_TOP_KEY.matcher(head);
        while (matcher.find() && symbols.size() < 30) {
            String key = matcher.group(1);
            if (key.isEmpty() || key.length() > 60) {
                continue;
            }
            symbols.add(new Symbol(key, "key", lineOf(head, matcher.start()) + 1));
        }
        if (symbols.isEmpty()) {
            // 可能是 properties 风格（k=v）
            Matcher confMatcher = CONF_KEY.matcher(head);
            while (confMatcher.find() && symbols.size() < 30) {
                symbols.add(new Symbol(confMatcher.group(1), "key",
                        lineOf(head, confMatcher.start()) + 1));
            }
        }
        return symbols;
    }

    /** JSON：顶层键（缩进 ≤3） */
    private List<Symbol> scanJsonKeys(String head) {
        List<Symbol> symbols = new ArrayList<>();
        Matcher matcher = JSON_TOP_KEY.matcher(head);
        while (matcher.find() && symbols.size() < 25) {
            if (matcher.group(1).length() > 3) {
                continue;       // 深层嵌套的键没信息量
            }
            String key = matcher.group(2);
            if (key.isEmpty() || key.length() > 60) {
                continue;
            }
            symbols.add(new Symbol(key, "key", lineOf(head, matcher.start()) + 1));
        }
        return symbols;
    }

    /** SQL：建表 / 建视图 */
    private List<Symbol> scanSql(String head) {
        List<Symbol> symbols = new ArrayList<>();
        Matcher matcher = SQL_TABLE.matcher(head);
        while (matcher.find() && symbols.size() < 40) {
            symbols.add(new Symbol(matcher.group(2), "table", lineOf(head, matcher.start()) + 1));
        }
        return symbols;
    }

    /** Shell / bat / ps1：函数定义 */
    private List<Symbol> scanShell(String head) {
        List<Symbol> symbols = new ArrayList<>();
        Matcher matcher = SH_FUNC.matcher(head);
        while (matcher.find() && symbols.size() < 30) {
            symbols.add(new Symbol(matcher.group(1), "func", lineOf(head, matcher.start()) + 1));
        }
        return symbols;
    }

    // ==================== 描述抽取 ====================

    /**
     * 抽取文件头注释首行作为描述。只看前 40 行；遇到第一行代码就停。
     * 抽不到返回 null（地图里会标「无注释」），绝不调 LLM 编造。
     */
    private String extractDescription(List<String> lines) {
        int max = Math.min(lines.size(), 40);
        for (int i = 0; i < max; i++) {
            // 每行都要剥 BOM：有些文件只在首行带，有些是从别处拼接过留下零宽字符
            String trimmed = stripBom(lines.get(i)).trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (SKIP_PREFIXES.stream().anyMatch(trimmed::startsWith)) {
                continue;
            }
            if (isCommentStart(trimmed)) {
                String cleaned = cleanComment(trimmed);
                if (!cleaned.isEmpty()) {
                    return truncate(cleaned, 120);
                }
                // 注释块起始行只有 /** 或 <!--，往下找第一行有内容的
                for (int j = i + 1; j < Math.min(lines.size(), i + 14); j++) {
                    String next = stripBom(lines.get(j)).trim();
                    if (next.equals("*/") || next.equals("-->") || next.equals("\"\"\"")) {
                        break;
                    }
                    String c = cleanComment(next);
                    if (!c.isEmpty()) {
                        return truncate(c, 120);
                    }
                }
                continue;
            }
            // 碰到代码行，说明文件头没有注释
            break;
        }
        return null;
    }

    /**
     * 剥离 BOM 与零宽字符。
     * <p>这是实测踩到的坑：{@code ARCHITECTURE.md} 首行是 {@code ﻿﻿﻿> 🌐 English Version：…}，
     * 前面的 U+FEFF 让 {@code trim()} 之后的 {@code startsWith(">")} 依然为 false，
     * 结果整份文档的描述抽取直接 break 掉，INDEX 里「说明」列一片空白。</p>
     */
    private String stripBom(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        int i = 0;
        while (i < text.length() && (text.charAt(i) == '\uFEFF' || text.charAt(i) == '\u200B')) {
            i++;
        }
        return i == 0 ? text : text.substring(i);
    }

    private boolean isCommentStart(String trimmed) {
        return trimmed.startsWith("/**") || trimmed.startsWith("/*")
                || trimmed.startsWith("//") || trimmed.startsWith("<!--")
                || trimmed.startsWith("\"\"\"") || trimmed.startsWith("#");
    }

    private String cleanComment(String line) {
        String s = line;
        if (s.startsWith("/**")) {
            s = s.substring(3);
        } else if (s.startsWith("/*")) {
            s = s.substring(2);
        } else if (s.startsWith("<!--")) {
            s = s.substring(4);
        } else if (s.startsWith("\"\"\"")) {
            s = s.substring(3);
        } else if (s.startsWith("//")) {
            s = s.substring(2);
        } else if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (s.endsWith("*/")) {
            s = s.substring(0, s.length() - 2);
        } else if (s.endsWith("-->")) {
            s = s.substring(0, s.length() - 3);
        } else if (s.endsWith("\"\"\"")) {
            s = s.substring(0, s.length() - 3);
        }
        s = s.trim();
        while (s.startsWith("*")) {
            s = s.substring(1).trim();
        }
        // 纯分隔线不算描述
        if (s.isEmpty() || s.chars().allMatch(c -> c == '-' || c == '=' || c == '*' || c == '/')) {
            return "";
        }
        return s;
    }

    private boolean isNoiseMethod(String name, String primaryType) {
        if (primaryType != null && primaryType.equals(name)) {
            return true;    // 构造器
        }
        if (ACCESSOR.matcher(name).matches()) {
            return true;    // getter / setter / isXxx
        }
        return OBJECT_METHODS.contains(name);
    }

    private int lineOf(String head, int charIndex) {
        int line = 0;
        int max = Math.min(charIndex, head.length());
        for (int i = 0; i < max; i++) {
            if (head.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
