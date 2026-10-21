package com.example.agentdeepseek.projectcontext;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 模块划分器：把扁平的文件相对路径列表聚成「模块 → 文件列表」。
 *
 * <p><b>为什么不用硬编码规则</b>：不同项目结构差异极大（Maven 单模块 / 多模块 / 纯前端 / Electron），
 * 硬编码包名或目录名会把「本项目」写死进通用能力里。这里改用<b>文件数阈值聚类</b>：
 * 从根往下逐级判断，某目录的子树文件数 ≤ 阈值时就收为一个模块，否则继续下钻。</p>
 *
 * <p>效果举例（CodeCraft 自身）：{@code src} / {@code src/main/java} / {@code .../agentdeepseek}
 * 这些大目录会被穿透，最终落到 {@code .../agentdeepseek/service}（52 个文件）、
 * {@code .../agentdeepseek/tool}（40 个）、{@code frontend-v3/src/components}（30 个）等真正内聚的单元。</p>
 */
@Slf4j
@Component
public class ModuleResolver {

    /** 模块划分结果 */
    public record ModuleInfo(String key, String displayName, List<String> files) {
        public int fileCount() {
            return files == null ? 0 : files.size();
        }
    }

    /** 目录名命中即整棵子树跳过（构建产物、依赖、缓存、工具目录、版本库） */
    public static final Set<String> EXCLUDE_DIRS = Set.of(
            "node_modules", "target", "build", "dist", "out", ".git", ".svn",
            ".idea", ".vscode", ".settings", "release", "__pycache__", ".gradle",
            ".codecraft", ".cache", "coverage", "logs", "snapshots", "v3-static",
            "jre", ".mvn", ".next", ".nuxt", ".turbo", ".planning", ".specstory",
            ".husky", ".nx", ".yarn", ".pnpm-store", "tmp", "temp");

    /**
     * 「带三段版本号的目录」= 归档/发布产物（如 {@code codecraft-release-1.1.8}、{@code dist-2.3.4}）。
     * 这类目录没有源码价值，但名字不固定，只能靠版本号特征识别。
     * 正则带长度上限，不会回溯爆炸。
     */
    private static final Pattern VERSION_SEGMENT =
            Pattern.compile("(?:^|[.\\-_])v?\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");

    /** 文件后缀命中即跳过（压缩产物、二进制、锁文件） */
    private static final Set<String> EXCLUDE_SUFFIX = Set.of(
            ".min.js", ".min.css", ".map", ".lock", ".jar", ".war", ".exe",
            ".dmg", ".appimage", ".class", ".so", ".dll", ".dylib", ".png", ".jpg",
            ".jpeg", ".gif", ".webp", ".ico", ".pdf", ".zip", ".gz", ".tar", ".7z",
            "package-lock.json", "pnpm-lock.yaml", "yarn.lock");

    private static final int MAX_PATH_LENGTH = 240;

    /**
     * 按完整路径前缀排除。这两个是 Spring Boot 约定的前端构建输出目录，
     * 里面全是 {@code AgentConfigView-BI7WvCDk.js} 这种带哈希的产物——
     * 索引它们既占预算又会让模型误以为那是源码。
     */
    private static final List<String> EXCLUDE_PATH_PREFIXES = List.of(
            "src/main/resources/static/",
            "src/main/resources/v3-static/");

    /**
     * 「容器段」：这些路径段只是存放位置，本身不表达模块语义。
     * 若一个目录全部由容器段组成（如 {@code src/main}、{@code src/main/resources}），
     * 把它当模块名毫无意义，兜底时应继续向上归并到项目根。
     *
     * <p>注意：这里<b>不能</b>包含 {@code test} / {@code docs} / {@code deploy} / {@code ops} /
     * {@code scripts} / {@code specs} 这类词——它们在非编码场景（测试用例、需求文档、运维手册）
     * 里本身就是有意义的模块名，当成容器段会把整个模块吞掉。</p>
     */
    private static final Set<String> CONTAINER_SEGMENTS = Set.of(
            "src", "main", "java", "resources", "com", "example", "org", "net", "io", "webapp");

    private final ProjectContextConfig config;

    public ModuleResolver(ProjectContextConfig config) {
        this.config = config;
    }

    /**
     * 目录名是否应整体跳过。遍历（preVisitDirectory）与单文件过滤共用同一套判断，
     * 保证不会出现「目录没跳过但里面的文件被跳过」这种半截状态。
     */
    public boolean shouldSkipDirName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (EXCLUDE_DIRS.contains(name)) {
            return true;
        }
        return VERSION_SEGMENT.matcher(name).find();
    }

    /**
     * 判断某个相对路径是否应被跳过。
     * 纯字符串判断（不碰文件系统），调用方在遍历时先用它过滤。
     */
    public boolean shouldSkip(String relPath) {
        if (relPath == null || relPath.isEmpty() || relPath.length() > MAX_PATH_LENGTH) {
            return true;
        }
        String lower = relPath.toLowerCase();
        for (String prefix : EXCLUDE_PATH_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        for (String suffix : EXCLUDE_SUFFIX) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        // 路径任一段命中排除目录（含版本号归档目录）即跳过
        int from = 0;
        while (true) {
            int slash = relPath.indexOf('/', from);
            String seg = slash < 0 ? relPath.substring(from) : relPath.substring(from, slash);
            if (shouldSkipDirName(seg)) {
                return true;
            }
            if (slash < 0) {
                break;
            }
            from = slash + 1;
        }
        return false;
    }

    /**
     * 把文件列表聚成模块。
     *
     * @param relPaths 相对项目根、以 {@code /} 分隔的文件路径（调用方已过滤）
     */
    public List<ModuleInfo> resolve(List<String> relPaths) {
        if (relPaths == null || relPaths.isEmpty()) {
            return List.of();
        }
        int total = relPaths.size();
        // 阈值：至少 moduleMinFiles 个文件，或总数的 moduleFileRatio（取较大者），避免小项目碎成几十个模块
        int threshold = Math.max(config.getModuleMinFiles(),
                (int) Math.ceil(total * config.getModuleFileRatio()));

        // 1) 统计每个目录的「子树文件数」
        Map<String, Integer> subtree = new HashMap<>();
        for (String path : relPaths) {
            String dir = parentOf(path);
            while (!dir.isEmpty()) {
                subtree.merge(dir, 1, Integer::sum);
                dir = parentOf(dir);
            }
        }

        // 2) 逐文件挑选模块：由浅到深找第一个「大小合适」的祖先目录
        //    - 上限 threshold：太大就继续下钻（否则 1030 个文件的项目里整个前端并成一块）
        //    - 下限 minFiles：太小就跳过（否则会切出 impl/document(7)、scripts(2) 这种碎片）
        int minFiles = Math.max(2, config.getModuleMinFiles());
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String path : relPaths) {
            String chosen = null;
            for (String ancestor : ancestorChain(path)) {
                int count = subtree.getOrDefault(ancestor, 0);
                if (count >= minFiles && count <= threshold) {
                    chosen = ancestor;
                    break;
                }
            }
            if (chosen == null) {
                // 兜底：向上找第一个「文件数达标且不是纯容器目录」的祖先。
                // 两个坑都避开了：不造 2 个文件的碎片模块，也不产出 src/main 这种无意义模块名。
                String dir = parentOf(path);
                while (!dir.isEmpty()
                        && (subtree.getOrDefault(dir, 0) < minFiles || isContainerPath(dir))) {
                    dir = parentOf(dir);
                }
                chosen = dir.isEmpty() ? ROOT_KEY : dir;
            }
            groups.computeIfAbsent(chosen, k -> new ArrayList<>()).add(path);
        }

        // 3) 强制最小块：任何仍不足 minFiles 的模块，并入最近的已有模块，找不到就归项目根
        groups = enforceMinSize(groups, minFiles);

        // 3) 模块数超上限：把文件数最少的模块并入父目录，直到达标
        groups = collapseIfTooMany(groups);

        // 4) 生成显示名（末段，冲突时逐级加前缀）
        Map<String, String> displayNames = buildDisplayNames(groups.keySet());

        List<ModuleInfo> result = new ArrayList<>(groups.size());
        for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
            String key = entry.getKey();
            List<String> files = entry.getValue();
            files.sort(Comparator.naturalOrder());
            result.add(new ModuleInfo(key, displayNames.getOrDefault(key, key), files));
        }
        result.sort(Comparator.comparingInt(ModuleInfo::fileCount).reversed());
        log.debug("模块划分完成: 文件 {} 个 → 模块 {} 个（阈值 {}）", total, result.size(), threshold);
        return result;
    }

    /** 根目录散文件的模块 key */
    public static final String ROOT_KEY = "(root)";

    /** 目录是否全部由容器段组成（如 src/main、src/main/resources） */
    private boolean isContainerPath(String dir) {
        for (String seg : dir.split("/")) {
            if (!CONTAINER_SEGMENTS.contains(seg)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 强制最小块：文件数不足 {@code minFiles} 的模块，并入最近的已有模块；找不到归属就归项目根。
     *
     * <p>这是「模块名可读性」的最后一道保险。比 {@code impl/document(8)} 更糟的是
     * 一个只含 1 个文件的模块——它除了占位没有任何作用，却会白白吃掉注入预算的一行。</p>
     */
    private Map<String, List<String>> enforceMinSize(Map<String, List<String>> groups, int minFiles) {
        if (groups.size() <= 1) {
            return groups;
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
            if (entry.getValue().size() >= minFiles) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        if (result.isEmpty()) {
            // 所有模块都小（小项目）→ 保留原样，交给上限与注入预算控制
            return groups;
        }
        for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
            if (entry.getValue().size() >= minFiles) {
                continue;
            }
            String target = null;
            for (String key : result.keySet()) {
                if (ROOT_KEY.equals(key)) {
                    continue;
                }
                // 碎片模块是已有模块的子目录，或已有模块是它的子目录 → 可归并
                if (entry.getKey().startsWith(key + "/") || key.startsWith(entry.getKey() + "/")) {
                    if (target == null || key.length() > target.length()) {
                        target = key;
                    }
                }
            }
            result.computeIfAbsent(target == null ? ROOT_KEY : target, k -> new ArrayList<>())
                    .addAll(entry.getValue());
        }
        return result;
    }

    // ==================== 内部实现 ====================

    private Map<String, List<String>> collapseIfTooMany(Map<String, List<String>> groups) {
        int limit = Math.max(4, config.getMaxModules());
        if (groups.size() <= limit) {
            return groups;
        }
        // 按文件数升序，从最小的开始往上并
        List<Map.Entry<String, List<String>>> entries = new ArrayList<>(groups.entrySet());
        entries.sort(Comparator.comparingInt(e -> e.getValue().size()));

        Map<String, List<String>> merged = new LinkedHashMap<>(groups);
        for (Map.Entry<String, List<String>> entry : entries) {
            if (merged.size() <= limit) {
                break;
            }
            String key = entry.getKey();
            List<String> files = merged.remove(key);
            if (files == null) {
                continue;
            }
            String parent = parentOf(key);
            String target = (parent.isEmpty() || ROOT_KEY.equals(key)) ? ROOT_KEY : parent;
            merged.computeIfAbsent(target, k -> new ArrayList<>()).addAll(files);
        }
        return merged;
    }

    /**
     * 生成短显示名。
     *
     * <p>规则：<b>至少保留 2 段</b>。只留末段的话会出现 {@code impl}、{@code components} 这种
     * 看不出属于哪个子系统的名字（{@code tool/impl} 和 {@code service/impl} 都叫 impl 最典型），
     * 而模型要靠这个名字去 {@code action=read module=<名字>}，名字有歧义就得多试一次。</p>
     *
     * <p>仍需去重时（末 2 段也相同），再逐级加前缀；极端情况兜底加 hash。</p>
     */
    private Map<String, String> buildDisplayNames(Set<String> keys) {
        Map<String, String> result = new HashMap<>();
        for (String key : keys) {
            if (ROOT_KEY.equals(key)) {
                result.put(key, ROOT_KEY);
                continue;
            }
            result.put(key, tailOf(key, Math.min(2, key.split("/").length)));
        }
        // 末 2 段仍冲突 → 逐级加前缀直到唯一
        for (String key : keys) {
            if (ROOT_KEY.equals(key)) {
                continue;
            }
            String[] segments = key.split("/");
            int take = Math.min(2, segments.length);
            while (take < segments.length && countSameTail(keys, tailOf(key, take), take) > 1) {
                take++;
            }
            result.put(key, tailOf(key, take));
        }
        // 极端情况仍冲突（同名不同层级）→ 附上完整路径的哈希后 4 位
        Map<String, Integer> nameCount = new HashMap<>();
        for (String name : result.values()) {
            nameCount.merge(name, 1, Integer::sum);
        }
        for (Map.Entry<String, String> entry : new ArrayList<>(result.entrySet())) {
            if (nameCount.getOrDefault(entry.getValue(), 0) > 1) {
                String suffix = Integer.toHexString(entry.getKey().hashCode() & 0xFFFF);
                result.put(entry.getKey(), entry.getValue() + "-" + suffix);
            }
        }
        return result;
    }

    /** 取路径末 take 段 */
    private String tailOf(String path, int take) {
        String[] segments = path.split("/");
        int from = Math.max(0, segments.length - take);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < segments.length; i++) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(segments[i]);
        }
        return sb.toString();
    }

    private int countSameTail(Set<String> keys, String tail, int take) {
        int count = 0;
        for (String key : keys) {
            if (ROOT_KEY.equals(key)) {
                continue;
            }
            String[] segments = key.split("/");
            if (segments.length < take) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = segments.length - take; i < segments.length; i++) {
                if (sb.length() > 0) {
                    sb.append('/');
                }
                sb.append(segments[i]);
            }
            if (sb.toString().equals(tail)) {
                count++;
            }
        }
        return count;
    }

    /** 返回路径的所有祖先目录，由浅到深，不含根（空串） */
    private List<String> ancestorChain(String path) {
        Set<String> chain = new LinkedHashSet<>();
        String dir = parentOf(path);
        while (!dir.isEmpty()) {
            chain.add(dir);
            dir = parentOf(dir);
        }
        List<String> ordered = new ArrayList<>(chain);
        // 反转成由浅到深
        List<String> result = new ArrayList<>(ordered.size());
        for (int i = ordered.size() - 1; i >= 0; i--) {
            result.add(ordered.get(i));
        }
        return result;
    }

    /** 取父目录（最后一段之前的部分），无父目录返回空串 */
    private String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "" : path.substring(0, slash);
    }

    /** 用于给模块文件排序时的稳定顺序（供外部复用） */
    public static Comparator<ModuleInfo> byDisplayName() {
        return Comparator.comparing(ModuleInfo::displayName);
    }
}
