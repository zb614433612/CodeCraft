package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.service.DataTransferService;
import com.example.agentdeepseek.service.UserService;
import com.example.agentdeepseek.service.llm.LLMClientManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 系统管理接口
 * <p>
 * 提供"重置所有数据"与"备份导出 / 导入"能力：重置清空所有用户业务数据（会话、消息、技能、
 * 定时任务、用户、LLM Provider 配置等）并恢复出厂基础数据，同时删除文件类数据
 * （快照、日志、P2P 接收文件、附件暂存）；导出/导入用于重装或跨设备迁移后快速恢复配置数据
 * （LLM 配置、智能体、技能、聊天记录、踩坑经验、MCP 服务器、数据库连接）。
 * <p>
 * 替代原先 electron 主进程基于 exe mtime 的"覆盖安装自动清理"方案：
 * 破坏性操作改为用户在设置页显式触发，杜绝误删。
 */
@Slf4j
@RestController
@RequestMapping("/api/system")
@Tag(name = "系统管理", description = "系统数据管理（重置 / 导出 / 导入）")
public class SystemController {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserService userService;

    @Autowired
    private LLMClientManager llmClientManager;

    @Autowired
    private DataTransferService dataTransferService;

    @Autowired
    private ObjectMapper objectMapper;

    /** 应用名（取自 spring.application.name，与前端品牌一致） */
    @Value("${spring.application.name:CodeCraft}")
    private String appName;

    /**
     * 版本号（application.yml 的 app.version，由 Maven 资源过滤注入 @project.version@）。
     * <p>
     * IDE 直接运行 / 资源未过滤时会拿到字面量 `@project.version@`，由 {@link #resolveAppVersion()} 兜底。
     * </p>
     */
    @Value("${app.version:}")
    private String appVersion;

    /** 构建时间（application.yml 的 app.build-time，Maven 资源过滤注入） */
    @Value("${app.build-time:}")
    private String appBuildTime;

    /** 版本号兜底值：资源过滤未生效时使用（与 pom.xml 的 &lt;version&gt; 保持一致） */
    private static final String FALLBACK_VERSION = "3.0.0";

    /** 需要清空的业务表（按外键依赖顺序：先子表后父表） */
    private static final String[] BUSINESS_TABLES = {
            "conversation_message",
            "conversation_compaction",
            "sub_agent_log",
            "conversation",
            "agent_task",
            "schedule_task",
            "skill",
            "llm_provider",
            "agent_config",
            "sys_config",
            "sys_user",
            "p2p_chat_message",
            "p2p_agent_authorization",
            "p2p_agent_conversation"
    };

    /**
     * 重置所有用户数据（仅管理员）
     * <p>
     * 保留系统基础表 sys_role / sys_menu / sys_role_menu（菜单权限体系，由 schema.sql 维护）。
     * 业务表清空后立即重建出厂基础数据（默认管理员、内置 AI 助手，与 schema.sql 初始化一致），
     * 因此无需重启应用即可恢复可用状态。
     * <p>
     * 整个重置过程在一个事务内执行：任一步失败则整体回滚，
     * 避免"表已清空但基础数据未重建"的半重置状态。
     */
    @PostMapping("/reset-data")
    @Transactional
    @Operation(summary = "重置所有用户数据", description = "清空会话、消息、技能、定时任务、用户、LLM Provider 等全部业务数据并恢复出厂基础数据；同时删除快照、日志、P2P 接收文件、附件暂存目录。危险操作，需管理员权限。")
    public ApiResponse<Void> resetData(HttpServletRequest request) {
        checkAdmin(request);
        log.warn("收到重置所有数据的请求，由用户 {} 触发", request.getAttribute("username"));

        // 1. 清空业务表
        for (String table : BUSINESS_TABLES) {
            jdbcTemplate.execute("DELETE FROM " + table);
            // 重置自增主键，恢复出厂 ID 起点
            jdbcTemplate.execute("ALTER TABLE " + table + " ALTER COLUMN id RESTART WITH 1");
        }

        // 2. 重建出厂基础数据（与 schema.sql 的 INSERT IGNORE 初始化保持一致）
        userService.createDefaultAdminIfNotExists();
        // 内置默认 AI 助手（schema.sql 同款：id=1 内置不可删改，provider_id=1 由用户创建 Provider 后绑定）
        jdbcTemplate.execute("INSERT INTO agent_config (id, name, description, avatar, system_prompt, tool_names, model_name, thinking_mode, execution_mode, temperature, work_dir, sort_order, enabled, is_default, is_builtin, provider_id, provider_code, character_profile, created_at, updated_at) "
                + "VALUES (1, 'AI 助手', '默认的AI编程助手，拥有全部工具', '🤖', NULL, NULL, 'deepseek-v4-flash', 'non-thinking', 'manual', 0.3, NULL, 1, 1, 1, 1, 1, NULL, NULL, NOW(), NOW())");

        // 3. 刷新 LLM Provider 内存缓存（清空已删除的 Provider 客户端，防止残留 API Key）
        try {
            llmClientManager.refreshClients();
        } catch (Exception e) {
            log.warn("重置后刷新 LLM Provider 缓存失败: {}", e.getMessage());
        }

        // 4. 删除文件类数据（尽力而为，失败仅告警不中断）
        deleteQuietly(Paths.get("snapshots"));
        deleteQuietly(Paths.get("data/p2p/received"));
        deleteQuietly(Paths.get(System.getProperty("java.io.tmpdir"), "codecraft-attachments"));
        deleteLogFiles();

        log.warn("所有数据已重置完成");
        return ApiResponse.success(null, "所有数据已重置，系统已恢复出厂默认状态");
    }

    // ==================== 版本信息 ====================

    /**
     * 解析后端版本号。
     * <p>
     * 三种来源按优先级：Maven 资源过滤注入的 {@code app.version} → 兜底常量。
     * 未替换的占位符（以 {@code @} 开头或含 {@code ${}}）视为无效，避免把 {@code @project.version@}
     * 原样显示到界面上。
     * </p>
     */
    private String resolveAppVersion() {
        String value = appVersion == null ? "" : appVersion.trim();
        if (value.isEmpty() || value.startsWith("@") || value.contains("${")) {
            return FALLBACK_VERSION;
        }
        return value;
    }

    /** 解析构建时间；未替换的占位符返回 null（前端不展示该行） */
    private String resolveBuildTime() {
        String value = appBuildTime == null ? "" : appBuildTime.trim();
        if (value.isEmpty() || value.startsWith("@") || value.contains("${")) {
            return null;
        }
        return value;
    }

    /**
     * 应用版本与运行环境信息（供「版本与更新」页展示）。
     * <p>
     * 与其它系统接口不同，本接口<b>不做管理员校验</b>：只返回版本号、构建时间与 JVM 运行环境，
     * 不含任何凭证或业务数据，普通用户也需要看到自己用的是什么版本。
     * </p>
     */
    @GetMapping("/version")
    @Operation(summary = "应用版本信息", description = "返回应用名、后端版本、构建时间与运行环境（Java / OS / PID）。任何登录用户可读，不含敏感数据。")
    public ApiResponse<Map<String, Object>> version() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", appName);
        info.put("version", resolveAppVersion());
        String buildTime = resolveBuildTime();
        if (buildTime != null) {
            info.put("buildTime", buildTime);
        }
        info.put("javaVersion", System.getProperty("java.version"));
        info.put("osName", System.getProperty("os.name"));
        info.put("osArch", System.getProperty("os.arch"));
        info.put("pid", ProcessHandle.current().pid());
        return ApiResponse.success(info);
    }

    // ==================== 数据导出 / 导入（备份与恢复） ====================

    /**
     * 数据统计：各类别当前数据量（行数），用于导出界面展示。
     */
    @GetMapping("/data-stats")
    @Operation(summary = "数据统计", description = "返回 7 个数据类别（LLM 配置/智能体/技能/聊天记录/踩坑经验/MCP 服务器/数据库连接）的当前行数，用于导出界面展示。")
    public ApiResponse<Map<String, Object>> dataStats(HttpServletRequest request) {
        checkAdmin(request);
        return ApiResponse.success(dataTransferService.stats());
    }

    /**
     * 导出备份：按类别导出为 JSON 备份文件（浏览器直接下载）。
     * <p>
     * types 为逗号分隔的类别 key（llmProvider/agent/skill/chat/lesson/mcp/dbConnection），
     * 缺省导出全部类别。备份文件包含 API Key、数据库密码密文等敏感信息（用于重装后免重新配置），
     * 前端已提示用户妥善保管。
     * </p>
     */
    @GetMapping("/export-data")
    @Operation(summary = "导出备份数据", description = "导出指定类别的数据为 JSON 备份文件。types 为逗号分隔的类别 key，缺省导出全部；文件含敏感凭证，请妥善保管。")
    public ResponseEntity<byte[]> exportData(@RequestParam(value = "types", required = false) String types,
                                             HttpServletRequest request) throws IOException {
        checkAdmin(request);
        List<String> categoryKeys = (types == null || types.isBlank()) ? List.of() : Arrays.asList(types.split(","));
        Map<String, Object> bundle = dataTransferService.exportData(categoryKeys);
        byte[] bytes = objectMapper.writeValueAsBytes(bundle);
        String filename = "codecraft-backup-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".json";
        log.info("数据导出完成：类别 [{}]，文件 {}（{} 字节）", types == null ? "全部" : types, filename, bytes.length);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                .body(bytes);
    }

    /**
     * 导入备份：上传 JSON 备份文件，按「同 id 覆盖更新 / 新 id 追加插入」合并导入（不清空现有数据）。
     * <p>
     * 导入完成后自动刷新 LLM Provider 内存缓存，使新导入的 Provider / API Key 立即生效；
     * 单行导入失败不中断整体，结果统计（新增/更新/失败）返回给前端展示。
     * </p>
     */
    @PostMapping("/import-data")
    @Operation(summary = "导入备份数据", description = "上传此前导出的 JSON 备份文件，一键恢复数据（合并策略：同 id 覆盖更新、新 id 追加，不清空现有数据）。导入后自动刷新 LLM Provider 缓存。")
    public ApiResponse<Map<String, Object>> importData(@RequestParam("file") MultipartFile file,
                                                       HttpServletRequest request) throws IOException {
        checkAdmin(request);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要导入的备份文件");
        }
        Map<String, Object> bundle = objectMapper.readValue(file.getBytes(), new TypeReference<Map<String, Object>>() {});
        Map<String, Object> result = dataTransferService.importData(bundle);

        // 导入的 LLM Provider / API Key 立即生效
        try {
            llmClientManager.refreshClients();
        } catch (Exception e) {
            log.warn("导入后刷新 LLM Provider 缓存失败: {}", e.getMessage());
        }

        String summary = "导入完成：新增 " + result.get("inserted") + " 条，更新 " + result.get("updated")
                + " 条，失败 " + result.get("failed") + " 条";
        log.info(summary);
        return ApiResponse.success(result, summary);
    }

    /**
     * 递归删除目录（含目录本身）
     */
    private void deleteQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("删除文件失败: {} ({})", p, e.getMessage());
                }
            });
            log.info("已删除目录: {}", dir.toAbsolutePath());
        } catch (IOException e) {
            log.warn("删除目录失败: {} ({})", dir, e.getMessage());
        }
    }

    /**
     * 删除日志文件（仅 .log 文件；日志文件可能正被 logback 占用，失败忽略）
     */
    private void deleteLogFiles() {
        String logPath = System.getProperty("LOG_PATH", "./logs");
        Path logDir = Paths.get(logPath);
        if (!Files.isDirectory(logDir)) {
            return;
        }
        try (Stream<Path> list = Files.list(logDir)) {
            list.filter(p -> p.toString().endsWith(".log"))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                            log.info("已删除日志文件: {}", p);
                        } catch (IOException e) {
                            log.warn("删除日志文件失败（可能被占用）: {} ({})", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("遍历日志目录失败: {} ({})", logDir, e.getMessage());
        }
    }

    private void checkAdmin(HttpServletRequest request) {
        String role = (String) request.getAttribute("userRole");
        if (!"admin".equals(role)) {
            throw new RuntimeException("权限不足，需要管理员权限");
        }
    }
}
