package com.example.agentdeepseek.projectcontext;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 项目知识库服务：扫描 → 生成 {@code .codecraft/} → 查询 / 标注 / 记规则。
 *
 * <p><b>核心约定（务必保持）</b>：本类所有方法都只在<b>被显式调用</b>时写盘。
 * 不存在任何 {@code @PostConstruct}、定时任务或监听器 —— 目录由模型按需创建，
 * 「用户只是让我改某个目录下的一个文件」这类任务不应在仓库里留下任何痕迹。</p>
 *
 * <p><b>职责分层</b>：结构层（模块划分、符号、文件数）由代码扫描得出，稳定且零 token；
 * 语义层（模块一句话职责、项目规则）由模型通过 {@code annotate} / {@code rule} 写入，
 * 落在 {@code <!-- manual:begin -->} 保留区，重建时原样回填、不会被覆盖。</p>
 */
@Slf4j
@Component
public class ProjectMapService {

    /** 知识库根目录名 */
    public static final String DIR_NAME = ".codecraft";
    private static final String INDEX_FILE = "INDEX.md";
    private static final String MAP_SUBDIR = "map";
    private static final String RULES_FILE = "rules.md";
    private static final String STATE_FILE = ".state.json";

    private static final String MANUAL_BEGIN = "<!-- manual:begin -->";
    private static final String MANUAL_END = "<!-- manual:end -->";

    /** 单模块详情最多列出的文件数 */
    private static final int MAX_FILES_PER_MODULE = 60;
    /** 每个文件最多列出的符号数 */
    private static final int MAX_SYMBOLS_PER_FILE = 8;

    /**
     * 规则 key 前缀白名单。
     *
     * <p>分三组，覆盖「不只写代码」的使用场景：</p>
     * <ul>
     *   <li><b>开发</b>：build. / style. / arch. / avoid. / flow. / domain. / link. / risk.</li>
     *   <li><b>运维</b>：ops.（启动顺序、健康检查、灰度步骤、回滚动作）、deploy.（环境与发布）。</li>
     *   <li><b>需求与文档</b>：req.（需求口径与验收标准）、doc.（文档规范与归属）、know.（领域事实）。</li>
     *   <li><b>技能</b>：skill.（{@code skill.all.xxx} 全技能通用，{@code skill.<技能名或ID>.xxx}
     *       在加载该技能时随「使用说明」一起注入，见 {@link #skillRules}）。</li>
     * </ul>
     * <p>这些语义大多没有单一「证据行」，是从代码结构、文档或沟通中得出的，
     * 所以 evidence 是建议而非门槛。</p>
     */
    private static final List<String> RULE_PREFIXES = List.of(
            "build.", "style.", "arch.", "avoid.", "flow.", "domain.",
            "link.", "know.", "risk.", "test.", "deploy.",
            "ops.", "req.", "doc.", "skill.");

    /**
     * 入口与配置文件清单（生成 INDEX 的「入口与配置」段）。
     * <p>刻意覆盖四类而不是只列构建文件：<b>构建</b>（pom/package/gradle）、
     * <b>运维</b>（Docker/CI/部署脚本/编排）、<b>数据</b>（schema.sql）、
     * <b>协作</b>（README/CONTRIBUTING/CHANGELOG/接口契约）。
     * 只做存在性检查，不读内容，成本可忽略。</p>
     */
    private static final List<String> ENTRY_FILES = List.of(
            // 构建
            "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle",
            "package.json", "frontend/package.json", "frontend-v3/package.json", "electron/package.json",
            "requirements.txt", "pyproject.toml", "go.mod",
            // 运行配置
            "src/main/resources/application.yml", "src/main/resources/application.properties",
            "src/main/resources/application-local.yml", "application-local.yml.example",
            // 运维 / 部署
            "Dockerfile", "docker-compose.yml", "docker-compose.yaml", "docker-compose.prod.yml",
            "Makefile", "Jenkinsfile", ".gitlab-ci.yml", ".github/workflows/release.yml",
            "helm/Chart.yaml", "k8s/deployment.yaml", "ansible.cfg", "terraform/main.tf",
            "scripts/build.sh", "scripts/build.bat", "start.sh", "start.bat",
            // 数据
            "src/main/resources/schema.sql", "schema.sql",
            // 接口契约
            "openapi.yaml", "openapi.json", "swagger.json", "docs/openapi.yaml",
            // 协作与文档
            "README.md", "README_EN.md", "CONTRIBUTING.md", "CHANGELOG.md",
            "docs/README.md", "docs/ARCHITECTURE.md", "AGENTS.md", "CLAUDE.md");

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** state.json 结构（只存模块级聚合，不存全量文件路径，避免几十 KB 噪音） */
    /**
     * 持久化状态。
     *
     * @param docMode  true = 文档模式（代码文件占比 < 10%）。此时索引的主体是 {@code docFiles}
     *                 而不是 {@code modules}——工作目录可能压根没有代码，比如桌面上的「V2.0需求文档」
     * @param docFiles 文档模式下的文件清单（路径 + 标题 + 章节），是 LLM 检索「该读哪一篇」的依据
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StateFile(int version, String root, String gitHead, long generatedAt,
                            int fileCount, int moduleCount, boolean docMode,
                            List<ModuleState> modules, List<FileEntry> docFiles) {
    }

    /**
     * 文档模式下的单文件条目。
     *
     * @param path     相对路径
     * @param title    首行标题（Markdown 的 {@code #} 标题，或文件头注释首行）
     * @param sections 一二级章节名，用 {@code /} 连接（最多 5 个）
     * @param note     模型用 annotate 补的一句话摘要（「这份文档讲什么」）；有它时优先展示它
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FileEntry(String path, String title, String sections, String note) {
    }

    /**
     * 单个模块的持久化状态。
     *
     * @param note 模型用 annotate 写的职责首行；{@code null} 表示「只有自动统计、尚无人工语义」——
     *             此时注入摘要只显示模块名与文件数，不塞「90 个文件，主要为 Java 90」这种零信息量的话
     * @param lang 主要语言（Java / Vue / TS / Markdown …），用于把纯文档目录排在源码模块之后
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ModuleState(String key, String display, String slug, int files, long latestMtime,
                              String note, String lang) {
    }

    /** 扫描结果 */
    public record ScanResult(int files, List<ModuleResolver.ModuleInfo> modules, String gitHead,
                             Map<String, Integer> langCount, Map<String, Long> latestMtime,
                             List<SymbolScanner.FileSymbols> symbols,
                             boolean docMode, List<FileEntry> docFiles, int codeFiles) {
    }

    /** 状态检查结果 */
    public record StatusResult(boolean exists, String gitHead, String generatedAt, int fileCount,
                               int moduleCount, List<String> staleModules, int addedFiles) {
    }

    /** 通用写入结果 */
    public record WriteResult(boolean ok, String message) {
    }

    private final ProjectContextConfig config;
    private final ModuleResolver moduleResolver;
    private final SymbolScanner symbolScanner;
    private final ObjectMapper objectMapper;

    public ProjectMapService(ProjectContextConfig config, ModuleResolver moduleResolver,
                             SymbolScanner symbolScanner, ObjectMapper objectMapper) {
        this.config = config;
        this.moduleResolver = moduleResolver;
        this.symbolScanner = symbolScanner;
        this.objectMapper = objectMapper;
    }

    // ==================== 扫描 ====================

    /**
     * 全量扫描项目（只读，不写盘）。
     */
    public ScanResult scan(Path root) {
        return scan(root, true);
    }

    /**
     * @param withSymbols 是否抽取符号。{@code status} 检查传 false——只看文件数/mtime 足够，
     *                    省掉几百次文件读取，快 5~10 倍
     */
    public ScanResult scan(Path root, boolean withSymbols) {
        List<String> relPaths = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(root)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    // 与单文件过滤共用同一套判断：目录名命中（含带版本号的归档目录）就整棵剪掉
                    if (moduleResolver.shouldSkipDirName(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (relPaths.size() >= config.getMaxFiles()) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (!attrs.isRegularFile() || attrs.size() > config.getMaxFileSize()) {
                        return FileVisitResult.CONTINUE;
                    }
                    String rel = root.relativize(file).toString().replace('\\', '/');
                    if (!moduleResolver.shouldSkip(rel)) {
                        relPaths.add(rel);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("项目扫描中断: root={}, 已收集 {} 个文件, {}", root, relPaths.size(), e.getMessage());
        }

        List<ModuleResolver.ModuleInfo> modules = moduleResolver.resolve(relPaths);

        // 语言分布先算出来：文档模式判定要用它，而符号抽取预算又取决于文档模式
        Map<String, Integer> langCount = new LinkedHashMap<>();
        for (String rel : relPaths) {
            langCount.merge(SymbolScanner.detectLang(rel), 1, Integer::sum);
        }
        int codeFiles = 0;
        for (String codeLang : CODE_LANGS) {
            codeFiles += langCount.getOrDefault(codeLang, 0);
        }
        // 代码文件占比 < 10%（或压根没有代码）→ 文档模式。
        // 典型场景：桌面上的「V2.0需求文档」目录，几十篇 md，一行代码都没有。
        boolean docMode = codeFiles * config.getDocModeCodeRatio() < relPaths.size();

        // 模块最新 mtime + 符号
        Map<String, Long> latestMtime = new LinkedHashMap<>();
        List<SymbolScanner.FileSymbols> symbols = new ArrayList<>();
        // 代码模式：每个模块各 40 个文件（模块间不互相挤占，否则第一个大模块会把预算吃光，
        // 后面所有模块的「代表符号」全变成 -）；
        // 文档模式：全局 200 个文件（文档目录通常只有一两个模块，等价于"尽量多覆盖"）
        int docSymbolBudget = docMode ? Math.min(200, relPaths.size()) : 0;
        for (ModuleResolver.ModuleInfo module : modules) {
            long latest = 0L;
            for (String rel : module.files()) {
                try {
                    long mtime = Files.getLastModifiedTime(
                            root.resolve(rel.replace('/', java.io.File.separatorChar))).toMillis();
                    latest = Math.max(latest, mtime);
                } catch (IOException ignoredEx) {
                    // 文件已被删除，忽略
                }
            }
            latestMtime.put(module.key(), latest);
            // 只对「能抽出结构」的模块抽符号，减少开销
            if (withSymbols && hasIndexableFiles(module)) {
                int budget = docMode ? docSymbolBudget : 40;
                for (String rel : module.files()) {
                    if (budget <= 0) {
                        break;
                    }
                    if (INDEXABLE_LANGS.contains(SymbolScanner.detectLang(rel))) {
                        symbols.add(symbolScanner.scan(root, rel));
                        budget--;
                    }
                }
                if (docMode) {
                    docSymbolBudget = budget;
                }
            }
        }

        // 文档模式：把每个文件的「标题 + 章节」整理成清单，作为注入与检索的主体
        List<FileEntry> docFiles = docMode ? buildDocFiles(relPaths, symbols) : List.of();
        return new ScanResult(relPaths.size(), modules, readGitHead(root), langCount, latestMtime,
                symbols, docMode, docFiles, codeFiles);
    }

    /** 代码语言集合（用于文档模式判定） */
    private static final Set<String> CODE_LANGS = Set.of("java", "vue", "ts", "js", "py", "go");

    /** 文档清单最多保留的条目数（防超大型文档库把 state.json 撑大） */
    private static final int MAX_DOC_FILES = 300;

    /**
     * 生成文档清单：每个文件一行「路径 + 标题 + 章节」。
     * <p>这就是纯文档目录（如「V2.0需求文档」）的核心索引——
     * LLM 靠它判断「哪篇文档跟当前需求相关」，再决定要不要打开原文。</p>
     */
    private List<FileEntry> buildDocFiles(List<String> relPaths, List<SymbolScanner.FileSymbols> symbols) {
        Map<String, SymbolScanner.FileSymbols> symbolMap = new LinkedHashMap<>();
        for (SymbolScanner.FileSymbols fileSymbols : symbols) {
            symbolMap.put(fileSymbols.relPath(), fileSymbols);
        }
        List<FileEntry> entries = new ArrayList<>();
        for (String rel : relPaths) {
            if (entries.size() >= MAX_DOC_FILES) {
                break;
            }
            SymbolScanner.FileSymbols fileSymbols = symbolMap.get(rel);
            String title = fileSymbols == null ? null : fileSymbols.description();
            String sections = joinSections(fileSymbols);
            entries.add(new FileEntry(rel, title, sections, null));
        }
        entries.sort(Comparator.comparing(FileEntry::path));
        return entries;
    }

    /** 把一二级章节名拼成「A / B / C」，最多 5 个 */
    private String joinSections(SymbolScanner.FileSymbols fileSymbols) {
        if (fileSymbols == null || fileSymbols.isEmpty()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (SymbolScanner.Symbol symbol : fileSymbols.symbols()) {
            if ("section".equals(symbol.kind())) {
                names.add(symbol.name());
                if (names.size() >= 5) {
                    break;
                }
            }
        }
        return names.isEmpty() ? null : String.join(" / ", names);
    }

    /**
     * 可抽结构的语言。
     * <p><b>不只源码</b>：这个知识库同时服务运维、测试、写文档等任务，
     * 所以 Markdown（章节）、YAML/JSON/conf（配置块）、SQL（表）、Shell（函数）都算。</p>
     */
    private static final Set<String> INDEXABLE_LANGS = Set.of(
            "java", "vue", "ts", "js", "md", "yaml", "json", "sql", "sh", "conf", "py", "go");

    private boolean hasIndexableFiles(ModuleResolver.ModuleInfo module) {
        for (String rel : module.files()) {
            if (INDEXABLE_LANGS.contains(SymbolScanner.detectLang(rel))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 读 .git/HEAD（纯文件读取，零依赖，任何异常都静默返回 null）。
     * 不调 git 命令：工具执行期起子进程代价高且可能被安全软件拦。
     */
    private String readGitHead(Path root) {
        try {
            Path gitDir = root.resolve(".git");
            if (!Files.isDirectory(gitDir)) {
                return null;
            }
            Path headFile = gitDir.resolve("HEAD");
            if (!Files.isRegularFile(headFile)) {
                return null;
            }
            String content = Files.readString(headFile, StandardCharsets.UTF_8).trim();
            if (!content.startsWith("ref:")) {
                return content;      // detached HEAD
            }
            String ref = content.substring(4).trim();
            Path refFile = gitDir.resolve(ref.replace('/', java.io.File.separatorChar));
            if (Files.isRegularFile(refFile)) {
                return Files.readString(refFile, StandardCharsets.UTF_8).trim();
            }
            Path packed = gitDir.resolve("packed-refs");
            if (Files.isRegularFile(packed)) {
                for (String line : Files.readAllLines(packed, StandardCharsets.UTF_8)) {
                    if (line.startsWith("#") || line.isBlank()) {
                        continue;
                    }
                    String[] parts = line.trim().split("\\s+", 2);
                    if (parts.length == 2 && parts[1].equals(ref)) {
                        return parts[0];
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 建库 ====================

    /**
     * 建立 / 增量更新知识库。
     *
     * @param force true=完全重建（丢弃模型的 annotate 内容）；false=增量（保留 manual 区）
     */
    public WriteResult bootstrap(Path root, boolean force) {
        if (!Files.isDirectory(root)) {
            return new WriteResult(false, "项目根目录不存在: " + root);
        }
        StateFile oldState = force ? null : readState(root);
        ScanResult scan = scan(root);
        if (!force && scan.files() < config.getBootstrapMinFiles()) {
            return new WriteResult(false, "项目可扫描文件仅 " + scan.files()
                    + " 个（阈值 " + config.getBootstrapMinFiles() + "），体量太小，"
                    + "直接读文件比建库更快，已跳过创建。确需建库请加 force=true。");
        }
        if (scan.modules().isEmpty()) {
            return new WriteResult(false, "未扫描到可索引的文件（可能全被排除规则过滤），未创建知识库。");
        }

        Path dir = root.resolve(DIR_NAME);
        try {
            Files.createDirectories(dir.resolve(MAP_SUBDIR));
            // 增量只适用于「旧库是代码模式、本次扫描也是代码模式」：
            // 文档模式的主体是文件清单（没有模块概念），全量重写本来就便宜，不值得再造一条路径
            boolean incremental = !force && oldState != null && !oldState.docMode() && !scan.docMode()
                    && oldState.modules() != null && !oldState.modules().isEmpty();
            return incremental
                    ? writeIncremental(root, dir, scan, oldState)
                    : writeFull(root, dir, scan, force, oldState);
        } catch (IOException e) {
            log.error("创建知识库失败: root={}", root, e);
            return new WriteResult(false, "创建知识库失败: " + e.getMessage());
        }
    }

    /**
     * 全量重建：首次建库、{@code force=true}、或代码/文档模式切换时使用。
     *
     * @param oldState 旧状态（文档模式下用来回填模型标注过的摘要；force 时为 null）
     */
    private WriteResult writeFull(Path root, Path dir, ScanResult scan, boolean force, StateFile oldState)
            throws IOException {
        // 旧 manual 区由 extractManual 逐个模块读取回填，无需整份旧 state
        List<ModuleState> moduleStates = new ArrayList<>();
        int written = 0;
        for (ModuleResolver.ModuleInfo module : scan.modules()) {
            String slug = slugOf(module.displayName());
            Path mapFile = dir.resolve(MAP_SUBDIR).resolve(slug + ".md");
            String existing = force ? "" : extractManual(mapFile);
            // manual 区为空时用自动摘要兜底，保证 map 文件里永远有一句可看的话
            String effective = existing.isBlank() ? autoSummary(module) : existing;
            // 只有模型真正标注过的内容才进 note：自动摘要（「90 个文件，主要为 Java 90」）
            // 对模型判断「该不该读这个模块」毫无帮助，塞进每轮注入只是白烧 token
            String note = isAutoSummary(effective) ? null : firstLine(effective);
            atomicWrite(mapFile, renderModuleFile(module, scan, effective));
            moduleStates.add(new ModuleState(module.key(), module.displayName(), slug,
                    module.fileCount(), scan.latestMtime().getOrDefault(module.key(), 0L),
                    note, primaryLang(module)));
            written++;
        }

        List<FileEntry> docFiles = backfillDocNotes(scan, force, oldState);
        // INDEX 的形态分两种：文档模式给「文件清单」（一篇一行），代码模式给「模块表」。
        // 注意：上面这个循环在两种模式下都会写 map/<slug>.md —— 文档模式通常只有 1~2 个模块
        // （整棵目录会被收成一个），多出的详情文件不影响 read（docMode 下 read 只读 state 里的清单），
        // 但不要以为「文档模式不落 map 文件」。
        String index = scan.docMode()
                ? renderDocIndex(root, scan, docFiles)
                : renderIndex(root, scan, moduleStates);
        atomicWrite(dir.resolve(INDEX_FILE), index);
        writeStateFile(root, scan, moduleStates, docFiles);
        ensureRulesFile(dir);

        String head = scan.gitHead() == null ? "（非 Git 仓库）" : shortHead(scan.gitHead());
        if (scan.docMode()) {
            return new WriteResult(true, "目录索引已就绪（文档模式）：扫描 " + scan.files()
                    + " 个文件，其中有结构的 " + scan.docFiles().size() + " 个（按「文件 + 标题 + 章节」建索引），HEAD="
                    + head + "。后续用 project_map action=read 查看清单，action=annotate 给某篇文档补一句摘要。");
        }
        return new WriteResult(true, "知识库已就绪：扫描 " + scan.files() + " 个文件，"
                + moduleStates.size() + " 个模块，写入 " + written + " 个模块详情，HEAD=" + head
                + "。后续用 project_map action=read 查看，action=annotate 补一句话职责。");
    }

    /**
     * 增量刷新：只重写「文件数变化 / 有新改动 / 新增 / 详情文件丢失」的模块。
     *
     * <p>两条实打实的好处：</p>
     * <ol>
     *   <li><b>无变化时一个字节都不写。</b>模型经常在任务开始时先 <code>bootstrap</code> 确认一下，
     *       全量重写会让 <code>.codecraft/</code> 的每个文件 mtime 都变，进 Git 的项目就是一片假 diff。</li>
     *   <li><b>只动变化的模块。</b>改一个模块不该让另外 29 个模块的详情文件重新落盘，
     *       也避免未变化模块的 manual 区在「读—写」往返中被意外改写。</li>
     * </ol>
     */
    private WriteResult writeIncremental(Path root, Path dir, ScanResult scan, StateFile oldState)
            throws IOException {
        Set<String> staleKeys = staleModuleKeys(oldState, scan);
        Map<String, ModuleState> oldByKey = new LinkedHashMap<>();
        for (ModuleState moduleState : oldState.modules()) {
            oldByKey.put(moduleState.key(), moduleState);
        }

        List<ModuleState> moduleStates = new ArrayList<>();
        List<String> rebuilt = new ArrayList<>();
        int reused = 0;
        for (ModuleResolver.ModuleInfo module : scan.modules()) {
            ModuleState old = oldByKey.get(module.key());
            String slug = slugOf(module.displayName());
            Path mapFile = dir.resolve(MAP_SUBDIR).resolve(slug + ".md");
            boolean detailMissing = !Files.isRegularFile(mapFile);
            // slug 变了说明模块被重命名，旧详情文件不能再复用
            boolean slugChanged = old != null && !slug.equals(old.slug());
            if (old != null && !staleKeys.contains(module.key()) && !detailMissing && !slugChanged) {
                moduleStates.add(old);
                reused++;
                continue;
            }
            String existing = extractManual(mapFile);
            String effective = existing.isBlank() ? autoSummary(module) : existing;
            // manual 区为空但旧 state 里有 note（例如详情文件被手工删过）→ 保留已有语义
            String note = isAutoSummary(effective)
                    ? (old == null ? null : old.note())
                    : firstLine(effective);
            atomicWrite(mapFile, renderModuleFile(module, scan, effective));
            moduleStates.add(new ModuleState(module.key(), module.displayName(), slug,
                    module.fileCount(), scan.latestMtime().getOrDefault(module.key(), 0L),
                    note, primaryLang(module)));
            rebuilt.add(module.displayName());
        }

        Set<String> liveSlugs = new LinkedHashSet<>();
        for (ModuleState moduleState : moduleStates) {
            liveSlugs.add(moduleState.slug());
        }
        List<String> removed = removeOrphanDetails(dir, oldState, liveSlugs);

        boolean noChange = staleKeys.isEmpty() && rebuilt.isEmpty() && removed.isEmpty()
                && oldState.modules().size() == scan.modules().size();
        if (noChange) {
            return new WriteResult(true, "已是最新：扫描 " + scan.files() + " 个文件 / "
                    + moduleStates.size() + " 个模块，无任何变化，未改动任何文件。");
        }

        atomicWrite(dir.resolve(INDEX_FILE), renderIndex(root, scan, moduleStates));
        writeStateFile(root, scan, moduleStates, null);
        ensureRulesFile(dir);

        StringBuilder message = new StringBuilder("增量刷新完成：扫描 ").append(scan.files())
                .append(" 个文件 / ").append(moduleStates.size()).append(" 个模块")
                .append("，重建 ").append(rebuilt.size()).append(" 个")
                .append(rebuilt.isEmpty() ? "" : "（" + joinLimited(rebuilt, 8) + "）")
                .append("，复用 ").append(reused).append(" 个");
        if (!removed.isEmpty()) {
            message.append("，清理 ").append(removed.size()).append(" 个已消失模块");
        }
        message.append("，HEAD=")
                .append(scan.gitHead() == null ? "（非 Git 仓库）" : shortHead(scan.gitHead()));
        return new WriteResult(true, message.append('。').toString());
    }

    /** 文档模式：把上一轮模型标注的摘要回填进来（重建索引不该丢掉语义） */
    private List<FileEntry> backfillDocNotes(ScanResult scan, boolean force, StateFile oldState) {
        List<FileEntry> docFiles = scan.docFiles();
        if (!scan.docMode() || force || docFiles.isEmpty()
                || oldState == null || oldState.docFiles() == null) {
            return docFiles;
        }
        Map<String, String> oldNotes = new LinkedHashMap<>();
        for (FileEntry oldFile : oldState.docFiles()) {
            if (oldFile.note() != null && !oldFile.note().isBlank()) {
                oldNotes.put(oldFile.path(), oldFile.note());
            }
        }
        if (oldNotes.isEmpty()) {
            return docFiles;
        }
        List<FileEntry> merged = new ArrayList<>(docFiles.size());
        for (FileEntry file : docFiles) {
            String note = oldNotes.get(file.path());
            merged.add(note == null ? file
                    : new FileEntry(file.path(), file.title(), file.sections(), note));
        }
        return merged;
    }

    private void writeStateFile(Path root, ScanResult scan, List<ModuleState> moduleStates,
                                List<FileEntry> docFiles) throws IOException {
        atomicWrite(root.resolve(DIR_NAME).resolve(STATE_FILE),
                objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(new StateFile(1, root.toString(), scan.gitHead(),
                                System.currentTimeMillis(), scan.files(), moduleStates.size(),
                                scan.docMode(), moduleStates,
                                scan.docMode() ? docFiles : null)));
    }

    /** 规则文件只在不存在时建骨架，绝不覆盖已有内容 */
    private void ensureRulesFile(Path dir) throws IOException {
        Path rulesFile = dir.resolve(RULES_FILE);
        if (!Files.isRegularFile(rulesFile)) {
            atomicWrite(rulesFile, renderRulesTemplate());
        }
    }

    /**
     * 计算过期模块 key 集合（status 与增量刷新共用同一套判定，避免两处口径漂移）。
     *
     * <p>四种过期信号：模块整个消失 / 文件数变化 / 有文件比上次建库更新 / 新增模块。</p>
     */
    private Set<String> staleModuleKeys(StateFile state, ScanResult scan) {
        Set<String> stale = new LinkedHashSet<>();
        if (state == null || state.modules() == null) {
            return stale;
        }
        Map<String, Integer> currentFiles = new LinkedHashMap<>();
        for (ModuleResolver.ModuleInfo module : scan.modules()) {
            currentFiles.put(module.key(), module.fileCount());
        }
        Set<String> knownKeys = new LinkedHashSet<>();
        for (ModuleState moduleState : state.modules()) {
            knownKeys.add(moduleState.key());
            int now = currentFiles.getOrDefault(moduleState.key(), 0);
            long nowMtime = scan.latestMtime().getOrDefault(moduleState.key(), 0L);
            boolean countChanged = now != moduleState.files();
            boolean touched = nowMtime > state.generatedAt();
            if (now == 0 || countChanged || touched) {
                stale.add(moduleState.key());
            }
        }
        for (String key : currentFiles.keySet()) {
            if (!knownKeys.contains(key)) {
                stale.add(key);
            }
        }
        return stale;
    }

    /**
     * 删除「旧库里有、本次扫描已不存在」的模块详情文件。
     * <p>只删旧 state 记录过的 slug，且必须在当前存活 slug 集合之外——不遍历目录做通配删除。</p>
     */
    private List<String> removeOrphanDetails(Path dir, StateFile oldState, Set<String> liveSlugs) {
        List<String> removed = new ArrayList<>();
        if (oldState.modules() == null) {
            return removed;
        }
        for (ModuleState moduleState : oldState.modules()) {
            String slug = moduleState.slug();
            if (slug == null || slug.isBlank() || liveSlugs.contains(slug)) {
                continue;
            }
            Path mapFile = dir.resolve(MAP_SUBDIR).resolve(slug + ".md");
            try {
                if (Files.deleteIfExists(mapFile)) {
                    removed.add(moduleState.display());
                }
            } catch (IOException e) {
                log.debug("清理过期模块详情失败: {} - {}", mapFile, e.getMessage());
            }
        }
        return removed;
    }

    /** 列表截断展示（日志/提示文案用，避免一次列出 40 个模块名） */
    private String joinLimited(List<String> items, int max) {
        if (items.size() <= max) {
            return String.join("、", items);
        }
        return String.join("、", items.subList(0, max)) + " 等 " + items.size() + " 个";
    }

    // ==================== 状态 ====================

    /**
     * 检查知识库是否过期。会做一次全量扫描（几百毫秒量级），只应在模型主动询问时调用。
     */
    public StatusResult status(Path root) {
        StateFile state = readState(root);
        if (state == null) {
            return new StatusResult(false, null, null, 0, 0, List.of(), 0);
        }
        ScanResult scan = scan(root, false);
        // Git HEAD 变化本身不作为 stale 依据：切分支再切回来时文件没变，标 stale 只是噪音
        boolean headChanged = state.gitHead() != null && scan.gitHead() != null
                && !state.gitHead().equals(scan.gitHead());

        // 与增量刷新共用同一套过期判定（staleModuleKeys），避免「status 说过期、bootstrap 却说没变」这种自相矛盾
        Map<String, String> displayByKey = new LinkedHashMap<>();
        if (state.modules() != null) {
            for (ModuleState moduleState : state.modules()) {
                displayByKey.put(moduleState.key(), moduleState.display());
            }
        }
        List<String> stale = new ArrayList<>();
        for (String key : staleModuleKeys(state, scan)) {
            stale.add(displayByKey.getOrDefault(key, key));
        }
        int addedFiles = Math.max(0, scan.files() - state.fileCount());
        if (stale.isEmpty() && headChanged) {
            log.debug("Git HEAD 变化但文件无变化，不标记 stale: {} → {}", state.gitHead(), scan.gitHead());
        }
        return new StatusResult(true, state.gitHead(),
                TIME_FMT.format(Instant.ofEpochMilli(state.generatedAt())),
                state.fileCount(), state.moduleCount(), stale, addedFiles);
    }

    // ==================== 查询 ====================

    /** 注入摘要中单条职责的最大字符数（超长截断，把预算留给「有更多模块」这件事） */
    private static final int NOTE_IN_SNIPPET_LIMIT = 40;

    /** 纯文档类语言：这些模块在注入摘要里排在源码模块之后 */
    private static final Set<String> DOC_LANGS = Set.of("Markdown", "Other");

    /**
     * 生成注入用的紧凑摘要（≤ budget 字符）。
     *
     * <p>以 {@code state.json} 为唯一数据源：一个文件就能拿到全部模块的职责，
     * 不用每轮去读 N 个 map 文件。模块行优先带「职责首行」——这是整个知识库里语义密度最高的部分，
     * 也是「代码地图」区别于「目录树」的关键。</p>
     *
     * @return 摘要文本；知识库不存在或无模块时返回 null
     */
    public String buildContextSnippet(Path root, int budget) {
        StateFile state = readState(root);
        if (state == null) {
            return null;
        }
        if (state.docMode()) {
            return buildDocSnippet(state);
        }
        if (state.modules() == null || state.modules().isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("HEAD ").append(state.gitHead() == null ? "-" : shortHead(state.gitHead()))
                .append(" · 文件 ").append(state.fileCount())
                .append(" · 模块 ").append(state.moduleCount()).append('\n');
        int used = sb.length();
        int shown = 0;
        // 源码模块优先：纯 Markdown 的归档/临时目录（如 release-notes、.planning）排在后面，
        // 预算不足时首先被裁掉——模型真正需要的是「代码在哪」，不是「文档放在哪」
        List<ModuleState> ordered = new ArrayList<>(state.modules());
        // 只有当「源码类模块占多数」时才把纯文档后置。
        // 纯文档仓库 / 需求库 / 运维手册项目里 Markdown 就是主体，硬把文档排到最后反而是错的。
        long docCount = ordered.stream().filter(module -> DOC_LANGS.contains(module.lang())).count();
        boolean preferSource = docCount * 2 < ordered.size();
        ordered.sort((a, b) -> {
            // (root) 始终排最前：里面有 pom.xml / package.json 这类构建入口，
            // 被当成「文档类」排到末尾是错的
            boolean rootA = ModuleResolver.ROOT_KEY.equals(a.key());
            boolean rootB = ModuleResolver.ROOT_KEY.equals(b.key());
            if (rootA != rootB) {
                return rootA ? -1 : 1;
            }
            if (preferSource) {
                boolean docA = DOC_LANGS.contains(a.lang());
                boolean docB = DOC_LANGS.contains(b.lang());
                if (docA != docB) {
                    return docA ? 1 : -1;
                }
            }
            return b.files() - a.files();
        });
        int total = ordered.size();
        for (ModuleState moduleState : ordered) {
            StringBuilder line = new StringBuilder();
            line.append("- ").append(moduleState.display()).append('(').append(moduleState.files()).append(')');
            String note = moduleState.note();
            if (note != null && !note.isBlank()) {
                line.append(' ').append(truncatePlain(note, NOTE_IN_SNIPPET_LIMIT));
            }
            line.append('\n');
            // 预留 60 字符给「还有 N 个模块」的收尾与可能的尾部说明
            if (used + line.length() > budget - 60) {
                sb.append("- …还有 ").append(total - shown).append(" 个模块（用 project_map action=read 查看）\n");
                break;
            }
            sb.append(line);
            used += line.length();
            shown++;
        }
        String result = sb.toString().stripTrailing();
        return result.length() > budget ? result.substring(0, budget) + "…" : result;
    }

    /**
     * 文档模式的注入摘要：一行一个文件（路径 + 标题）。
     *
     * <p>这是「纯文档目录」场景的核心产物。LLM 看到
     * {@code - 用户管理.md — V2.0 用户管理需求} 就知道该不该打开这一篇，
     * 不需要先把 24 个文档全读一遍。</p>
     */
    private String buildDocSnippet(StateFile state) {
        List<FileEntry> files = state.docFiles();
        if (files == null || files.isEmpty()) {
            return null;
        }
        int budget = config.getBudgetDocList();
        StringBuilder sb = new StringBuilder();
        sb.append("文档目录 · ").append(state.fileCount()).append(" 个文件\n");
        int used = sb.length();
        int shown = 0;
        for (FileEntry file : files) {
            StringBuilder line = new StringBuilder();
            line.append("- ").append(file.path());
            // 优先展示模型标注的摘要，没有则退回首行标题
            String desc = describeDoc(file);
            if (desc != null) {
                line.append(" — ").append(truncatePlain(desc, 36));
            }
            line.append('\n');
            // 预留收尾行空间
            if (used + line.length() > budget - 60) {
                sb.append("- …还有 ").append(files.size() - shown).append(" 个文件（用 project_map action=read 查看）\n");
                break;
            }
            sb.append(line);
            used += line.length();
            shown++;
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 文档的一句话描述：优先用模型标注的 note，没有则退回首行标题。
     *
     * @return 无可用描述时返回 null
     */
    private String describeDoc(FileEntry file) {
        if (file.note() != null && !file.note().isBlank()) {
            return file.note();
        }
        if (file.title() != null && !file.title().isBlank()) {
            return file.title();
        }
        return null;
    }

    /**
     * 单文件上下文提示：所属模块 + 该模块职责首行。
     *
     * @return 提示文本；知识库不存在或无匹配模块时返回 null
     */
    public String moduleHintFor(Path root, String relPath) {
        StateFile state = readState(root);
        if (state == null || relPath == null) {
            return null;
        }
        String normalized = relPath.replace('\\', '/');
        if (state.docMode()) {
            if (state.docFiles() == null) {
                return null;
            }
            for (FileEntry file : state.docFiles()) {
                if (file.path().equals(normalized) || file.path().endsWith("/" + normalized)) {
                    String desc = describeDoc(file);
                    return desc == null ? "文档：" + file.path() : "文档：" + desc;
                }
            }
            return null;
        }
        if (state.modules() == null) {
            return null;
        }
        ModuleState best = null;
        for (ModuleState moduleState : state.modules()) {
            String key = moduleState.key();
            if (ModuleResolver.ROOT_KEY.equals(key)) {
                continue;
            }
            // 最长前缀命中 = 最具体的模块
            if (normalized.startsWith(key + "/") && (best == null || key.length() > best.key().length())) {
                best = moduleState;
            }
        }
        if (best == null) {
            return null;
        }
        String note = best.note();
        return (note == null || note.isBlank())
                ? "所属模块 " + best.display() + "（" + best.files() + " 个文件）"
                : "所属模块 " + best.display() + "：" + note;
    }

    private String truncatePlain(String text, int max) {
        if (text == null) {
            return "";
        }
        String single = text.replace("\n", " ").trim();
        return single.length() <= max ? single : single.substring(0, max) + "…";
    }

    /**
     * 读 INDEX.md（不传 module）或某模块详情（传 module，可用显示名或完整路径）。
     */
    public String read(Path root, String module) {
        Path dir = root.resolve(DIR_NAME);
        if (!Files.isDirectory(dir)) {
            return "本项目尚未建立知识库（.codecraft/ 不存在）。"
                    + "若任务需要先定位多处代码，可调用 project_map action=bootstrap 建立；"
                    + "单文件小改无需建库。";
        }
        if (module == null || module.isBlank()) {
            return truncate(readText(dir.resolve(INDEX_FILE)), config.getBudgetRead());
        }
        StateFile state = readState(root);
        if (state == null) {
            return "知识库状态文件缺失，建议执行 project_map action=bootstrap 重建。";
        }
        String target = module.trim();
        // 文档模式：module 传的是文件名（如「用户管理.md」），直接给该文档的元信息
        if (state.docMode() && state.docFiles() != null) {
            for (FileEntry file : state.docFiles()) {
                String fileName = file.path().substring(file.path().lastIndexOf('/') + 1);
                if (file.path().equals(target) || file.path().endsWith("/" + target)
                        || fileName.equalsIgnoreCase(target)) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("文件：`").append(file.path()).append("`\n");
                    String desc = describeDoc(file);
                    sb.append("标题/摘要：").append(desc == null ? "（未抽取到）" : desc).append('\n');
                    sb.append("章节：").append(file.sections() == null ? "（无）" : file.sections()).append('\n');
                    sb.append("\n需要正文请用 file_explorer（action=read, file_path=\"")
                            .append(file.path()).append("\"）。\n");
                    return sb.toString();
                }
            }
            return "未在清单中找到文档「" + target + "」。可先 action=read 不带 module 查看完整文件清单。";
        }
        for (ModuleState moduleState : state.modules()) {
            if (moduleState.display().equalsIgnoreCase(target) || moduleState.key().equals(target)
                    || moduleState.key().endsWith("/" + target)) {
                Path mapFile = dir.resolve(MAP_SUBDIR).resolve(moduleState.slug() + ".md");
                if (!Files.isRegularFile(mapFile)) {
                    return "模块 " + moduleState.display() + " 的详情文件缺失，建议 action=bootstrap 重建。";
                }
                return truncate(readText(mapFile), config.getBudgetRead());
            }
        }
        // 模糊：按显示名包含匹配
        List<String> candidates = new ArrayList<>();
        for (ModuleState moduleState : state.modules()) {
            if (moduleState.display().toLowerCase().contains(target.toLowerCase())
                    || moduleState.key().toLowerCase().contains(target.toLowerCase())) {
                candidates.add(moduleState.display() + "  (" + moduleState.key() + ")");
            }
        }
        if (!candidates.isEmpty()) {
            return "未精确命中模块「" + module + "」，相近的有：\n- " + String.join("\n- ", candidates)
                    + "\n请用其中的显示名重新调用。";
        }
        return "未找到模块「" + module + "」。可先 action=read 不带 module 查看模块清单。";
    }

    // ==================== 标注 ====================

    /**
     * 给模块写职责与联动约束（落 manual 保留区，重建时保留）。
     *
     * <p>支持多行：这类知识天然是「A 负责什么 + 改 A 要同步改 B + 与哪些模块耦合」的复合结构，
     * 压成一句会丢语义。INDEX 与注入摘要只取首行，完整内容留在 map 文件里。</p>
     */
    public WriteResult annotate(Path root, String module, String note) {
        if (module == null || module.isBlank() || note == null || note.isBlank()) {
            return new WriteResult(false, "module 与 note 均为必填");
        }
        StateFile state = readState(root);
        if (state == null) {
            return new WriteResult(false, "知识库不存在，请先 action=bootstrap");
        }
        String cleaned = note.replace(MANUAL_BEGIN, "").replace(MANUAL_END, "").trim();
        int maxNote = config.getMaxNoteLength();
        if (cleaned.length() > maxNote) {
            cleaned = cleaned.substring(0, maxNote);
        }
        // 文档模式：module 传的是文件名，摘要写进 state 的 docFiles[].note
        if (state.docMode()) {
            return annotateDoc(root, state, module.trim(), cleaned);
        }
        for (ModuleState moduleState : state.modules()) {
            if (moduleState.display().equalsIgnoreCase(module.trim())
                    || moduleState.key().equals(module.trim())
                    || moduleState.key().endsWith("/" + module.trim())) {
                Path mapFile = root.resolve(DIR_NAME).resolve(MAP_SUBDIR).resolve(moduleState.slug() + ".md");
                try {
                    String content = Files.isRegularFile(mapFile) ? readText(mapFile) : "";
                    atomicWrite(mapFile, replaceManual(content, cleaned));
                    // 同步首行到 state.json，注入摘要才能带上职责（否则要每轮读 N 个 map 文件）
                    updateStateNote(root, moduleState.key(), firstLine(cleaned));
                    String hint = cleaned.contains("\n") ? "（多行描述，INDEX 只展示首行）" : "";
                    return new WriteResult(true, "已更新模块「" + moduleState.display() + "」的职责描述" + hint + "。");
                } catch (IOException e) {
                    return new WriteResult(false, "写入失败: " + e.getMessage());
                }
            }
        }
        return new WriteResult(false, "未找到模块「" + module + "」，请先 action=read 查看模块清单。");
    }

    /**
     * 文档模式下给单篇文档补摘要：写进 state.json 的 {@code docFiles[].note}。
     * <p>文档目录没有「模块详情文件」可写，摘要就存在状态里，注入摘要时直接带出。</p>
     */
    private WriteResult annotateDoc(Path root, StateFile state, String target, String note) {
        if (state.docFiles() == null || state.docFiles().isEmpty()) {
            return new WriteResult(false, "文档清单为空，请先 action=bootstrap 重建。");
        }
        List<FileEntry> updated = new ArrayList<>(state.docFiles().size());
        boolean matched = false;
        String matchedName = null;
        for (FileEntry file : state.docFiles()) {
            String fileName = file.path().substring(file.path().lastIndexOf('/') + 1);
            boolean hit = !matched && (file.path().equals(target)
                    || file.path().endsWith("/" + target)
                    || fileName.equalsIgnoreCase(target));
            if (hit) {
                updated.add(new FileEntry(file.path(), file.title(), file.sections(), note));
                matched = true;
                matchedName = fileName;
            } else {
                updated.add(file);
            }
        }
        if (!matched) {
            return new WriteResult(false, "未在清单中找到文档「" + target + "」，请先 action=read 查看文件名。");
        }
        try {
            atomicWrite(root.resolve(DIR_NAME).resolve(STATE_FILE),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                            new StateFile(state.version(), state.root(), state.gitHead(), state.generatedAt(),
                                    state.fileCount(), state.moduleCount(), state.docMode(),
                                    state.modules(), updated)));
            return new WriteResult(true, "已更新文档「" + matchedName + "」的摘要（重建索引时保留）。");
        } catch (IOException e) {
            return new WriteResult(false, "写入失败: " + e.getMessage());
        }
    }

    /** 把职责首行同步进 state.json（注入摘要的唯一数据源） */
    private void updateStateNote(Path root, String moduleKey, String note) {
        StateFile state = readState(root);
        if (state == null || state.modules() == null) {
            return;
        }
        List<ModuleState> updated = new ArrayList<>(state.modules().size());
        for (ModuleState moduleState : state.modules()) {
            if (moduleState.key().equals(moduleKey)) {
                updated.add(new ModuleState(moduleState.key(), moduleState.display(), moduleState.slug(),
                        moduleState.files(), moduleState.latestMtime(), note, moduleState.lang()));
            } else {
                updated.add(moduleState);
            }
        }
        try {
            atomicWrite(root.resolve(DIR_NAME).resolve(STATE_FILE),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                            new StateFile(state.version(), state.root(), state.gitHead(), state.generatedAt(),
                                    state.fileCount(), state.moduleCount(), state.docMode(),
                                    updated, state.docFiles())));
        } catch (IOException e) {
            log.debug("同步模块职责到 state.json 失败: {}", e.getMessage());
        }
    }

    /** 取文本首个非空行（截断到 60 字符），用于 INDEX 与注入摘要 */
    private String firstLine(String text) {
        if (text == null) {
            return "";
        }
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return trimmed.length() > 60 ? trimmed.substring(0, 60) + "…" : trimmed;
            }
        }
        return "";
    }

    // ==================== 规则 ====================

    /**
     * 项目规则读写。每条规则必须带证据；key 必须命中前缀白名单。
     */
    public String rule(Path root, String op, String key, String text, String evidence) {
        Path dir = root.resolve(DIR_NAME);
        Path file = dir.resolve(RULES_FILE);
        String operation = (op == null || op.isBlank()) ? "list" : op.trim().toLowerCase();
        try {
            if (!Files.isRegularFile(file)) {
                if ("list".equals(operation)) {
                    return "暂无项目规则（.codecraft/rules.md 不存在）。建库后可逐步沉淀。";
                }
                return "知识库不存在，请先 action=bootstrap。";
            }
            String content = readText(file);
            switch (operation) {
                case "list":
                    return truncate(content, config.getBudgetRead());
                case "add": {
                    String validation = validateRule(key, text, evidence);
                    if (validation != null) {
                        return validation;
                    }
                    List<String> existingKeys = extractRuleKeys(content);
                    if (existingKeys.contains(key)) {
                        return "规则 key「" + key + "」已存在。如需修改请用 op=update，避免同 key 两条冲突。";
                    }
                    if (existingKeys.size() >= config.getMaxRules()) {
                        return "规则已达上限 " + config.getMaxRules() + " 条。请先删除低价值规则（op=remove）或合并同类项。";
                    }
                    String block = renderRuleBlock(key, text, evidence, 0);
                    atomicWrite(file, content.stripTrailing() + "\n\n" + block);
                    return "已记录规则「" + key + "」。当前共 " + (existingKeys.size() + 1) + " 条。";
                }
                case "update": {
                    if (key == null || key.isBlank()) {
                        return "key 必填（格式：前缀.标识，如 build.cmd、skill.all.before-edit）";
                    }
                    // 只改正文时不该丢掉旧证据，也不该把命中计数清零：
                    // 这两样都是历史积累，按「未填就清空」重写等于让模型的沉淀白费
                    boolean evidenceGiven = evidence != null && !evidence.isBlank();
                    String mergedEvidence = evidenceGiven
                            ? evidence
                            : extractRuleEvidence(content, key.trim());
                    String validation = validateRule(key, text, mergedEvidence);
                    if (validation != null) {
                        return validation;
                    }
                    int hits = extractRuleHits(content, key.trim());
                    String updated = replaceRuleBlock(content, key,
                            renderRuleBlock(key, text, mergedEvidence, hits));
                    if (updated == null) {
                        return "未找到规则「" + key + "」。如需新增请用 op=add。";
                    }
                    atomicWrite(file, updated);
                    // 只有「本次没填、沿用了旧值」才提示沿用；模型自己填了证据还回一句「沿用原证据」，
                    // 会让它以为自己的证据没生效，下次干脆不填 —— 那是纯负面暗示
                    boolean evidenceInherited = !evidenceGiven && mergedEvidence != null;
                    return "已更新规则「" + key + "」"
                            + (evidenceInherited ? "（沿用原证据）" : "")
                            + (hits > 0 ? "（保留命中数 " + hits + "）" : "") + "。";
                }
                case "remove": {
                    String removed = removeRuleBlock(content, key);
                    if (removed == null) {
                        return "未找到规则「" + key + "」。";
                    }
                    atomicWrite(file, removed);
                    return "已删除规则「" + key + "」。";
                }
                default:
                    return "不支持的 op：" + op + "（可用 add / update / remove / list）";
            }
        } catch (IOException e) {
            return "规则操作失败: " + e.getMessage();
        }
    }

    /**
     * 规则校验：只拦「结构性问题」，不拦「语义表达」。
     *
     * <p>设计取舍（来自实际使用反馈）：早期 edition 把 evidence 做成硬性必填、正文限 140 字符，
     * 结果联动约束类知识（「改 A 必须同步改 B」）根本写不进去——这类知识没有单一「证据行」，
     * 是从代码结构里读出来的。强行要求证据只会逼模型编造一条假的行号，那比没有证据更糟。
     * 所以现在的闸门是：key 格式、text 非空且不超长、evidence 若填则不能超长。</p>
     */
    private String validateRule(String key, String text, String evidence) {
        if (key == null || key.isBlank()) {
            return "key 必填（格式：前缀.标识，如 build.cmd、link.settings-density）";
        }
        String trimmedKey = key.trim();
        boolean prefixOk = RULE_PREFIXES.stream().anyMatch(trimmedKey::startsWith);
        if (!prefixOk) {
            return "key 前缀必须在白名单内：" + String.join(" / ", RULE_PREFIXES)
                    + "（收到：" + trimmedKey + "）";
        }
        if (text == null || text.isBlank()) {
            return "text 必填：说清这条规则或联动约束";
        }
        if (text.trim().length() > config.getMaxRuleLength()) {
            return "text 超过 " + config.getMaxRuleLength() + " 字符，请压缩（一条只讲一件事，多条拆成多个 key）。";
        }
        if (evidence != null && evidence.trim().length() > 300) {
            return "evidence 超过 300 字符，写最短的可核对片段即可（也可以不填）。";
        }
        return null;
    }

    private List<String> extractRuleKeys(String content) {
        List<String> keys = new ArrayList<>();
        Matcher matcher = RULE_KEY_PATTERN.matcher(content);
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }

    private static final Pattern RULE_KEY_PATTERN =
            Pattern.compile("^<!--\\s*rule:\\s*([^>\\n]{1,80}?)\\s*-->$", Pattern.MULTILINE);

    private static final Pattern RULE_EVIDENCE_PATTERN =
            Pattern.compile("^证据\\s*[:：]\\s*(.{1,300})$", Pattern.MULTILINE);

    private static final Pattern RULE_HITS_PATTERN =
            Pattern.compile("^命中\\s*[:：]\\s*(\\d{1,6})$", Pattern.MULTILINE);

    /** 取规则块正文（key 注释行之后到下一块之前）；找不到返回 null */
    private String findRuleBlock(String content, String key) {
        Matcher matcher = RULE_KEY_PATTERN.matcher(content);
        while (matcher.find()) {
            if (matcher.group(1).equals(key)) {
                return content.substring(matcher.end(), findBlockEnd(content, matcher.end()));
            }
        }
        return null;
    }

    /**
     * 取某条规则已有的证据行。
     * <p>{@code update} 只改正文时，调用方通常不会重复填 evidence；若按「空证据」重写，
     * 模型记下的「文件:行号」会被悄悄抹掉——纯损失，必须沿用。</p>
     */
    private String extractRuleEvidence(String content, String key) {
        String block = findRuleBlock(content, key);
        if (block == null) {
            return null;
        }
        Matcher matcher = RULE_EVIDENCE_PATTERN.matcher(block);
        if (matcher.find()) {
            String value = matcher.group(1).trim();
            return value.isEmpty() ? null : value;
        }
        return null;
    }

    /** 取某条规则已有的命中计数（update 时不清零，否则「越用越准」的反馈闭环白做） */
    private int extractRuleHits(String content, String key) {
        String block = findRuleBlock(content, key);
        if (block == null) {
            return 0;
        }
        Matcher matcher = RULE_HITS_PATTERN.matcher(block);
        if (matcher.find()) {
            try {
                return Math.max(0, Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    // ==================== 技能规则片段 ====================

    /**
     * 取出「与某个技能相关」的项目规则片段，供 {@code skill action=load} 追加到使用说明之后。
     *
     * <p>匹配三种 key：{@code skill.all.*}（所有技能通用）、{@code skill.<技能名>.*}、
     * {@code skill.<技能ID>.*}。为什么要单开这条通道：技能本身是跨项目复用的通用流程，
     * 而「在这个项目里执行这个技能还需要注意什么」是项目特有的——
     * 写进技能说明会被带到别的项目去，写在 {@code rules.md} 才是正确归属。</p>
     *
     * @return 拼接好的规则片段；无匹配或知识库不存在时返回 null
     */
    public String skillRules(Path root, String skillName, long skillId, int budget) {
        Path file = root.resolve(DIR_NAME).resolve(RULES_FILE);
        if (!Files.isRegularFile(file) || budget <= 0) {
            return null;
        }
        String content = readText(file);
        if (content.isEmpty()) {
            return null;
        }
        String slug = skillSlugOf(skillName);
        String rawName = skillName == null ? "" : skillName.trim().toLowerCase();
        StringBuilder sb = new StringBuilder();
        Matcher matcher = RULE_KEY_PATTERN.matcher(content);
        int scanned = 0;
        while (matcher.find() && scanned < 200) {
            scanned++;
            String key = matcher.group(1);
            if (!matchesSkillRule(key, slug, rawName, skillId)) {
                continue;
            }
            String body = renderRuleForHint(
                    content.substring(matcher.end(), findBlockEnd(content, matcher.end())), key);
            if (body == null) {
                continue;
            }
            // 预算内正常追加；第一条即使超预算也要给（截断），否则模型只能看到「已省略」，等于没注入
            if (sb.isEmpty() && body.length() > budget) {
                sb.append(body, 0, Math.max(0, budget - 8)).append("…\n");
                break;
            }
            if (sb.length() + body.length() > budget) {
                sb.append("- （其余相关规则因预算省略，可用 project_map action=rule op=list 查看）\n");
                break;
            }
            sb.append(body);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /**
     * 技能名 → 规则 key 片段：小写、空白转连字符、保留中文与字母数字。
     *
     * <p>刻意不复用 {@link #slugOf}：它把非 ASCII 一律替换成下划线，
     * 「发版提交流程」会变成一串下划线，规则 key 就没法读了。</p>
     */
    private static String skillSlugOf(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String s = name.trim().toLowerCase();
        s = s.replaceAll("[\\s_]+", "-");
        s = s.replaceAll("[^a-z0-9\\p{IsHan}-]", "");
        s = s.replaceAll("-{2,}", "-");
        while (s.startsWith("-")) {
            s = s.substring(1);
        }
        while (s.endsWith("-")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    private boolean matchesSkillRule(String key, String slug, String rawName, long skillId) {
        if (key == null || !key.startsWith("skill.")) {
            return false;
        }
        String rest = key.substring(6).toLowerCase();
        if (rest.equals("all") || rest.startsWith("all.")) {
            return true;
        }
        if (slug != null && !slug.isEmpty() && (rest.equals(slug) || rest.startsWith(slug + "."))) {
            return true;
        }
        if (!rawName.isEmpty() && (rest.equals(rawName) || rest.startsWith(rawName + "."))) {
            return true;
        }
        String id = String.valueOf(skillId);
        return rest.equals(id) || rest.startsWith(id + ".")
                || rest.equals("#" + id) || rest.startsWith("#" + id + ".")
                || rest.equals("id" + id) || rest.startsWith("id" + id + ".");
    }

    /** 规则块 → 一行提示（正文 + 证据），正文为空时返回 null */
    private String renderRuleForHint(String block, String key) {
        String text = null;
        String evidence = null;
        for (String line : block.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Matcher ev = RULE_EVIDENCE_PATTERN.matcher(trimmed);
            if (ev.matches()) {
                evidence = ev.group(1).trim();
                continue;
            }
            if (RULE_HITS_PATTERN.matcher(trimmed).matches()) {
                continue;
            }
            if (text == null) {
                text = trimmed;
            }
        }
        if (text == null || text.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("- `").append(key).append("`：").append(text);
        if (evidence != null && !evidence.isBlank()) {
            sb.append("（证据：").append(evidence).append("）");
        }
        return sb.append('\n').toString();
    }

    private String renderRuleBlock(String key, String text, String evidence, int hits) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!-- rule: ").append(key.trim()).append(" -->\n");
        sb.append(text.trim().replace("\n", " ")).append('\n');
        // 证据可选：有就写，没有就不占一行（避免出现「证据：」后面空着的假条目）
        if (evidence != null && !evidence.isBlank()) {
            sb.append("证据：").append(evidence.trim().replace("\n", " ")).append('\n');
        }
        sb.append("命中：").append(hits).append('\n');
        return sb.toString();
    }

    private String replaceRuleBlock(String content, String key, String newBlock) {
        Matcher matcher = RULE_KEY_PATTERN.matcher(content);
        if (!matcher.find()) {
            return null;
        }
        while (true) {
            if (matcher.group(1).equals(key)) {
                int start = matcher.start();
                int end = findBlockEnd(content, matcher.end());
                return content.substring(0, start) + newBlock.stripTrailing() + content.substring(end);
            }
            if (!matcher.find()) {
                return null;
            }
        }
    }

    private String removeRuleBlock(String content, String key) {
        Matcher matcher = RULE_KEY_PATTERN.matcher(content);
        while (matcher.find()) {
            if (matcher.group(1).equals(key)) {
                int start = matcher.start();
                int end = findBlockEnd(content, matcher.end());
                return (content.substring(0, start) + content.substring(end)).replaceAll("[ \\t]+\\n", "\n");
            }
        }
        return null;
    }

    /** 规则块到下一个 <!-- rule: 或文件尾结束 */
    private int findBlockEnd(String content, int from) {
        Matcher matcher = RULE_KEY_PATTERN.matcher(content);
        if (matcher.find(from)) {
            return matcher.start();
        }
        return content.length();
    }

    // ==================== 渲染 ====================

    /**
     * 文档模式的索引：文件清单。
     *
     * <p>工作目录可能一个代码文件都没有（例如桌面上的「V2.0需求文档」），
     * 这时「模块」概念没有意义——索引的主体就是每个文档，
     * 要回答的是「有哪些文档、每篇讲什么、讲了哪几节」。</p>
     */
    private String renderDocIndex(Path root, ScanResult scan, List<FileEntry> docFiles) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!-- 由 CodeCraft 自动生成，请勿手工编辑。模型可用 project_map action=annotate 给单篇文档补摘要 -->\n");
        sb.append("# 目录索引（文档模式）\n\n");
        sb.append("| 项 | 值 |\n|---|---|\n");
        sb.append("| 目录 | `").append(root.toString().replace('\\', '/')).append("` |\n");
        sb.append("| 扫描时间 | ").append(TIME_FMT.format(Instant.now())).append(" |\n");
        sb.append("| 文件数 | ").append(scan.files()).append(" |\n");
        sb.append("| 代码文件 | ").append(scan.codeFiles()).append(" 个（占比不足 10%，按文档清单索引） |\n");
        sb.append("| 语言分布 | ").append(formatLangCount(scan.langCount())).append(" |\n\n");

        sb.append("## 文件清单\n\n");
        sb.append("> 「摘要」列由模型用 annotate 标注，未标注前显示文档首行标题。\n\n");
        sb.append("| 文件 | 摘要 | 章节 |\n|---|---|---|\n");
        for (FileEntry file : docFiles) {
            sb.append("| `").append(file.path()).append("` | ")
                    .append(escapeCell(describeDoc(file) == null ? "" : describeDoc(file))).append(" | ")
                    .append(escapeCell(file.sections() == null ? "" : file.sections())).append(" |\n");
        }

        sb.append("\n## 用法\n\n");
        sb.append("- `project_map action=read module=<文件名>` — 看该文件的标题与章节\n");
        sb.append("- `project_map action=annotate module=<文件名> note=\"这份文档讲什么\"` — 补一句摘要（重建时保留）\n");
        sb.append("- `project_map action=rule op=add key=doc.xxx text=\"...\"` — 记文档规范（命名/归属/更新时机）\n");
        sb.append("- 读正文仍用 file_explorer（action=read）\n");
        return sb.toString();
    }

    private String renderIndex(Path root, ScanResult scan, List<ModuleState> moduleStates) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!-- 由 CodeCraft 自动生成，请勿手工编辑。重启任务的模型可用 project_map action=annotate 补职责 -->\n");
        sb.append("# 项目索引\n\n");
        sb.append("| 项 | 值 |\n|---|---|\n");
        sb.append("| 项目根 | `").append(root.toString().replace('\\', '/')).append("` |\n");
        sb.append("| Git HEAD | ").append(scan.gitHead() == null ? "（非 Git 仓库）" : "`" + shortHead(scan.gitHead()) + "`").append(" |\n");
        sb.append("| 生成时间 | ").append(TIME_FMT.format(Instant.now())).append(" |\n");
        sb.append("| 文件数 | ").append(scan.files()).append(" |\n");
        sb.append("| 模块数 | ").append(moduleStates.size()).append(" |\n");
        sb.append("| 语言分布 | ").append(formatLangCount(scan.langCount())).append(" |\n\n");

        sb.append("## 模块\n\n");
        sb.append("> 「职责」由模型用 annotate 标注，未标注前是自动摘要。改这列请用 `project_map action=annotate`。\n\n");
        sb.append("| 模块 | 文件 | 语言 | 职责 | 代表符号 | 详情 |\n|---|---|---|---|---|---|\n");
        Map<String, ModuleResolver.ModuleInfo> moduleMap = new LinkedHashMap<>();
        for (ModuleResolver.ModuleInfo module : scan.modules()) {
            moduleMap.put(module.key(), module);
        }
        for (ModuleState moduleState : moduleStates) {
            ModuleResolver.ModuleInfo module = moduleMap.get(moduleState.key());
            String note = moduleState.note();
            if (note == null || note.isBlank()) {
                note = module == null ? "-" : autoSummary(module);
            }
            String symbols = module == null ? "-" : topSymbols(scan.symbols(), module);
            sb.append("| ").append(moduleState.display())
                    .append(" | ").append(moduleState.files())
                    .append(" | ").append(moduleState.lang() == null ? "-" : moduleState.lang())
                    .append(" | ").append(escapeCell(note))
                    .append(" | ").append(escapeCell(symbols))
                    .append(" | `map/").append(moduleState.slug()).append(".md` |\n");
        }

        sb.append("\n## 入口与配置\n\n");
        sb.append("| 文件 | 用途 |\n|---|---|\n");
        for (String entry : ENTRY_FILES) {
            Path file = root.resolve(entry.replace('/', java.io.File.separatorChar));
            if (Files.isRegularFile(file)) {
                sb.append("| `").append(entry).append("` | ").append(describeEntry(entry)).append(" |\n");
            }
        }

        sb.append("\n## 用法\n\n");
        sb.append("- `project_map action=read module=<模块名>` — 读模块详情（文件 + 符号级）\n");
        sb.append("- `project_map action=annotate module=<模块名> note=\"职责与联动约束\"` — 补写语义（支持多行，重建时保留）\n");
        sb.append("- `project_map action=rule op=add key=link.xxx text=\"...\"` — 记联动约束 / 项目约定（建议附证据，非强制）\n");
        sb.append("- `project_map action=status` — 检查索引是否过期\n");
        sb.append("\n> 地图是缓存不是真相：与代码冲突时以代码为准，并可用 action=annotate 修正描述。\n");
        return sb.toString();
    }

    private String renderModuleFile(ModuleResolver.ModuleInfo module, ScanResult scan, String manual) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!-- module: ").append(module.key()).append(" -->\n");
        sb.append("# ").append(module.displayName()).append("\n\n");
        sb.append(MANUAL_BEGIN).append('\n');
        // manual 已是最终内容（调用方已用自动摘要兜底），这里原样保留多行——
        // 「联动约束」类描述天然是多行的，压成一行会丢语义
        sb.append(manual == null ? "" : manual.trim()).append('\n');
        sb.append(MANUAL_END).append("\n\n");

        sb.append("- 路径：`").append(module.key()).append("`\n");
        sb.append("- 文件数：").append(module.fileCount()).append('\n');
        sb.append("- 语言：").append(formatLangBrief(module.files())).append("\n\n");

        sb.append("## 文件\n\n");
        sb.append("| 文件 | 说明 | 符号 |\n|---|---|---|\n");
        Map<String, SymbolScanner.FileSymbols> symbolMap = new LinkedHashMap<>();
        for (SymbolScanner.FileSymbols symbols : scan.symbols()) {
            symbolMap.put(symbols.relPath(), symbols);
        }
        int shown = 0;
        for (String rel : module.files()) {
            if (shown++ >= MAX_FILES_PER_MODULE) {
                sb.append("| … | 其余 ").append(module.fileCount() - MAX_FILES_PER_MODULE).append(" 个文件已省略 | |\n");
                break;
            }
            SymbolScanner.FileSymbols fileSymbols = symbolMap.get(rel);
            String shortName = rel.substring(rel.lastIndexOf('/') + 1);
            String desc = fileSymbols == null || fileSymbols.description() == null
                    ? "" : fileSymbols.description();
            String symbols = fileSymbols == null ? "" : formatSymbols(fileSymbols);
            sb.append("| `").append(shortName).append("` | ").append(escapeCell(desc))
                    .append(" | ").append(escapeCell(symbols)).append(" |\n");
        }
        return sb.toString();
    }

    /**
     * 自动摘要：只讲「有多少文件、主要是哪种语言」。
     * <p>刻意不带代表符号——INDEX 里已经有独立的「代表符号」列，两处重复会让表格看起来像复制粘贴，
     * 而且这段话会进 manual 区被 firstLine 截断，越拼越难看。</p>
     */
    private String autoSummary(ModuleResolver.ModuleInfo module) {
        return module.fileCount() + " 个文件，主要为 " + formatLangBrief(module.files());
    }

    /**
     * 判断一段 manual 内容是不是自动生成的统计句（而非模型标注的语义）。
     * 用于决定它要不要进 note / 注入摘要。
     */
    private boolean isAutoSummary(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String first = firstLine(text);
        // 正则带长度上限，不会回溯爆炸
        return first.matches("^\\d{1,6} 个文件，主要为[^\\n]{0,90}$");
    }

    /** 模块的主要语言（文件数最多的那种），用于把纯文档目录排在源码模块后面 */
    private String primaryLang(ModuleResolver.ModuleInfo module) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String file : module.files()) {
            counts.merge(SymbolScanner.detectLang(file), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(entry -> displayLang(entry.getKey()))
                .orElse("Other");
    }

    /**
     * 模块的「代表符号」。
     * <p>{@code section}（文档章节）与 {@code table}（SQL 表名）也算——
     * 文档模块的代表符号就是它的核心章节标题，这对「这份文档讲什么」是最高密度的概括。</p>
     */
    private String topSymbols(List<SymbolScanner.FileSymbols> symbols, ModuleResolver.ModuleInfo module) {
        Set<String> names = new LinkedHashSet<>();
        for (SymbolScanner.FileSymbols fileSymbols : symbols) {
            if (!module.files().contains(fileSymbols.relPath())) {
                continue;
            }
            for (SymbolScanner.Symbol symbol : fileSymbols.symbols()) {
                if ("type".equals(symbol.kind()) || "component".equals(symbol.kind())
                        || "section".equals(symbol.kind()) || "table".equals(symbol.kind())) {
                    names.add(symbol.name());
                    if (names.size() >= 4) {
                        return String.join("、", names);
                    }
                }
            }
        }
        return names.isEmpty() ? "-" : String.join("、", names);
    }

    private String formatSymbols(SymbolScanner.FileSymbols fileSymbols) {
        if (fileSymbols.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        int count = 0;
        for (SymbolScanner.Symbol symbol : fileSymbols.symbols()) {
            if (count++ >= MAX_SYMBOLS_PER_FILE) {
                parts.add("…");
                break;
            }
            switch (symbol.kind()) {
                case "type", "component" -> parts.add(symbol.name());
                case "method" -> parts.add(symbol.name() + "()");
                case "prop" -> parts.add("prop:" + symbol.name());
                case "emit" -> parts.add("emit:" + symbol.name());
                default -> parts.add(symbol.name());
            }
        }
        return String.join(" ", parts);
    }

    private String formatLangCount(Map<String, Integer> langCount) {
        if (langCount.isEmpty()) {
            return "-";
        }
        // 必须用 List 排序而不是 TreeMap：TreeMap 的比较器在文件数相同时会返回 0，
        // 导致 Java / Vue 同为 100 个文件时其中一个被丢弃（丢掉整种语言）
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(langCount.entrySet());
        entries.sort((a, b) -> b.getValue() - a.getValue());
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : entries) {
            parts.add(displayLang(entry.getKey()) + " " + entry.getValue());
            if (parts.size() >= 6) {
                break;
            }
        }
        return String.join(" · ", parts);
    }

    private String formatLangBrief(List<String> files) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (String file : files) {
            map.merge(SymbolScanner.detectLang(file), 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(map.entrySet());
        entries.sort((a, b) -> b.getValue() - a.getValue());
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : entries) {
            parts.add(displayLang(entry.getKey()) + " " + entry.getValue());
            if (parts.size() >= 3) {
                break;
            }
        }
        return parts.isEmpty() ? "-" : String.join(" · ", parts);
    }

    private String displayLang(String lang) {
        return switch (lang) {
            case "java" -> "Java";
            case "vue" -> "Vue";
            case "ts" -> "TS";
            case "js" -> "JS";
            case "md" -> "Markdown";
            case "yaml" -> "YAML";
            case "json" -> "JSON";
            case "sql" -> "SQL";
            case "sh" -> "Shell";
            case "conf" -> "Config";
            case "py" -> "Python";
            case "go" -> "Go";
            default -> "Other";
        };
    }

    /** 给入口文件一句话用途说明（覆盖构建 / 运维 / 数据 / 协作四类） */
    private String describeEntry(String entry) {
        String lower = entry.toLowerCase();
        // —— 构建与依赖 ——
        if (lower.endsWith("pom.xml")) {
            return "Maven 构建、依赖与项目版本号";
        }
        if (lower.endsWith("package.json")) {
            int slash = entry.lastIndexOf('/');
            String scope = slash < 0 ? "根目录" : entry.substring(0, slash);
            return scope + " 的 JS 依赖与脚本";
        }
        if (lower.contains("gradle")) {
            return "Gradle 构建";
        }
        if (lower.contains("requirements.txt") || lower.contains("pyproject.toml")) {
            return "Python 依赖";
        }
        if (lower.contains("go.mod")) {
            return "Go 模块定义";
        }
        // —— 运行配置 ——
        if (lower.contains("application")) {
            return "后端主配置（端口 / 数据源 / 工具组 / 各功能开关）";
        }
        // —— 运维与部署 ——
        if (lower.startsWith("dockerfile")) {
            return "镜像构建";
        }
        if (lower.startsWith("docker-compose")) {
            return "容器编排（服务 / 端口 / 卷）";
        }
        if (lower.equals("makefile")) {
            return "构建入口";
        }
        if (lower.contains("jenkins") || lower.contains("gitlab-ci") || lower.contains("workflows")) {
            return "CI 流水线与发布";
        }
        if (lower.contains("helm/chart")) {
            return "Helm 编排";
        }
        if (lower.contains("k8s/")) {
            return "K8s 部署清单";
        }
        if (lower.contains("ansible")) {
            return "自动化配置管理";
        }
        if (lower.contains("terraform") || lower.endsWith(".tf")) {
            return "基础设施定义";
        }
        if (lower.startsWith("scripts/") || lower.startsWith("start.")) {
            return "运维脚本（构建 / 启动）";
        }
        // —— 数据 ——
        if (lower.endsWith("schema.sql")) {
            return "数据库表结构";
        }
        // —— 接口契约 ——
        if (lower.contains("openapi") || lower.contains("swagger")) {
            return "接口契约";
        }
        // —— 协作与文档 ——
        if (lower.startsWith("readme")) {
            return "项目说明";
        }
        if (lower.startsWith("contributing")) {
            return "贡献指南";
        }
        if (lower.startsWith("changelog")) {
            return "版本变更记录";
        }
        if (lower.contains("architecture")) {
            return "架构说明";
        }
        if (lower.startsWith("agents.") || lower.startsWith("claude.")) {
            return "项目规则约定";
        }
        return "-";
    }

    private String renderRulesTemplate() {
        return "# 项目规则与联动约束\n\n"
                + "> 由模型在任务中沉淀：工程约定（build. / style. / avoid.）与重语义知识（link. / know. / risk.）。\n"
                + "> key 前缀白名单：" + String.join(" / ", RULE_PREFIXES) + "\n"
                + "> 单条 ≤ " + config.getMaxRuleLength() + " 字符（一条只讲一件事），活跃上限 "
                + config.getMaxRules() + " 条。\n"
                + "> 证据（evidence）建议提供但不强制——联动约束类知识往往没有单一证据行。\n\n";
    }

    // ==================== 文件读写 ====================

    private String extractManual(Path mapFile) {
        if (!Files.isRegularFile(mapFile)) {
            return "";
        }
        // readText 内部已吞掉 IOException，这里不会再抛
        String content = readText(mapFile);
        int begin = content.indexOf(MANUAL_BEGIN);
        int end = content.indexOf(MANUAL_END);
        if (begin < 0 || end <= begin) {
            return "";
        }
        return content.substring(begin + MANUAL_BEGIN.length(), end).trim();
    }

    private String replaceManual(String content, String manual) {
        int begin = content.indexOf(MANUAL_BEGIN);
        int end = content.indexOf(MANUAL_END);
        if (begin < 0 || end <= begin) {
            return content + "\n" + MANUAL_BEGIN + "\n" + manual + "\n" + MANUAL_END + "\n";
        }
        return content.substring(0, begin + MANUAL_BEGIN.length())
                + "\n" + manual + "\n" + content.substring(end);
    }

    private StateFile readState(Path root) {
        Path file = root.resolve(DIR_NAME).resolve(STATE_FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return objectMapper.readValue(Files.readString(file, StandardCharsets.UTF_8), StateFile.class);
        } catch (IOException e) {
            log.debug("state.json 解析失败: {}", e.getMessage());
            return null;
        }
    }

    private String readText(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return "";
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** 原子写：临时文件 + ATOMIC_MOVE，避免进程被杀时留下半截文件 */
    private void atomicWrite(Path target, String content) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ==================== 小工具 ====================

    private String slugOf(String displayName) {
        String s = displayName;
        if (ModuleResolver.ROOT_KEY.equals(s)) {
            return "_root";
        }
        s = s.replace('/', '-').replace('\\', '-');
        s = s.replaceAll("[^A-Za-z0-9._-]", "_");
        if (s.length() > 60) {
            s = s.substring(0, 60);
        }
        return s.isEmpty() ? "module" : s;
    }

    private String shortHead(String head) {
        return head.length() > 8 ? head.substring(0, 8) : head;
    }

    private String escapeCell(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replace("|", "\\|").replace("\n", " ");
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "\n…（内容超长已截断，可缩小范围重新读取）";
    }
}
