package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.util.ProjectRootContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

@Slf4j
@Component
public class ProjectInfoTool implements Tool {

    private static final int MAX_MODULES = 50;
    private static final int MAX_DEPENDENCIES = 100;

    private final ObjectMapper objectMapper;

    public ProjectInfoTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "project_info";
    }

    @Override
    public String getDescription() {
        return "项目信息查询工具：解析 Maven (pom.xml) 或 npm (package.json) 项目信息。"
                + "支持三种范围：structure（模块层次结构）、dependencies（依赖列表）、all（两者）。"
                + "适用于了解项目组织结构和第三方库依赖";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode path = objectMapper.createObjectNode();
        path.put("type", "string");
        path.put("description", "项目目录路径（可选），默认为当前项目根目录");
        properties.set("path", path);

        ObjectNode scope = objectMapper.createObjectNode();
        scope.put("type", "string");
        scope.put("description", "查询范围：structure（模块结构）、dependencies（依赖列表）、all（两者）");
        scope.putArray("enum").add("structure").add("dependencies").add("all");
        properties.set("scope", scope);

        ObjectNode filter = objectMapper.createObjectNode();
        filter.put("type", "string");
        filter.put("description", "依赖过滤（可选，仅 dependencies/all 时有效），如 compile/test 只显示某 scope 的 Maven 依赖，或 prod/dev 过滤 npm 依赖");
        properties.set("filter", filter);

        parameters.set("properties", properties);
        parameters.putArray("required").add("scope");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String scope = arguments.path("scope").asText("");
        String path = arguments.path("path").asText("");
        String filter = arguments.path("filter").asText("");

        if (scope.isEmpty()) {
            return "错误：缺少必要参数 scope（可选值：structure / dependencies / all）";
        }

        switch (scope) {
            case "structure":
                return getModuleStructure(path);
            case "dependencies":
                return getDependencies(path, filter);
            case "all":
                return getAllInfo(path, filter);
            default:
                return "错误：不支持的 scope 值 '" + scope + "'，可选值：structure / dependencies / all";
        }
    }

    // ============================================================
    // 模块结构 (scope=structure)
    // ============================================================

    private String getModuleStructure(String pathStr) {
        Path pomPath = locatePom(pathStr);
        if (pomPath == null) {
            return "错误：未找到 pom.xml 文件" + (pathStr.isEmpty() ? "（当前目录无 pom.xml）" : " - " + pathStr);
        }
        try {
            PomInfo rootPom = parsePom(pomPath);
            return buildStructureOutput(pomPath, rootPom);
        } catch (Exception e) {
            log.error("解析 pom.xml 失败", e);
            return "错误：解析 pom.xml 失败 - " + e.getMessage();
        }
    }

    /**
     * 使用已解析的 PomInfo 构建模块结构输出（用于 scope=all 时共享解析结果）
     */
    private String buildStructureOutput(Path pomPath, PomInfo rootPom) {
        List<PomInfo> allModules = new ArrayList<>();
        allModules.add(rootPom);

        if (rootPom.modules != null && !rootPom.modules.isEmpty()) {
            for (String module : rootPom.modules) {
                if (allModules.size() >= MAX_MODULES) break;
                Path modulePom = pomPath.resolveSibling(module).resolve("pom.xml").normalize();
                if (Files.exists(modulePom)) {
                    try {
                        PomInfo child = parsePom(modulePom);
                        child.moduleName = module;
                        child.parentArtifactId = rootPom.artifactId;
                        allModules.add(child);
                    } catch (Exception e) {
                        log.debug("解析子模块失败: {}", modulePom, e);
                    }
                }
            }
        }
        return formatStructureOutput(pomPath, rootPom, allModules);
    }

    // ============================================================
    // 依赖列表 (scope=dependencies)
    // ============================================================

    private String getDependencies(String pathStr, String filter) {
        Path projectDir = resolveProjectDir(pathStr);
        Path pomXml = projectDir.resolve("pom.xml");
        Path packageJson = projectDir.resolve("package.json");
        boolean hasMaven = Files.exists(pomXml);
        boolean hasNpm = Files.exists(packageJson);

        if (hasMaven && hasNpm) {
            return formatDualDeps(projectDir, pomXml, packageJson, filter);
        } else if (hasMaven) {
            return formatMavenDeps(projectDir, pomXml, filter, true);
        } else if (hasNpm) {
            return formatNpmDeps(projectDir, packageJson, filter, true);
        } else {
            return "错误：未找到 pom.xml 或 package.json，无法读取依赖信息";
        }
    }

    // ============================================================
    // 全部信息 (scope=all)
    // ============================================================

    private String getAllInfo(String pathStr, String filter) {
        Path pomPath = locatePom(pathStr);
        PomInfo rootPom = null;

        // 【P1-4】解析一次，共享给结构和依赖两个输出方法
        if (pomPath != null) {
            try {
                rootPom = parsePom(pomPath);
            } catch (Exception e) {
                return "错误：解析 pom.xml 失败 - " + e.getMessage();
            }
        }

        // 构建结构部分
        String structureResult;
        if (rootPom != null) {
            structureResult = buildStructureOutput(pomPath, rootPom);
        } else {
            structureResult = getModuleStructure(pathStr);
        }

        // 【P1-6】错误短路：结构解析失败则不继续
        if (structureResult.startsWith("错误：")) {
            return structureResult;
        }

        StringBuilder result = new StringBuilder();
        result.append("【模块结构】\n");
        result.append(structureResult);
        result.append("\n\n");
        result.append("【依赖列表】\n");

        // 依赖部分复用已解析的 PomInfo
        if (rootPom != null) {
            result.append(buildMavenDepsOutput(pomPath.getParent(), rootPom, filter, false));
        } else {
            result.append(getDependencies(pathStr, filter));
        }

        return result.toString();
    }

    // ============================================================
    // StAX Maven pom.xml 解析
    // ============================================================

    private PomInfo parsePom(Path pomPath) throws Exception {
        PomInfo pom = new PomInfo();
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);

        try (InputStream is = Files.newInputStream(pomPath)) {
            XMLStreamReader reader = factory.createXMLStreamReader(is, StandardCharsets.UTF_8.name());
            try {
                Deque<String> tagStack = new ArrayDeque<>();
                Dependency currentDep = null;
                boolean inParent = false;
                boolean inDependencies = false;
                boolean inDependencyManagement = false;  // 【P1-5】
                boolean inModules = false;

                while (reader.hasNext()) {
                    int event = reader.next();
                    switch (event) {
                        case XMLStreamConstants.START_ELEMENT -> {
                            String tag = reader.getLocalName();
                            tagStack.push(tag);
                            if ("parent".equals(tag)) {
                                inParent = true;
                            } else if ("dependencyManagement".equals(tag)) {
                                inDependencyManagement = true;
                            } else if ("dependencies".equals(tag)) {
                                inDependencies = true;
                            } else if ("dependency".equals(tag)) {
                                currentDep = new Dependency();
                            } else if ("modules".equals(tag)) {
                                inModules = true;
                            }
                        }
                        case XMLStreamConstants.CHARACTERS -> {
                            if (tagStack.isEmpty()) break;
                            String text = reader.getText().trim();
                            if (text.isEmpty()) break;
                            String currentTag = tagStack.peek();

                            if (inParent) {
                                switch (currentTag) {
                                    case "groupId" -> pom.parentGroupId = text;
                                    case "artifactId" -> pom.parentArtifactId = text;
                                    case "version" -> pom.parentVersion = text;
                                }
                            } else if (currentDep != null) {
                                switch (currentTag) {
                                    case "groupId" -> currentDep.groupId = text;
                                    case "artifactId" -> currentDep.artifactId = text;
                                    case "version" -> currentDep.version = text;
                                    case "scope" -> currentDep.scope = text;
                                    case "optional" -> currentDep.optional = "true".equals(text);
                                }
                            } else if (inModules && "module".equals(currentTag)) {
                                if (pom.modules == null) pom.modules = new ArrayList<>();
                                pom.modules.add(text);
                            } else {
                                switch (currentTag) {
                                    case "groupId" -> pom.groupId = text;
                                    case "artifactId" -> pom.artifactId = text;
                                    case "version" -> pom.version = text;
                                    case "packaging" -> pom.packaging = text;
                                    case "name" -> pom.name = text;
                                }
                                String[] stack = tagStack.toArray(new String[0]);
                                if (stack.length >= 2 && "properties".equals(stack[stack.length - 1])) {
                                    if (pom.properties == null) pom.properties = new LinkedHashMap<>();
                                    pom.properties.put(currentTag, text);
                                }
                            }
                        }
                        case XMLStreamConstants.END_ELEMENT -> {
                            String tag = reader.getLocalName();
                            if (!tagStack.isEmpty()) tagStack.pop();
                            if ("parent".equals(tag)) {
                                inParent = false;
                            } else if ("dependency".equals(tag) && currentDep != null) {
                                if (currentDep.groupId != null && currentDep.artifactId != null) {
                                    List<Dependency> targetList;
                                    if (inDependencyManagement) {
                                        if (pom.managedDependencies == null) {
                                            pom.managedDependencies = new ArrayList<>();
                                        }
                                        targetList = pom.managedDependencies;
                                    } else if (inDependencies) {
                                        if (pom.dependencies == null) {
                                            pom.dependencies = new ArrayList<>();
                                        }
                                        targetList = pom.dependencies;
                                    } else {
                                        targetList = null;
                                    }
                                    if (targetList != null) {
                                        targetList.add(currentDep);
                                    }
                                }
                                currentDep = null;
                            } else if ("dependencies".equals(tag)) {
                                inDependencies = false;
                            } else if ("dependencyManagement".equals(tag)) {
                                inDependencyManagement = false;
                            } else if ("modules".equals(tag)) {
                                inModules = false;
                            }
                        }
                    }
                }
            } finally {
                // 【P0-3】确保 XMLStreamReader 在异常时也能关闭
                reader.close();
            }
        }
        return pom;
    }

    // ============================================================
    // Maven 模块结构输出
    // ============================================================

    private String formatStructureOutput(Path pomPath, PomInfo rootPom, List<PomInfo> allModules) {
        StringBuilder sb = new StringBuilder();
        sb.append("Maven 项目：").append(rootPom.artifactId).append("\n");
        if (rootPom.name != null && !rootPom.name.equals(rootPom.artifactId)) {
            sb.append("项目名称：").append(rootPom.name).append("\n");
        }
        sb.append("文件路径：").append(pomPath.toAbsolutePath()).append("\n");
        sb.append("────────────────────────────────────────\n");

        sb.append("【模块层次】\n");
        for (PomInfo pom : allModules) {
            if (pom == rootPom) {
                sb.append("  ").append(pom.artifactId);
                if (pom.version != null) sb.append(" (").append(pom.version).append(")");
                if (pom.packaging != null && !"jar".equals(pom.packaging)) {
                    sb.append(" [").append(pom.packaging).append("]");
                }
                sb.append("\n");

                if (pom.modules != null && !pom.modules.isEmpty()) {
                    for (String module : pom.modules) {
                        PomInfo child = allModules.stream()
                                .filter(m -> module.equals(m.moduleName))
                                .findFirst().orElse(null);
                        if (child != null) {
                            sb.append("  ├── ").append(child.artifactId);
                            if (child.version != null && !child.version.equals(rootPom.version)) {
                                sb.append(" (").append(child.version).append(")");
                            }
                            if (child.packaging != null && !"jar".equals(child.packaging)) {
                                sb.append(" [").append(child.packaging).append("]");
                            }
                            sb.append("\n");
                        } else {
                            sb.append("  ├── ").append(module).append("（未解析）\n");
                        }
                    }
                }
            }
        }

        if (rootPom.parentArtifactId != null) {
            sb.append("\n【父项目】\n");
            sb.append("  ").append(rootPom.parentGroupId).append(":")
                    .append(rootPom.parentArtifactId).append(":")
                    .append(rootPom.parentVersion).append("\n");
        }

        if (rootPom.properties != null && !rootPom.properties.isEmpty()) {
            sb.append("\n【关键属性】\n");
            rootPom.properties.forEach((k, v) -> sb.append("  ").append(k).append(" = ").append(v).append("\n"));
        }

        return sb.toString();
    }

    // ============================================================
    // Maven 依赖输出（支持 includeHeader 控制 + dependencyManagement）
    // ============================================================

    /**
     * 对外入口：解析 pom.xml 并输出（含项目头信息）
     */
    private String formatMavenDeps(Path projectDir, Path pomPath, String filter, boolean includeHeader) {
        try {
            PomInfo pom = parsePom(pomPath);
            return buildMavenDepsOutput(projectDir, pom, filter, includeHeader);
        } catch (Exception e) {
            log.error("解析 Maven 依赖失败", e);
            return "错误：解析 Maven 依赖失败 - " + e.getMessage();
        }
    }

    /**
     * 使用已解析的 PomInfo 构建 Maven 依赖输出（用于 scope=all 共享解析结果）
     */
    private String buildMavenDepsOutput(Path projectDir, PomInfo pom, String filter, boolean includeHeader) {
        List<Dependency> allDeps = pom.dependencies != null ? pom.dependencies : Collections.emptyList();

        // 构建受管依赖查找表，用于解析 ${...} 版本占位符
        Map<String, String> managedVersionMap = buildManagedVersionMap(pom);

        StringBuilder sb = new StringBuilder();

        // 【P0-2】includeHeader 控制是否输出项目元信息行
        if (includeHeader) {
            sb.append("项目：").append(projectDir.toAbsolutePath()).append("\n");
        }
        sb.append("类型：Maven（pom.xml）\n");
        sb.append("────────────────────────────────────────\n");

        if (allDeps.isEmpty() && (pom.managedDependencies == null || pom.managedDependencies.isEmpty())) {
            sb.append("（pom.xml 中未定义依赖）");
            return sb.toString();
        }

        // 按 scope 分组过滤
        Map<String, List<Dependency>> grouped = new LinkedHashMap<>();
        for (Dependency dep : allDeps) {
            String scope = dep.scope != null ? dep.scope : "compile";
            if (filter.isEmpty() || scope.equalsIgnoreCase(filter)) {
                grouped.computeIfAbsent(scope, k -> new ArrayList<>()).add(dep);
            }
        }

        int total = allDeps.size();
        int shown = grouped.values().stream().mapToInt(List::size).sum();
        sb.append("共 ").append(total).append(" 个直接依赖")
                .append(shown < total ? "（显示 " + shown + " 个）" : "").append("\n\n");

        if (grouped.isEmpty() && !filter.isEmpty()) {
            sb.append("未找到匹配过滤条件 \"").append(filter).append("\" 的依赖\n");
        }

        for (Map.Entry<String, List<Dependency>> entry : grouped.entrySet()) {
            List<Dependency> deps = entry.getValue();
            sb.append("【").append(entry.getKey()).append(" 依赖】（").append(deps.size()).append(" 个）\n");
            for (Dependency dep : deps) {
                sb.append("  ").append(dep.groupId).append(":").append(dep.artifactId);
                String resolvedVersion = resolveVersion(dep.version, managedVersionMap, pom.properties);
                if (resolvedVersion != null) {
                    sb.append(":").append(resolvedVersion);
                }
                if (dep.optional) {
                    sb.append(" [optional]");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        // 【P1-5】显示 dependencyManagement 中的受管依赖
        if (pom.managedDependencies != null && !pom.managedDependencies.isEmpty()) {
            sb.append("【dependencyManagement 受管版本】（").append(pom.managedDependencies.size()).append(" 个）\n");
            for (Dependency dep : pom.managedDependencies) {
                sb.append("  ").append(dep.groupId).append(":").append(dep.artifactId);
                if (dep.version != null) {
                    sb.append(":").append(dep.version);
                }
                if (dep.scope != null && !"compile".equals(dep.scope)) {
                    sb.append(" [").append(dep.scope).append("]");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 构建受管依赖的 groupId:artifactId → version 映射表
     */
    private Map<String, String> buildManagedVersionMap(PomInfo pom) {
        Map<String, String> map = new LinkedHashMap<>();
        if (pom.managedDependencies != null) {
            for (Dependency dep : pom.managedDependencies) {
                if (dep.groupId != null && dep.artifactId != null && dep.version != null) {
                    map.put(dep.groupId + ":" + dep.artifactId, dep.version);
                }
            }
        }
        return map;
    }

    /**
     * 解析依赖版本：如果版本是 ${...} 占位符，尝试从受管依赖映射和 properties 中解析真实值
     */
    private String resolveVersion(String version, Map<String, String> managedVersionMap, Map<String, String> properties) {
        if (version == null) return null;
        // 非占位符版本直接返回
        if (!version.startsWith("${") || !version.endsWith("}")) {
            return version;
        }
        String key = version.substring(2, version.length() - 1);
        // 先查项目属性
        if (properties != null && properties.containsKey(key)) {
            return properties.get(key);
        }
        // 如果是 project.version 则无法通过 managedVersionMap 解析，返回占位符原文
        if ("project.version".equals(key)) {
            return version;
        }
        // 占位符无法解析则返回原文（比直接丢弃信息好）
        return version;
    }

    // ============================================================
    // npm 依赖解析与输出
    // ============================================================

    /**
     * 对外入口：解析 package.json 并输出
     */
    private String formatNpmDeps(Path projectDir, Path packagePath, String filter, boolean includeHeader) {
        try {
            String content = Files.readString(packagePath, StandardCharsets.UTF_8);
            // 【P0-1】使用 Jackson 替代手写 JSON 解析
            Map<String, String> prodDeps = extractJsonSection(content, "dependencies");
            Map<String, String> devDeps = extractJsonSection(content, "devDependencies");
            Map<String, String> peerDeps = extractJsonSection(content, "peerDependencies");

            String projectName = extractJsonValue(content, "name");
            String projectVersion = extractJsonValue(content, "version");

            return buildNpmDepsOutput(projectDir, projectName, projectVersion,
                    prodDeps, devDeps, peerDeps, filter, includeHeader);
        } catch (IOException e) {
            return "错误：读取 package.json 失败 - " + e.getMessage();
        }
    }

    /**
     * 构建 npm 依赖输出字符串
     */
    private String buildNpmDepsOutput(Path projectDir, String projectName, String projectVersion,
                                       Map<String, String> prodDeps, Map<String, String> devDeps,
                                       Map<String, String> peerDeps, String filter, boolean includeHeader) {
        if (prodDeps.isEmpty() && devDeps.isEmpty() && peerDeps.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            if (includeHeader) {
                sb.append("项目：").append(projectDir.toAbsolutePath()).append("\n");
            }
            sb.append("类型：npm（package.json）\n");
            sb.append("────────────────────────────────────────\n");
            sb.append("（package.json 中未定义依赖）");
            return sb.toString();
        }

        StringBuilder sb = new StringBuilder();
        if (includeHeader) {
            sb.append("项目：").append(projectDir.toAbsolutePath()).append("\n");
        }
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
    }

    // ============================================================
    // 双项目模式（Maven + npm 混合）
    // ============================================================

    private String formatDualDeps(Path projectDir, Path pomPath, Path packagePath, String filter) {
        StringBuilder sb = new StringBuilder();
        sb.append("项目：").append(projectDir.toAbsolutePath()).append("\n");
        sb.append("类型：Maven + npm 混合项目\n");
        sb.append("────────────────────────────────────────\n\n");

        // 【P0-2】通过 includeHeader=false 让子方法跳过重复的项目头信息
        String mavenPart = formatMavenDeps(projectDir, pomPath, filter, false);
        String npmPart = formatNpmDeps(projectDir, packagePath, filter, false);

        sb.append("--- Maven 依赖 ---\n").append(mavenPart).append("\n");
        sb.append("--- npm 依赖 ---\n").append(npmPart);

        return sb.toString();
    }

    // ============================================================
    // JSON 解析工具方法（使用 Jackson，替代手写解析器）
    // ============================================================

    /**
     * 从 JSON 内容中提取指定 section 下的键值对（如 dependencies / devDependencies）
     * 【P0-1】使用 Jackson readTree 替代手写状态机解析
     */
    private Map<String, String> extractJsonSection(String content, String sectionName) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            JsonNode root = objectMapper.readTree(content);
            JsonNode section = root.path(sectionName);
            if (section.isMissingNode() || !section.isObject()) {
                return result;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = section.fields();
            while (fields.hasNext() && result.size() < MAX_DEPENDENCIES) {
                Map.Entry<String, JsonNode> entry = fields.next();
                result.put(entry.getKey(), entry.getValue().asText());
            }
        } catch (Exception e) {
            log.debug("解析 JSON section [{}] 失败，回退返回空结果", sectionName, e);
        }
        return result;
    }

    /**
     * 从 JSON 内容中提取指定 key 的字符串值（如 name / version）
     * 【P0-1】使用 Jackson readTree 替代正则 + indexOf
     */
    private String extractJsonValue(String content, String key) {
        try {
            JsonNode root = objectMapper.readTree(content);
            JsonNode value = root.path(key);
            if (value.isMissingNode()) return null;
            if (value.isTextual()) return value.asText();
            // 数字、布尔等非字符串值也正常返回
            return value.toString();
        } catch (Exception e) {
            return null;
        }
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

    // ============================================================
    // 文件路径解析
    // ============================================================

    private Path resolveProjectDir(String pathStr) {
        if (!pathStr.isEmpty()) {
            Path path = Paths.get(pathStr);
            if (!path.isAbsolute()) path = Paths.get(ProjectRootContext.get(), pathStr).normalize();
            if (Files.exists(path) && !Files.isDirectory(path)) {
                path = path.getParent();
            }
            return path;
        }
        return Paths.get(ProjectRootContext.get());
    }

    private Path locatePom(String pathStr) {
        if (pathStr.isEmpty()) {
            Path defaultPom = Paths.get(ProjectRootContext.get(), "pom.xml");
            if (Files.exists(defaultPom)) return defaultPom;
            return null;
        }
        Path given = resolvePath(pathStr);
        if (Files.isDirectory(given)) {
            Path pomInDir = given.resolve("pom.xml");
            if (Files.exists(pomInDir)) return pomInDir;
            return null;
        }
        if (Files.isRegularFile(given)) return given;
        return null;
    }

    private Path resolvePath(String pathStr) {
        Path path = Paths.get(pathStr);
        if (path.isAbsolute()) return path.normalize();
        return Paths.get(ProjectRootContext.get(), pathStr).normalize();
    }

    // ============================================================
    // 内部数据类
    // ============================================================

    private static class PomInfo {
        String groupId;
        String artifactId;
        String version;
        String packaging;
        String name;
        String parentGroupId;
        String parentArtifactId;
        String parentVersion;
        String moduleName;
        List<String> modules;
        Map<String, String> properties;
        List<Dependency> dependencies;
        /** 【P1-5】dependencyManagement 中定义的受管依赖 */
        List<Dependency> managedDependencies;
    }

    private static class Dependency {
        String groupId;
        String artifactId;
        String version;
        String scope;
        boolean optional;
    }
}
