package com.example.agentdeepseek.tool.impl;
import com.example.agentdeepseek.util.ProjectRootContext;

import com.example.agentdeepseek.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Maven 模块解析工具
 * 使用 StAX 流式解析 pom.xml，提取模块依赖关系
 */
@Slf4j
@Component
public class ReadMavenModuleTool implements Tool {

    private static final int MAX_MODULES = 50;

    private final ObjectMapper objectMapper;

    public ReadMavenModuleTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "read_maven_module";
    }

    @Override
    public String getDescription() {
        return "解析 pom.xml 文件，提取 Maven 模块层次结构、模块间依赖关系与关键属性（groupId/artifactId/version/packaging 等）。"
                + "支持递归扫描子模块和解析依赖列表。适用于了解 Maven 多模块项目的组织结构";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode path = objectMapper.createObjectNode();
        path.put("type", "string");
        path.put("description", "pom.xml 路径或项目根目录（可选），默认为项目根目录下的 pom.xml");
        properties.set("path", path);

        ObjectNode includeModules = objectMapper.createObjectNode();
        includeModules.put("type", "boolean");
        includeModules.put("description", "是否递归扫描子模块，默认 true");
        properties.set("include_modules", includeModules);

        ObjectNode includeDependencies = objectMapper.createObjectNode();
        includeDependencies.put("type", "boolean");
        includeDependencies.put("description", "是否解析依赖列表，默认 true");
        properties.set("include_dependencies", includeDependencies);

        parameters.set("properties", properties);
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String pathStr = arguments.path("path").asText();
        boolean includeModules = arguments.path("include_modules").asBoolean(true);
        boolean includeDeps = arguments.path("include_dependencies").asBoolean(true);

        // 定位 pom.xml
        Path pomPath = locatePom(pathStr);
        if (pomPath == null) {
            return "错误：未找到 pom.xml 文件" + (pathStr.isEmpty() ? "（当前目录无 pom.xml）" : " - " + pathStr);
        }
        if (!Files.exists(pomPath)) {
            return "错误：文件不存在 - " + pomPath.toAbsolutePath();
        }

        try {
            PomInfo rootPom = parsePom(pomPath);
            List<PomInfo> allModules = new ArrayList<>();
            allModules.add(rootPom);

            if (includeModules && rootPom.modules != null && !rootPom.modules.isEmpty()) {
                for (String module : rootPom.modules) {
                    if (allModules.size() >= MAX_MODULES) break;
                    Path modulePom = pomPath.resolveSibling(module).resolve("pom.xml").normalize();
                    if (Files.exists(modulePom)) {
                        try {
                            PomInfo childPom = parsePom(modulePom);
                            childPom.moduleName = module;
                            childPom.parentArtifactId = rootPom.artifactId;
                            allModules.add(childPom);
                        } catch (Exception e) {
                            log.debug("解析子模块失败: {}", modulePom, e);
                        }
                    }
                }
            }

            return formatOutput(pomPath, rootPom, allModules, includeDeps);
        } catch (Exception e) {
            log.error("解析 pom.xml 失败", e);
            return "错误：解析 pom.xml 失败 - " + e.getMessage();
        }
    }

    /**
     * 解析单个 pom.xml 文件（StAX 流式解析）
     */
    private PomInfo parsePom(Path pomPath) throws Exception {
        PomInfo pom = new PomInfo();

        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);

        try (InputStream is = Files.newInputStream(pomPath)) {
            XMLStreamReader reader = factory.createXMLStreamReader(is, StandardCharsets.UTF_8.name());

            // 当前正在解析的标签栈
            Deque<String> tagStack = new ArrayDeque<>();
            // 当前正在解析的依赖
            Dependency currentDep = null;
            // 当前正在解析的 parent 坐标
            String currentParentField = null;
            boolean inParent = false;
            boolean inDependencies = false;
            boolean inModules = false;

            while (reader.hasNext()) {
                int event = reader.next();
                switch (event) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        String tag = reader.getLocalName();
                        tagStack.push(tag);

                        if ("parent".equals(tag)) {
                            inParent = true;
                        } else if ("dependencies".equals(tag)) {
                            inDependencies = true;
                        } else if ("dependency".equals(tag) && inDependencies) {
                            currentDep = new Dependency();
                        } else if ("modules".equals(tag)) {
                            inModules = true;
                        }
                    }
                    case XMLStreamConstants.CHARACTERS -> {
                        if (!tagStack.isEmpty()) {
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
                                // 收集 properties
                                if (!tagStack.isEmpty()) {
                                    String[] stack = tagStack.toArray(new String[0]);
                                    if (stack.length >= 2 && "properties".equals(stack[stack.length - 1])) {
                                        if (pom.properties == null) pom.properties = new LinkedHashMap<>();
                                        pom.properties.put(currentTag, text);
                                    }
                                }
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
                                if (pom.dependencies == null) pom.dependencies = new ArrayList<>();
                                pom.dependencies.add(currentDep);
                            }
                            currentDep = null;
                        } else if ("dependencies".equals(tag)) {
                            inDependencies = false;
                        } else if ("modules".equals(tag)) {
                            inModules = false;
                        }
                    }
                }
            }
            reader.close();
        }

        return pom;
    }

    /**
     * 定位 pom.xml 文件
     */
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

    /**
     * 格式化输出
     */
    private String formatOutput(Path pomPath, PomInfo rootPom, List<PomInfo> allModules, boolean includeDeps) {
        StringBuilder sb = new StringBuilder();
        sb.append("Maven 项目：").append(rootPom.artifactId).append("\n");
        if (rootPom.name != null && !rootPom.name.equals(rootPom.artifactId)) {
            sb.append("项目名称：").append(rootPom.name).append("\n");
        }
        sb.append("文件路径：").append(pomPath.toAbsolutePath()).append("\n");
        sb.append("────────────────────────────────────────\n");

        // 模块层次结构
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

        // 继承信息
        if (rootPom.parentArtifactId != null) {
            sb.append("\n【父项目】\n");
            sb.append("  ").append(rootPom.parentGroupId).append(":")
                    .append(rootPom.parentArtifactId).append(":")
                    .append(rootPom.parentVersion).append("\n");
        }

        // 关键属性
        if (rootPom.properties != null && !rootPom.properties.isEmpty()) {
            sb.append("\n【关键属性】\n");
            rootPom.properties.forEach((k, v) -> sb.append("  ").append(k).append(" = ").append(v).append("\n"));
        }

        // 依赖列表
        if (includeDeps) {
            // 收集所有模块的依赖
            Map<String, List<Dependency>> depsByScope = new LinkedHashMap<>();
            for (PomInfo pom : allModules) {
                if (pom.dependencies != null) {
                    for (Dependency dep : pom.dependencies) {
                        String scope = dep.scope != null ? dep.scope : "compile";
                        depsByScope.computeIfAbsent(scope, k -> new ArrayList<>()).add(dep);
                    }
                }
            }

            if (!depsByScope.isEmpty()) {
                sb.append("\n【依赖列表】\n");
                for (Map.Entry<String, List<Dependency>> entry : depsByScope.entrySet()) {
                    sb.append("  ").append(entry.getKey()).append(" 依赖 (").append(entry.getValue().size()).append(" 个)\n");
                    for (Dependency dep : entry.getValue()) {
                        sb.append("    ├── ").append(dep.groupId).append(":").append(dep.artifactId);
                        if (dep.version != null && !dep.version.startsWith("${")) {
                            sb.append(":").append(dep.version);
                        }
                        sb.append("\n");
                    }
                }
            } else {
                sb.append("\n（无依赖信息）\n");
            }
        }

        return sb.toString();
    }

    private Path resolvePath(String pathStr) {
        Path path = Paths.get(pathStr);
        if (path.isAbsolute()) return path.normalize();
        return Paths.get(ProjectRootContext.get(), pathStr).normalize();
    }

    // ===== 内部数据类 =====

    private static class PomInfo {
        String groupId;
        String artifactId;
        String version;
        String packaging;
        String name;
        String parentGroupId;
        String parentArtifactId;
        String parentVersion;
        String moduleName; // 子模块的目录名
        List<String> modules;
        Map<String, String> properties;
        List<Dependency> dependencies;
    }

    private static class Dependency {
        String groupId;
        String artifactId;
        String version;
        String scope;
    }
}
