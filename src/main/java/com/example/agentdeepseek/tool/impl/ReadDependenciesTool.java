package com.example.agentdeepseek.tool.impl;
import com.example.agentdeepseek.util.ProjectRootContext;

import com.example.agentdeepseek.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * 项目依赖读取工具
 * 自动检测 pom.xml / package.json，解析关键依赖并按类型分组展示
 */
@Slf4j
@Component
public class ReadDependenciesTool implements Tool {

    private static final int MAX_DEPENDENCIES = 100;

    private final ObjectMapper objectMapper;

    public ReadDependenciesTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "read_dependencies";
    }

    @Override
    public String getDescription() {
        return "自动检测项目依赖：识别 pom.xml（Maven）或 package.json（npm），解析关键依赖并按 scope/类型分组展示。"
                + "支持过滤（如只显示 compile 范围或 dev 依赖）。双项目（Maven + npm 混合）自动合并展示。"
                + "适用于了解项目用到的第三方库和框架版本";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode path = objectMapper.createObjectNode();
        path.put("type", "string");
        path.put("description", "项目目录路径（可选），默认为项目根目录。自动检测 pom.xml 或 package.json");
        properties.set("path", path);

        ObjectNode filter = objectMapper.createObjectNode();
        filter.put("type", "string");
        filter.put("description", "依赖过滤（可选），如 compile/test 只显示某 scope 的 Maven 依赖，或 prod/dev 过滤 npm 依赖");
        properties.set("filter", filter);

        parameters.set("properties", properties);
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String pathStr = arguments.path("path").asText();
        String filter = arguments.path("filter").asText();

        Path projectDir;
        if (!pathStr.isEmpty()) {
            projectDir = resolvePath(pathStr);
            if (!Files.exists(projectDir)) {
                return "错误：路径不存在 - " + projectDir.toAbsolutePath();
            }
            if (!Files.isDirectory(projectDir)) {
                projectDir = projectDir.getParent();
            }
        } else {
            projectDir = Paths.get(ProjectRootContext.get());
        }

        // 自动检测项目类型
        Path pomXml = projectDir.resolve("pom.xml");
        Path packageJson = projectDir.resolve("package.json");

        if (Files.exists(pomXml) && Files.exists(packageJson)) {
            // 双项目（前端+后端）
            return formatDualProject(projectDir, pomXml, packageJson, filter);
        } else if (Files.exists(pomXml)) {
            return formatMavenDeps(projectDir, pomXml, filter);
        } else if (Files.exists(packageJson)) {
            return formatNpmDeps(projectDir, packageJson, filter);
        } else {
            return "错误：未找到 pom.xml 或 package.json，无法读取依赖信息";
        }
    }

    /**
     * 解析 Maven pom.xml 依赖
     */
    private String formatMavenDeps(Path projectDir, Path pomPath, String filter) {
        List<MavenDep> allDeps = parseMavenDeps(pomPath);

        if (allDeps.isEmpty()) {
            return "项目：" + projectDir.toAbsolutePath() + "\n（pom.xml 中未定义依赖）";
        }

        // 按 scope 分组
        Map<String, List<MavenDep>> grouped = new LinkedHashMap<>();
        for (MavenDep dep : allDeps) {
            String scope = (dep.scope != null) ? dep.scope : "compile";
            if (filter.isEmpty() || scope.equalsIgnoreCase(filter)) {
                grouped.computeIfAbsent(scope, k -> new ArrayList<>()).add(dep);
            }
        }

        if (grouped.isEmpty()) {
            return "未找到匹配过滤条件 \"" + filter + "\" 的依赖";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("项目：").append(projectDir.toAbsolutePath()).append("\n");
        sb.append("类型：Maven（pom.xml）\n");
        sb.append("────────────────────────────────────────\n");

        int total = allDeps.size();
        int shown = grouped.values().stream().mapToInt(List::size).sum();
        sb.append("共 ").append(total).append(" 个依赖").append(shown < total ? "（显示 " + shown + " 个）" : "").append("\n\n");

        for (Map.Entry<String, List<MavenDep>> entry : grouped.entrySet()) {
            List<MavenDep> deps = entry.getValue();
            sb.append("【").append(entry.getKey()).append(" 依赖】（").append(deps.size()).append(" 个）\n");
            for (MavenDep dep : deps) {
                sb.append("  ").append(dep.groupId).append(":").append(dep.artifactId);
                if (dep.version != null && !dep.version.startsWith("${")) {
                    sb.append(":").append(dep.version);
                }
                if (dep.optional) {
                    sb.append(" [optional]");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 解析 npm package.json 依赖
     */
    private String formatNpmDeps(Path projectDir, Path packagePath, String filter) {
        try {
            String content = Files.readString(packagePath, StandardCharsets.UTF_8);

            // 简单 JSON 解析，提取 dependencies 和 devDependencies
            Map<String, String> prodDeps = extractJsonSection(content, "dependencies");
            Map<String, String> devDeps = extractJsonSection(content, "devDependencies");
            Map<String, String> peerDeps = extractJsonSection(content, "peerDependencies");

            if (prodDeps.isEmpty() && devDeps.isEmpty() && peerDeps.isEmpty()) {
                return "项目：" + projectDir.toAbsolutePath() + "\n（package.json 中未定义依赖）";
            }

            // 读取 name + version
            String projectName = extractJsonValue(content, "name");
            String projectVersion = extractJsonValue(content, "version");

            StringBuilder sb = new StringBuilder();
            sb.append("项目：").append(projectDir.toAbsolutePath()).append("\n");
            if (projectName != null) {
                sb.append("名称：").append(projectName);
                if (projectVersion != null) sb.append(" v").append(projectVersion);
                sb.append("\n");
            }
            sb.append("类型：npm（package.json）\n");
            sb.append("────────────────────────────────────────\n");

            int total = prodDeps.size() + devDeps.size() + peerDeps.size();
            sb.append("共 ").append(total).append(" 个依赖\n\n");

            appendNpmSection(sb, "生产依赖", prodDeps, "prod", filter);
            appendNpmSection(sb, "开发依赖", devDeps, "dev", filter);
            appendNpmSection(sb, "对等依赖", peerDeps, "peer", filter);

            return sb.toString();
        } catch (IOException e) {
            return "错误：读取 package.json 失败 - " + e.getMessage();
        }
    }

    /**
     * 双项目模式（同一目录下同时有 pom.xml 和 package.json）
     */
    private String formatDualProject(Path projectDir, Path pomPath, Path packagePath, String filter) {
        StringBuilder sb = new StringBuilder();
        sb.append("项目：").append(projectDir.toAbsolutePath()).append("\n");
        sb.append("类型：Maven + npm 混合项目\n");
        sb.append("────────────────────────────────────────\n\n");

        String mavenPart = formatMavenDeps(projectDir, pomPath, filter);
        String npmPart = formatNpmDeps(projectDir, packagePath, filter);

        // 提取标题行（第一行）之后的实际内容
        String mavenContent = mavenPart.substring(mavenPart.indexOf('\n') + 1);
        String npmContent = npmPart.substring(npmPart.indexOf('\n') + 1);

        sb.append("--- Maven 依赖 ---\n").append(mavenContent).append("\n");
        sb.append("--- npm 依赖 ---\n").append(npmContent);

        return sb.toString();
    }

    /**
     * 基于字符串行的 Maven 依赖解析（逐行解析 XML 标签）
     */
    private List<MavenDep> parseMavenDeps(Path pomPath) {
        List<MavenDep> deps = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(pomPath, StandardCharsets.UTF_8);
            boolean inDependencies = false;
            String currentGroupId = null, currentArtifactId = null, currentVersion = null, currentScope = null;
            boolean inDep = false;
            boolean optional = false;

            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.contains("<dependencies>")) {
                    inDependencies = true;
                    continue;
                }
                if (trimmed.contains("</dependencies>")) {
                    inDependencies = false;
                    continue;
                }
                if (!inDependencies) continue;

                if (trimmed.contains("<dependency>")) {
                    inDep = true;
                    currentGroupId = null;
                    currentArtifactId = null;
                    currentVersion = null;
                    currentScope = null;
                    optional = false;
                    continue;
                }
                if (trimmed.contains("</dependency>")) {
                    if (inDep && currentGroupId != null && currentArtifactId != null && deps.size() < MAX_DEPENDENCIES) {
                        MavenDep dep = new MavenDep();
                        dep.groupId = currentGroupId;
                        dep.artifactId = currentArtifactId;
                        dep.version = currentVersion;
                        dep.scope = currentScope;
                        dep.optional = optional;
                        deps.add(dep);
                    }
                    inDep = false;
                    continue;
                }

                if (inDep) {
                    currentGroupId = extractTagContent(trimmed, "groupId", currentGroupId);
                    currentArtifactId = extractTagContent(trimmed, "artifactId", currentArtifactId);
                    currentVersion = extractTagContent(trimmed, "version", currentVersion);
                    currentScope = extractTagContent(trimmed, "scope", currentScope);
                    if (trimmed.contains("<optional>true</optional>")) {
                        optional = true;
                    }
                }
            }
        } catch (IOException e) {
            log.error("解析 pom.xml 依赖失败", e);
        }
        return deps;
    }

    /**
     * 简单提取 JSON 对象的键值对（dependencies / devDependencies 节）
     */
    private Map<String, String> extractJsonSection(String content, String sectionName) {
        Map<String, String> result = new LinkedHashMap<>();

        // 查找 section 开始位置
        String searchKey = "\"" + sectionName + "\"";
        int sectionStart = content.indexOf(searchKey);
        if (sectionStart < 0) return result;

        int braceStart = content.indexOf('{', sectionStart);
        if (braceStart < 0) return result;

        int braceDepth = 0;
        int keyStart = -1;
        String currentKey = null;

        for (int i = braceStart; i < content.length() && result.size() < MAX_DEPENDENCIES; i++) {
            char c = content.charAt(i);
            switch (c) {
                case '{' -> braceDepth++;
                case '}' -> {
                    braceDepth--;
                    if (braceDepth == 0) return result;
                }
            }

            if (braceDepth == 1) {
                // 顶层键值对
                if (c == '"' && keyStart < 0) {
                    keyStart = i + 1;
                } else if (c == '"' && keyStart > 0) {
                    if (currentKey == null) {
                        currentKey = content.substring(keyStart, i);
                        keyStart = -1;
                    } else {
                        // 读取值
                        String value = content.substring(keyStart, i);
                        result.put(currentKey, value);
                        currentKey = null;
                        keyStart = -1;
                    }
                }
            }
        }

        return result;
    }

    /**
     * 提取 JSON 顶层字符串值（如 name, version）
     */
    private String extractJsonValue(String content, String key) {
        String search = "\"" + key + "\"\\s*:\\s*\"";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(search).matcher(content);
        if (m.find()) {
            int start = m.end();
            int end = content.indexOf('"', start);
            if (end > start) {
                return content.substring(start, end);
            }
        }
        return null;
    }

    /**
     * 提取 XML 标签内容
     */
    private String extractTagContent(String line, String tag, String current) {
        String openTag = "<" + tag + ">";
        String closeTag = "</" + tag + ">";
        int start = line.indexOf(openTag);
        if (start >= 0) {
            int end = line.indexOf(closeTag, start);
            if (end > start) {
                return line.substring(start + openTag.length(), end).trim();
            }
        }
        return current;
    }

    private void appendNpmSection(StringBuilder sb, String title, Map<String, String> deps,
                                  String filterKey, String filter) {
        if (!filter.isEmpty() && !filter.equalsIgnoreCase(filterKey)) return;
        if (deps.isEmpty()) return;

        sb.append("【").append(title).append("】（").append(deps.size()).append(" 个）\n");
        for (Map.Entry<String, String> entry : deps.entrySet()) {
            sb.append("  ").append(entry.getKey()).append(": \"").append(entry.getValue()).append("\"\n");
        }
        sb.append("\n");
    }

    private Path resolvePath(String pathStr) {
        Path path = Paths.get(pathStr);
        if (path.isAbsolute()) return path.normalize();
        return Paths.get(ProjectRootContext.get(), pathStr).normalize();
    }

    // ===== 内部数据类 =====

    private static class MavenDep {
        String groupId;
        String artifactId;
        String version;
        String scope;
        boolean optional;
    }
}
