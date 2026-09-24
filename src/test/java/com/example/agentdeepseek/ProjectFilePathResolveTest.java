package com.example.agentdeepseek;

import com.example.agentdeepseek.service.impl.ProjectServiceImpl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * ProjectServiceImpl.resolveFilePath 路径解析回归测试（纯逻辑，不依赖 Spring 与外部环境）。
 *
 * <p>覆盖：路径后缀匹配、精确匹配优先、多候选排序、黑名单目录（node_modules/target/.git 等）
 * 与排除路径前缀（src/main/resources/static）剪枝、前导 ./ 归一化、绝对路径/空路径不解析、
 * 无候选返回空列表。</p>
 *
 * <p>运行方式（main 方法，非 JUnit）：
 * <pre>
 * mvn -q -DskipTests -DskipFrontend=true test-compile
 * mvn -q org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
 *     -Dexec.mainClass=com.example.agentdeepseek.ProjectFilePathResolveTest \
 *     -Dexec.classpathScope=test -DskipFrontend=true
 * </pre>
 * </p>
 */
public class ProjectFilePathResolveTest {

    static int passed = 0;
    static int failed = 0;

    interface Assertion {
        boolean check() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== ProjectServiceImpl.resolveFilePath 路径解析回归测试 ===\n");

        Path root = Files.createTempDirectory("cc-resolve-test");
        try {
            // 目标文件（LLM 输出短路径 api/appearance.ts 时应该命中的真实文件）
            touch(root, "frontend/src/api/appearance.ts");
            touch(root, "frontend/src/components/AppearanceSettingsDrawer.vue");
            touch(root, "src/main/java/com/example/App.java");
            touch(root, "docs/readme.md");
            // 多候选场景：两个同后缀文件
            touch(root, "frontend/src/util/helper.ts");
            touch(root, "backend/src/util/helper.ts");
            // 黑名单目录中的同名文件（应被排除，否则 case1 会返回多个候选）
            touch(root, "node_modules/pkg/api/appearance.ts");
            touch(root, "target/classes/api/appearance.ts");
            touch(root, ".git/api/appearance.ts");
            // 排除路径前缀中的同名文件（EXCLUDED_PREFIXES 剪枝）
            touch(root, "src/main/resources/static/api/appearance.ts");

            ProjectServiceImpl svc = new ProjectServiceImpl();
            String rootStr = root.toString();

            test("后缀匹配：api/appearance.ts → 唯一命中 frontend/src/api/appearance.ts（黑名单目录全部排除）", () -> {
                List<String> r = svc.resolveFilePath(rootStr, "api/appearance.ts");
                return r.size() == 1 && r.get(0).equals("frontend/src/api/appearance.ts");
            });

            test("精确匹配优先：完整相对路径命中且排在首位", () -> {
                List<String> r = svc.resolveFilePath(rootStr, "frontend/src/api/appearance.ts");
                return !r.isEmpty() && r.get(0).equals("frontend/src/api/appearance.ts");
            });

            test("多候选：util/helper.ts 返回 2 个候选（字典序）", () -> {
                List<String> r = svc.resolveFilePath(rootStr, "util/helper.ts");
                return r.size() == 2
                        && r.get(0).equals("backend/src/util/helper.ts")
                        && r.get(1).equals("frontend/src/util/helper.ts");
            });

            test("静态资源前缀排除：static/api/appearance.ts 无候选", () -> {
                return svc.resolveFilePath(rootStr, "static/api/appearance.ts").isEmpty();
            });

            test("前导 ./ 归一化：./api/appearance.ts 命中同一文件", () -> {
                List<String> r = svc.resolveFilePath(rootStr, "./api/appearance.ts");
                return r.size() == 1 && r.get(0).equals("frontend/src/api/appearance.ts");
            });

            test("反斜杠归一化：api\\appearance.ts 命中同一文件", () -> {
                List<String> r = svc.resolveFilePath(rootStr, "api\\appearance.ts");
                return r.size() == 1 && r.get(0).equals("frontend/src/api/appearance.ts");
            });

            test("无候选：不存在路径返回空列表", () -> {
                return svc.resolveFilePath(rootStr, "no/such/file.ts").isEmpty();
            });

            test("绝对路径不解析：直接返回空列表", () -> {
                return svc.resolveFilePath(rootStr, "C:/x/y/appearance.ts").isEmpty()
                        && svc.resolveFilePath(rootStr, "/var/log/app.log").isEmpty();
            });

            test("空路径/空白路径：返回空列表不抛异常", () -> {
                return svc.resolveFilePath(rootStr, "").isEmpty()
                        && svc.resolveFilePath(rootStr, "   ").isEmpty()
                        && svc.resolveFilePath(rootStr, null).isEmpty();
            });

            test("root 为空：回退默认根目录，不抛异常（返回值仅要求为列表）", () -> {
                List<String> r = svc.resolveFilePath("", "api/appearance.ts");
                return r != null;
            });
        } finally {
            deleteRecursively(root);
        }

        System.out.println("\n===== 结果: " + passed + " 通过, " + failed + " 失败 =====");
        if (failed > 0) {
            System.exit(1);
        }
    }

    static void touch(Path root, String rel) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "// test\n");
    }

    static void deleteRecursively(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 清理失败不影响测试结论
                }
            });
        } catch (IOException ignored) {
            // 忽略
        }
    }

    static void test(String name, Assertion a) {
        try {
            if (a.check()) {
                passed++;
                System.out.println("  ✓ " + name);
            } else {
                failed++;
                System.out.println("  ✗ " + name);
            }
        } catch (Exception e) {
            failed++;
            System.out.println("  ✗ " + name + " （异常: " + e + "）");
        }
    }
}
