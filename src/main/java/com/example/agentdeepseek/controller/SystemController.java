package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.service.UserService;
import com.example.agentdeepseek.service.llm.LLMClientManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 系统管理接口
 * <p>
 * 提供"重置所有数据"能力：清空所有用户业务数据（会话、消息、技能、定时任务、
 * 用户、LLM Provider 配置等）并恢复出厂基础数据，同时删除文件类数据
 * （快照、日志、P2P 接收文件、附件暂存）。
 * <p>
 * 替代原先 electron 主进程基于 exe mtime 的"覆盖安装自动清理"方案：
 * 破坏性操作改为用户在设置页显式触发，杜绝误删。
 */
@Slf4j
@RestController
@RequestMapping("/api/system")
@Tag(name = "系统管理", description = "系统数据管理（重置）")
public class SystemController {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserService userService;

    @Autowired
    private LLMClientManager llmClientManager;

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
