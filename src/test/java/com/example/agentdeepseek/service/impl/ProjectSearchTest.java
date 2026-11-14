package com.example.agentdeepseek.service.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@link ProjectServiceImpl#searchFiles} 自测（main 方法式，不依赖 Spring 上下文）。
 *
 * <p>运行方式：
 * <pre>
 *   mvn -q test-compile -DskipTests
 *   mvn exec:java "-Dexec.mainClass=com.example.agentdeepseek.service.impl.ProjectSearchTest" "-Dexec.classpathScope=test"
 * </pre>
 *
 * <p>覆盖点：文件名全等 / 前缀 / 包含 / 仅路径包含 的四档优先级、大小写不敏感、
 * 排除目录（.git、node_modules、target）与排除前缀（src/main/resources/static）、
 * 空关键字 / null / 根目录不存在 / limit 收敛。
 */
public class ProjectSearchTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("cc-search-test");
        try {
            run(root);
        } finally {
            deleteRecursively(root);
        }

        System.out.println("========================================");
        System.out.println("ProjectSearchTest: 通过 " + passed + " 项，失败 " + failed + " 项");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void run(Path root) throws Exception {
        // 样例题：正常源码 + 三个必须被排除的目录 + 一个排除前缀
        write(root, "src/App.vue", "<template/>");
        write(root, "src/main/java/Demo.java", "class Demo {}");
        write(root, "src/utils/markdown.ts", "export {}");
        write(root, "src/utils/file-path.ts", "export {}");
        write(root, "README.md", "# readme");
        write(root, "docs/guide.md", "# guide");
        write(root, "node_modules/app/index.js", "// excluded");
        write(root, "target/App.class", "// excluded");
        write(root, ".git/config", "// excluded");
        write(root, "src/main/resources/static/app.js", "// excluded by prefix");

        ProjectServiceImpl service = new ProjectServiceImpl();
        String base = root.toString();

        // 1) 文件名命中 + 三类排除目录
        List<String> app = service.searchFiles(base, "App", 100);
        check("文件名搜索命中 src/App.vue", app.contains("src/App.vue"), app);
        check("排除 node_modules", noneStartsWith(app, "node_modules/"), app);
        check("排除 target", noneStartsWith(app, "target/"), app);
        check("排除 .git", noneStartsWith(app, ".git/"), app);
        check("排除静态资源前缀", noneStartsWith(app, "src/main/resources/static"), app);

        // 2) 大小写不敏感
        List<String> readme = service.searchFiles(base, "readme", 100);
        check("大小写不敏感（readme → README.md）", readme.contains("README.md"), readme);

        // 3) 前缀优先于「包含」：mark 应命中 markdown.ts，且它是唯一/首个结果
        List<String> mark = service.searchFiles(base, "mark", 100);
        check("前缀命中排首位", !mark.isEmpty() && "src/utils/markdown.ts".equals(mark.get(0)), mark);

        // 4) 仅路径包含：utils/ 命中目录下两个文件
        List<String> utils = service.searchFiles(base, "utils/", 100);
        check("路径匹配命中两个 utils 文件",
                utils.contains("src/utils/markdown.ts") && utils.contains("src/utils/file-path.ts"), utils);

        // 5) 空关键字 / null
        check("空白关键字返回空列表", service.searchFiles(base, "   ", 100).isEmpty(), List.of());
        check("null 关键字返回空列表", service.searchFiles(base, null, 100).isEmpty(), List.of());

        // 6) 根目录不存在
        check("根目录不存在返回空列表",
                service.searchFiles(root.resolve("not-exists").toString(), "App", 100).isEmpty(), List.of());

        // 7) limit 收敛（limit=1 只回 1 条；limit=0 走默认 100，不抛错）
        List<String> one = service.searchFiles(base, ".", 1);
        check("limit=1 只返回 1 条", one.size() == 1, one);
        List<String> fallback = service.searchFiles(base, "App", 0);
        check("limit=0 走默认且仍有结果", fallback.contains("src/App.vue"), fallback);

        // 8) 文件名全等优先于前缀：搜 App.vue 时精确项排第一
        List<String> exact = service.searchFiles(base, "App.vue", 100);
        check("文件名全等排首位", !exact.isEmpty() && "src/App.vue".equals(exact.get(0)), exact);

        // 9) 无匹配
        check("无匹配返回空列表", service.searchFiles(base, "zzz-not-exists", 100).isEmpty(), List.of());
    }

    private static boolean noneStartsWith(List<String> list, String prefix) {
        return list.stream().noneMatch(p -> p.startsWith(prefix));
    }

    private static void write(Path root, String rel, String content) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void check(String name, boolean ok, List<String> detail) {
        if (ok) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            failed++;
            System.out.println("[FAIL] " + name + (detail.isEmpty() ? "" : " → " + detail));
        }
    }

    private static void deleteRecursively(Path path) {
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 临时目录清理失败不影响测试结论
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }
}
