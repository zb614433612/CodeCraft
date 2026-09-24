package com.example.agentdeepseek;

import com.example.agentdeepseek.service.DataTransferService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 数据导入导出（备份/恢复）全链路回归测试（H2 内存库）
 * <p>
 * 覆盖链路：全量执行 schema.sql 建库 → 铺垫测试数据 → stats() 统计 → 部分/全量导出 →
 * JSON 往返（模拟真实文件读写）→ 清空业务表（模拟重装后空库）→ 一键导入 →
 * 行数与抽样一致性 → 导出→导入→再导出（除元数据外完全一致）→ 重复导入幂等 →
 * 含坏行的导入失败容错 → 自增序列修复（导入后可继续新增数据）→ 非法文件拒绝。
 * <p>
 * 运行：mvn -q -DskipTests -DskipFrontend=true test-compile && mvn -q
 * org.codehaus.mojo:exec-maven-plugin:3.1.0:java
 * -Dexec.mainClass=com.example.agentdeepseek.DataTransferLogicTest
 * -Dexec.classpathScope=test -DskipFrontend=true
 */
@SuppressWarnings("unchecked")
public class DataTransferLogicTest {

    static int passed = 0;
    static int failed = 0;

    /** H2 内存库（与生产同款参数：MySQL 兼容模式 + 标识符小写） */
    static final String URL = "jdbc:h2:mem:dataTransferTest;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1";

    /** 导出覆盖的 10 张表（行数基线对比用） */
    static final List<String> ALL_TABLES = List.of(
            "llm_provider", "agent_config", "skill",
            "conversation", "conversation_message", "conversation_compaction",
            "lesson", "lesson_rule", "mcp_server", "db_connection");

    /** 清空顺序：子表先于父表（conversation_message / conversation_compaction 有指向 conversation 的外键） */
    static final List<String> CLEAR_ORDER = List.of(
            "conversation_message", "conversation_compaction", "conversation",
            "lesson_rule", "lesson", "skill", "mcp_server", "db_connection",
            "llm_provider", "agent_config");

    public static void main(String[] args) throws Exception {
        System.out.println("═══════════════════════════════════════════");
        System.out.println("  数据导入导出（备份/恢复）回归测试（H2 内存库）");
        System.out.println("  导出 → 清空 → 导入 → 一致性 → 幂等 → 序列修复");
        System.out.println("═══════════════════════════════════════════\n");

        // ========== 建库：全量执行 schema.sql（复刻应用启动 sql.init） ==========
        try (Connection conn = DriverManager.getConnection(URL, "sa", "")) {
            runSchema(conn);
        }
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(URL, "sa", ""));
        DataTransferService service = new DataTransferService(jdbc);
        ObjectMapper mapper = new ObjectMapper();

        // 清空 schema.sql 的预置数据（如内置 agent_config id=1），保证测试数据完全受控
        for (String table : CLEAR_ORDER) {
            jdbc.update("DELETE FROM " + table);
        }

        // ========== 1. 铺垫测试数据 ==========
        System.out.println("---- 第 1 步：铺垫测试数据（10 张表共 19 行）----");
        seedData(jdbc);
        long seeded = 0;
        for (String t : ALL_TABLES) {
            seeded += countOf(jdbc, t);
        }
        assertEq("铺垫数据总行数 = 19", 19L, seeded);

        // ========== 2. stats() 数据统计 ==========
        System.out.println("\n---- 第 2 步：stats() 数据统计 ----");
        Map<String, Object> stats = service.stats();
        assertEq("stats: LLM 配置 = 2", 2L, ((Number) stats.get("llmProvider")).longValue());
        assertEq("stats: 智能体 = 2", 2L, ((Number) stats.get("agent")).longValue());
        assertEq("stats: 技能 = 2", 2L, ((Number) stats.get("skill")).longValue());
        assertEq("stats: 聊天记录 = 6（2 会话 + 3 消息 + 1 压缩）", 6L, ((Number) stats.get("chat")).longValue());
        assertEq("stats: 踩坑经验 = 4（2 经验 + 2 规则）", 4L, ((Number) stats.get("lesson")).longValue());
        assertEq("stats: MCP 服务器 = 2", 2L, ((Number) stats.get("mcp")).longValue());
        assertEq("stats: 数据库连接 = 1", 1L, ((Number) stats.get("dbConnection")).longValue());

        // ========== 3. 部分类别导出 ==========
        System.out.println("\n---- 第 3 步：部分类别导出（含大小写/空格容错、未知类别）----");
        Map<String, Object> partial = service.exportData(Arrays.asList(" agent ", "SKILL"));
        Map<String, Object> partialCategories = (Map<String, Object>) partial.get("categories");
        assertEq("部分导出：类别数 = 2", 2, partialCategories.size());
        assertTrue("部分导出：含 agent", partialCategories.containsKey("agent"));
        assertTrue("部分导出：含 skill", partialCategories.containsKey("skill"));
        assertTrue("部分导出：不含 chat", !partialCategories.containsKey("chat"));
        assertEq("部分导出：agent_config 行数 = 2", 2, rowsOf(partial, "agent", "agent_config").size());
        assertEq("部分导出：skill 行数 = 2", 2, rowsOf(partial, "skill", "skill").size());
        assertEq("未知类别导出：categories 为空", 0,
                ((Map<?, ?>) service.exportData(List.of("not-exist")).get("categories")).size());

        // ========== 4. 全量导出 + JSON 往返（模拟真实文件读写） ==========
        System.out.println("\n---- 第 4 步：全量导出 + JSON 往返 ----");
        Map<String, Object> bundle1 = service.exportData(null);
        String json1 = mapper.writeValueAsString(bundle1);
        Map<String, Object> reloaded = mapper.readValue(json1, new TypeReference<Map<String, Object>>() {
        });
        assertEq("备份标识 app", "CodeCraft", reloaded.get("app"));
        assertEq("格式版本 formatVersion = 1", 1, ((Number) reloaded.get("formatVersion")).intValue());
        assertEq("全量导出：类别数 = 7", 7, ((Map<?, ?>) reloaded.get("categories")).size());
        assertTrue("导出内容不含缓存表 lesson_norm_cache", !json1.contains("lesson_norm_cache"));

        List<Map<String, Object>> providerRows = rowsOf(reloaded, "llmProvider", "llm_provider");
        Object createdAt = providerRows.get(0).get("created_at");
        assertTrue("时间字段导出为字符串（" + createdAt + "）", createdAt instanceof String);
        assertTrue("时间字符串可被 Timestamp.valueOf 解析", parseOk((String) createdAt));
        assertTrue("TINYINT(1) 布尔字段值为 Boolean 或数字",
                providerRows.get(1).get("enabled") instanceof Boolean || providerRows.get(1).get("enabled") instanceof Number);

        // ========== 5. 记录基线（行数 + 抽样行内容） ==========
        System.out.println("\n---- 第 5 步：记录数据基线 ----");
        Map<String, Long> baseline = tableCounts(jdbc);
        assertEq("基线：llm_provider = 2", 2L, baseline.get("llm_provider"));
        assertEq("基线：conversation_message = 3", 3L, baseline.get("conversation_message"));
        assertEq("基线：lesson = 2", 2L, baseline.get("lesson"));
        String sampleMessage = jdbc.queryForObject("SELECT content FROM conversation_message WHERE id = 2", String.class);
        String sampleApiKey = jdbc.queryForObject("SELECT api_key FROM llm_provider WHERE id = 2", String.class);
        String sampleSummary = jdbc.queryForObject("SELECT summary FROM conversation_compaction WHERE id = 1", String.class);
        String sampleParams = jdbc.queryForObject("SELECT params_json FROM lesson WHERE id = 1", String.class);
        String sampleHeaders = jdbc.queryForObject("SELECT headers FROM mcp_server WHERE id = 1", String.class);
        String samplePassword = jdbc.queryForObject("SELECT password_encrypted FROM db_connection WHERE id = 1", String.class);

        // ========== 6. 清空业务表（模拟重装后的空库） ==========
        System.out.println("\n---- 第 6 步：清空业务表（模拟重装后的空库）----");
        for (String table : CLEAR_ORDER) {
            jdbc.update("DELETE FROM " + table);
        }
        assertEq("清空后 llm_provider = 0", 0L, countOf(jdbc, "llm_provider"));
        assertEq("清空后 conversation_message = 0", 0L, countOf(jdbc, "conversation_message"));

        // ========== 7. 一键导入 ==========
        System.out.println("\n---- 第 7 步：一键导入（清空后的库）----");
        Map<String, Object> result = service.importData(reloaded);
        assertEq("导入：新增 = 19", 19L, ((Number) result.get("inserted")).longValue());
        assertEq("导入：更新 = 0", 0L, ((Number) result.get("updated")).longValue());
        assertEq("导入：失败 = 0", 0L, ((Number) result.get("failed")).longValue());
        assertEq("导入明细：类别数 = 7", 7, ((List<?>) result.get("details")).size());

        // ========== 8. 导入后一致性：行数 + 抽样 ==========
        System.out.println("\n---- 第 8 步：导入后一致性检查 ----");
        Map<String, Long> afterImport = tableCounts(jdbc);
        for (String table : ALL_TABLES) {
            assertEq("行数一致 [" + table + "]", baseline.get(table), afterImport.get(table));
        }
        assertEq("抽样：消息内容一致（含换行/特殊字符）", sampleMessage,
                jdbc.queryForObject("SELECT content FROM conversation_message WHERE id = 2", String.class));
        assertEq("抽样：API Key 一致（含中文）", sampleApiKey,
                jdbc.queryForObject("SELECT api_key FROM llm_provider WHERE id = 2", String.class));
        assertEq("抽样：压缩摘要一致", sampleSummary,
                jdbc.queryForObject("SELECT summary FROM conversation_compaction WHERE id = 1", String.class));
        assertEq("抽样：经验变量 JSON 一致", sampleParams,
                jdbc.queryForObject("SELECT params_json FROM lesson WHERE id = 1", String.class));
        assertEq("抽样：MCP headers 一致", sampleHeaders,
                jdbc.queryForObject("SELECT headers FROM mcp_server WHERE id = 1", String.class));
        assertEq("抽样：数据库密码密文一致", samplePassword,
                jdbc.queryForObject("SELECT password_encrypted FROM db_connection WHERE id = 1", String.class));
        assertEq("时间字段往返一致（毫秒精度）", Timestamp.valueOf("2026-05-01 10:20:30.123"),
                jdbc.queryForObject("SELECT created_at FROM llm_provider WHERE id = 1", Timestamp.class));
        assertEq("null 字段往返保持 null（reasoning）", null,
                jdbc.queryForObject("SELECT reasoning FROM conversation_message WHERE id = 1", String.class));

        // ========== 9. 再导出 → 与首次导出完全一致（除 exportedAt） ==========
        System.out.println("\n---- 第 9 步：导出→导入→再导出（内容完全一致）----");
        Map<String, Object> bundle2 = service.exportData(null);
        bundle1.remove("exportedAt");
        bundle2.remove("exportedAt");
        assertEq("两次导出的数据部分完全一致", mapper.writeValueAsString(bundle1), mapper.writeValueAsString(bundle2));

        // ========== 10. 幂等：重复导入 ==========
        System.out.println("\n---- 第 10 步：重复导入（幂等验证）----");
        Map<String, Object> again = service.importData(reloaded);
        assertEq("重复导入：新增 = 0", 0L, ((Number) again.get("inserted")).longValue());
        assertEq("重复导入：更新 = 19", 19L, ((Number) again.get("updated")).longValue());
        assertEq("重复导入：失败 = 0", 0L, ((Number) again.get("failed")).longValue());

        // ========== 11. 失败容错：含坏行的导入不中断整体 ==========
        System.out.println("\n---- 第 11 步：含坏行的导入（失败容错）----");
        Map<String, Object> badBundle = mapper.readValue(json1, new TypeReference<Map<String, Object>>() {
        });
        // 追加一行 code 与 id=1 重复的 llm_provider（id=99 不存在 → INSERT 时撞 UNIQUE(code)）
        Map<String, Object> badRow = new LinkedHashMap<>();
        badRow.put("id", 99);
        badRow.put("code", "deepseek");
        badRow.put("name", "重复 code 的坏行");
        badRow.put("base_url", "https://bad.example.com");
        badRow.put("created_at", "2026-05-01 10:00:00.0");
        badRow.put("updated_at", "2026-05-01 10:00:00.0");
        rowsOf(badBundle, "llmProvider", "llm_provider").add(badRow);
        Map<String, Object> badResult = service.importData(badBundle);
        assertEq("坏行导入：失败 = 1", 1L, ((Number) badResult.get("failed")).longValue());
        assertEq("坏行导入：其余 19 行仍更新成功", 19L, ((Number) badResult.get("updated")).longValue());
        Map<String, Object> llmDetail = findCategoryDetail(badResult, "llmProvider");
        List<Map<String, Object>> llmTables = (List<Map<String, Object>>) llmDetail.get("tables");
        assertTrue("坏行导入：错误摘要已记录", llmTables.get(0).get("errors") instanceof List
                && !((List<?>) llmTables.get(0).get("errors")).isEmpty());
        assertEq("坏行导入后：行数仍为 19", 19L, totalCount(jdbc));

        // ========== 12. 序列修复：导入后可继续新增数据（自增 id 不冲突） ==========
        System.out.println("\n---- 第 12 步：自增序列修复验证 ----");
        jdbc.update("INSERT INTO llm_provider (code, name, base_url, created_at, updated_at) VALUES (?,?,?,?,?)",
                "new-provider", "导入后新增平台", "https://new.example.com",
                Timestamp.valueOf("2026-06-01 09:00:00"), Timestamp.valueOf("2026-06-01 09:00:00"));
        Long newProviderId = jdbc.queryForObject("SELECT MAX(id) FROM llm_provider", Long.class);
        assertEq("序列修复：llm_provider 新行 id = 3（原 max=2）", 3L, newProviderId);

        jdbc.update("INSERT INTO skill (name, description, tool_names, user_id, agent_type, created_at, updated_at) VALUES (?,?,?,?,?,?,?)",
                "导入后新增技能", "验证序列修复", "[]", 1L, "code_assistant",
                Timestamp.valueOf("2026-06-01 09:00:00"), Timestamp.valueOf("2026-06-01 09:00:00"));
        Long newSkillId = jdbc.queryForObject("SELECT MAX(id) FROM skill", Long.class);
        assertEq("序列修复：skill 新行 id = 3（原 max=2）", 3L, newSkillId);

        jdbc.update("INSERT INTO conversation (name, user_id, agent_type, created_at, updated_at) VALUES (?,?,?,?,?)",
                "导入后新增会话", 1L, "code_assistant",
                Timestamp.valueOf("2026-06-01 09:00:00"), Timestamp.valueOf("2026-06-01 09:00:00"));
        Long newConvId = jdbc.queryForObject("SELECT MAX(id) FROM conversation", Long.class);
        assertEq("序列修复：conversation 新行 id = 3（原 max=2）", 3L, newConvId);

        // ========== 13. 非法文件拒绝 ==========
        System.out.println("\n---- 第 13 步：非法备份文件校验 ----");
        Map<String, Object> noApp = new LinkedHashMap<>();
        noApp.put("formatVersion", 1);
        noApp.put("categories", Map.of("x", "y"));
        assertThrows("非法文件：缺少 app 标识被拒绝", () -> service.importData(noApp));

        Map<String, Object> badVersion = new LinkedHashMap<>();
        badVersion.put("app", "CodeCraft");
        badVersion.put("formatVersion", 99);
        badVersion.put("categories", Map.of("agent", Map.of()));
        assertThrows("非法文件：版本过高被拒绝", () -> service.importData(badVersion));

        Map<String, Object> noCategories = new LinkedHashMap<>();
        noCategories.put("app", "CodeCraft");
        noCategories.put("formatVersion", 1);
        noCategories.put("categories", Map.of());
        assertThrows("非法文件：无数据类别被拒绝", () -> service.importData(noCategories));

        // ========== 汇总 ==========
        System.out.println("\n═══════════════════════════════════════════");
        System.out.println("  通过 " + passed + " / 失败 " + failed);
        System.out.println("═══════════════════════════════════════════");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== 测试数据铺垫 ====================

    /** 铺垫 10 张表共 19 行：覆盖中文/特殊字符/换行/emoji/null/时间/JSON 字符串等类型 */
    static void seedData(JdbcTemplate jdbc) {
        Timestamp ts = Timestamp.valueOf("2026-05-01 10:20:30.123");
        Timestamp ts2 = Timestamp.valueOf("2026-05-02 11:22:33");

        // 1) LLM 配置 ×2
        jdbc.update("INSERT INTO llm_provider (id, code, name, base_url, api_key, default_model, model_list, request_template, is_default, enabled, sort_order, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                1L, "deepseek", "DeepSeek", "https://api.deepseek.com", "sk-test-key-1", "deepseek-v4-flash",
                "[\"deepseek-v4-pro\",\"deepseek-v4-flash\"]", "deepseek", true, true, 1, ts, ts);
        jdbc.update("INSERT INTO llm_provider (id, code, name, base_url, api_key, default_model, model_list, request_template, is_default, enabled, sort_order, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                2L, "custom", "自建平台（中文名）", "https://llm.internal.cn/v1", "sk-测试key-含中文", null,
                null, "openai", false, false, 2, ts2, ts2);

        // 2) 智能体 ×2（含 null）
        jdbc.update("INSERT INTO agent_config (id, name, description, avatar, system_prompt, tool_names, model_name, thinking_mode, execution_mode, temperature, work_dir, sort_order, enabled, is_default, is_builtin, provider_id, provider_code, character_profile, user_id, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                1L, "AI 助手", "默认的AI编程助手，拥有全部工具", "🤖", null, null, "deepseek-v4-flash",
                "non-thinking", "manual", 0.3, null, 1, true, true, true, 1L, null, null, null, ts, ts);
        jdbc.update("INSERT INTO agent_config (id, name, description, avatar, system_prompt, tool_names, model_name, thinking_mode, execution_mode, temperature, work_dir, sort_order, enabled, is_default, is_builtin, provider_id, provider_code, character_profile, user_id, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                2L, "文档助手", "技术文档撰写（含'引号'与\n换行）", "📝", "你是一名严谨的文档助手。",
                "[\"file_writer\",\"file_explorer\"]", "qwen-max", "thinking", "auto", 0.7,
                "C:\\work\\docs", 2, true, false, false, 2L, "custom", "角色设定：输出前先列大纲。", 1L, ts2, ts2);

        // 3) 技能 ×2
        jdbc.update("INSERT INTO skill (id, name, description, tool_names, instructions, trigger_words, confidence, usage_count, success_count, fail_count, user_id, agent_type, agent_config_id, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                1L, "天气查询", "查询目标城市天气", "[\"web_search\"]", "第1步：搜索城市天气。\n第2步：总结。",
                "[\"天气\",\"气温\"]", 0.9, 5, 4, 1, 1L, "code_assistant", 1L, ts, ts);
        jdbc.update("INSERT INTO skill (id, name, description, tool_names, instructions, trigger_words, confidence, usage_count, success_count, fail_count, user_id, agent_type, agent_config_id, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                2L, "Git 提交", "规范化 Git 提交信息", "[\"command\"]", "检查 diff 后生成提交信息。",
                null, 0.5, 0, 0, 0, 1L, "code_assistant", null, ts2, ts2);

        // 4) 会话 ×2 + 消息 ×3 + 压缩记录 ×1
        jdbc.update("INSERT INTO conversation (id, name, user_id, agent_type, agent_config_id, work_dir, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
                1L, "测试会话一", 1L, "code_assistant", 1L, null, ts, ts);
        jdbc.update("INSERT INTO conversation (id, name, user_id, agent_type, agent_config_id, work_dir, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
                2L, "会话二（工作目录）", 1L, "code_assistant", 1L, "C:\\work\\project", ts2, ts2);
        jdbc.update("INSERT INTO conversation_message (id, conversation_id, role, content, reasoning, tool_calls, turn_id, message_type, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                1L, 1L, "user", "你好，这是一条测试消息", null, null, "turn-1", null, ts);
        jdbc.update("INSERT INTO conversation_message (id, conversation_id, role, content, reasoning, tool_calls, turn_id, message_type, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                2L, 1L, "assistant", "这是回复\n含换行与特殊字符：<>&\"'` 以及 emoji 🎉",
                "思考过程……", "[{\"name\":\"web_search\",\"args\":{}}]", "turn-1", null, ts2);
        jdbc.update("INSERT INTO conversation_message (id, conversation_id, role, content, reasoning, tool_calls, turn_id, message_type, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                3L, 2L, "user", "桌面截图注入的消息", null, null, null, "desktop_inject", ts2);
        jdbc.update("INSERT INTO conversation_compaction (id, conversation_id, summary, start_message_id, end_message_id, token_savings, superseded, created_at) VALUES (?,?,?,?,?,?,?,?)",
                1L, 1L, "# 摘要\n- 要点一\n- 要点二", 1L, 2L, 1234, false, ts);

        // 5) 踩坑经验 ×2 + 规则 ×2
        jdbc.update("INSERT INTO lesson (id, project_key, tool_name, error_category, error_code, error_signature, symptom, root_cause, solution, params_json, applicable_cond, keywords, status, source, hit_count, success_count, fail_count, type, goal, env_params, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                1L, "CodeCraft", "command", "COMPILE", "BUILD FAILURE", "sig-1", "编译失败现象",
                "根因：缺少依赖", "在 pom.xml 添加 {缺失依赖} 后重新编译",
                "[{\"name\":\"缺失依赖\",\"value\":\"starter-web\"}]", "仅当 pom.xml 中不存在 {缺失依赖} 时",
                "编译 依赖", 1, "llm", 3, 2, 1, "FAILURE", null, "{\"os\":\"windows\"}", ts, ts);
        jdbc.update("INSERT INTO lesson (id, project_key, tool_name, error_category, error_code, error_signature, symptom, root_cause, solution, params_json, applicable_cond, keywords, status, source, hit_count, success_count, fail_count, type, goal, env_params, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                2L, "CodeCraft", "web_search", "NETWORK", "TIMEOUT", "sig-2", "检索超时现象",
                null, "改用官方文档站直接抓取", null, null, "检索 超时", 0, "auto", 1, 0, 0, "DETOUR",
                "调研协议规范", null, ts2, ts2);
        jdbc.update("INSERT INTO lesson_rule (id, text_pattern, error_category, error_code, hit_count, match_hit_count, source, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                1L, "cannot find symbol", "COMPILE", "SYMBOL_NOT_FOUND", 2, 5, "llm", 1, ts, ts);
        jdbc.update("INSERT INTO lesson_rule (id, text_pattern, error_category, error_code, hit_count, match_hit_count, source, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                2L, "connection refused", "NETWORK", "ECONNREFUSED", 1, 0, "llm", 0, ts2, ts2);

        // 6) MCP 服务器 ×2
        jdbc.update("INSERT INTO mcp_server (id, name, type, url, command, headers, tool_prefix, permission_level, enabled, auto_register, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                1L, "GitHub", "http", "http://localhost:3001/mcp", null,
                "{\"Authorization\":\"Bearer ghp_xxx\"}", "github_", "SAFE", false, true, ts, ts);
        jdbc.update("INSERT INTO mcp_server (id, name, type, url, command, headers, tool_prefix, permission_level, enabled, auto_register, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                2L, "本地文件系统", "stdio", null, "npx -y @modelcontextprotocol/server-filesystem",
                null, "", "DATA", true, false, ts2, ts2);

        // 7) 数据库连接 ×1
        jdbc.update("INSERT INTO db_connection (id, name, db_type, host, port, database_name, username, password_encrypted, extra_params, enabled, user_id, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                1L, "订单库", "mysql", "10.0.0.5", 3306, "orders", "root", "AQIDBAU=",
                "useSSL=false&serverTimezone=Asia/Shanghai", true, 1L, ts, ts);
    }

    // ==================== 工具方法 ====================

    /** 全量执行 schema.sql（复刻应用启动 sql.init：continue-on-error 容错） */
    static void runSchema(Connection conn) {
        ClassPathResource resource = new ClassPathResource("schema.sql");
        ScriptUtils.executeSqlScript(conn, new EncodedResource(resource, StandardCharsets.UTF_8),
                true, true, "--", ";", "/*", "*/");
    }

    /** 从备份结构中取某类别某表的行列表 */
    static List<Map<String, Object>> rowsOf(Map<String, Object> bundle, String categoryKey, String table) {
        Map<String, Object> categories = (Map<String, Object>) bundle.get("categories");
        Map<String, Object> category = (Map<String, Object>) categories.get(categoryKey);
        Map<String, Object> tables = (Map<String, Object>) category.get("tables");
        return (List<Map<String, Object>>) tables.get(table);
    }

    /** 从导入结果中取某类别明细 */
    static Map<String, Object> findCategoryDetail(Map<String, Object> importResult, String categoryKey) {
        List<Map<String, Object>> details = (List<Map<String, Object>>) importResult.get("details");
        for (Map<String, Object> detail : details) {
            if (categoryKey.equals(detail.get("category"))) {
                return detail;
            }
        }
        throw new IllegalStateException("导入明细中找不到类别: " + categoryKey);
    }

    static long countOf(JdbcTemplate jdbc, String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }

    static Map<String, Long> tableCounts(JdbcTemplate jdbc) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : ALL_TABLES) {
            counts.put(table, countOf(jdbc, table));
        }
        return counts;
    }

    static long totalCount(JdbcTemplate jdbc) {
        long total = 0;
        for (String table : ALL_TABLES) {
            total += countOf(jdbc, table);
        }
        return total;
    }

    static boolean parseOk(String timestampText) {
        try {
            Timestamp.valueOf(timestampText);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static void assertEq(String name, Object expected, Object actual) {
        if (Objects.equals(expected, actual)) {
            pass(name);
        } else {
            fail(name + "，期望 " + expected + "，实际 " + actual);
        }
    }

    static void assertTrue(String name, boolean condition) {
        if (condition) {
            pass(name);
        } else {
            fail(name);
        }
    }

    static void assertThrows(String name, Runnable runnable) {
        try {
            runnable.run();
            fail(name + "（未抛出异常）");
        } catch (Exception e) {
            pass(name + "（" + e.getClass().getSimpleName() + "）");
        }
    }

    static void pass(String msg) {
        passed++;
        System.out.println("✅ " + msg);
    }

    static void fail(String msg) {
        failed++;
        System.out.println("❌ " + msg);
    }
}
