package com.example.agentdeepseek.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Clob;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据导入导出服务（数据管理页「导出备份 / 导入备份」）
 * <p>
 * 以「表级快照」方式工作：导出时对每张业务表执行 SELECT *（按 id 排序）并规范化值为 JSON 友好类型；
 * 导入时按主键（id）执行 UPSERT —— 已存在则整行 UPDATE，不存在则 INSERT，
 * 最后修复自增序列（RESTART WITH MAX(id)+1），保证导入后继续新增数据不会主键冲突。
 * </p>
 * <p>
 * 覆盖 7 个数据类别（10 张表）：
 * <ul>
 *   <li>LLM 配置：llm_provider</li>
 *   <li>智能体：agent_config</li>
 *   <li>技能：skill</li>
 *   <li>聊天记录：conversation / conversation_message / conversation_compaction</li>
 *   <li>踩坑经验：lesson / lesson_rule（lesson_norm_cache 为纯缓存表，不导出）</li>
 *   <li>MCP 服务器：mcp_server</li>
 *   <li>数据库连接：db_connection</li>
 * </ul>
 * 设计约束：
 * <ul>
 *   <li>敏感数据（API Key / 数据库密码密文 / MCP headers）原样导出，用于重装后免重新配置；前端提示用户妥善保管备份文件；</li>
 *   <li>导入逐行容错：单行失败不中断整体（记录错误摘要并返回统计），尽力恢复数据；</li>
 *   <li>导入顺序 = {@link #CATEGORIES} 声明顺序（父表先于子表，满足外键依赖）。</li>
 * </ul>
 */
@Slf4j
@Service
public class DataTransferService {

    /** 备份文件格式版本（结构变化时递增） */
    public static final int FORMAT_VERSION = 1;

    /** 备份文件标识（导入时校验，防止误选其他 JSON 文件） */
    public static final String APP_TAG = "CodeCraft";

    /** 备份文件元数据中导出时间的展示格式 */
    private static final DateTimeFormatter EXPORTED_AT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 合法列名模式（防止备份 JSON 中的恶意列名拼接 SQL） */
    private static final String COL_PATTERN = "[A-Za-z0-9_]+";

    /** 类别定义：顺序 = 导出顺序 = 导入顺序（父表先于子表，满足外键依赖） */
    public static final List<CategoryDef> CATEGORIES = List.of(
            new CategoryDef("llmProvider", "LLM 配置", List.of("llm_provider")),
            new CategoryDef("agent", "智能体", List.of("agent_config")),
            new CategoryDef("skill", "技能", List.of("skill")),
            new CategoryDef("chat", "聊天记录", List.of("conversation", "conversation_message", "conversation_compaction")),
            new CategoryDef("lesson", "踩坑经验", List.of("lesson", "lesson_rule")),
            new CategoryDef("mcp", "MCP 服务器", List.of("mcp_server")),
            new CategoryDef("dbConnection", "数据库连接", List.of("db_connection"))
    );

    private final JdbcTemplate jdbcTemplate;

    public DataTransferService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ==================== 导出 ====================

    /**
     * 导出指定类别数据。
     *
     * @param categoryKeys 类别 key 列表（如 ["agent","skill"]）；null/空 = 全部类别；未知 key 忽略
     * @return 备份结构：{app, formatVersion, exportedAt, categories: {key: {label, tables: {表名: [行...]}}}}
     */
    public Map<String, Object> exportData(List<String> categoryKeys) {
        Set<String> wanted = normalizeKeys(categoryKeys);
        Map<String, Object> categories = new LinkedHashMap<>();
        for (CategoryDef def : CATEGORIES) {
            if (!wanted.contains(def.key())) {
                continue;
            }
            Map<String, Object> tables = new LinkedHashMap<>();
            for (String table : def.tables()) {
                List<Map<String, Object>> rows = readTable(table);
                tables.put(table, rows);
                log.info("数据导出 [{} / {}]：{} 行", def.label(), table, rows.size());
            }
            Map<String, Object> category = new LinkedHashMap<>();
            category.put("label", def.label());
            category.put("tables", tables);
            categories.put(def.key(), category);
        }

        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("app", APP_TAG);
        bundle.put("formatVersion", FORMAT_VERSION);
        bundle.put("exportedAt", LocalDateTime.now().format(EXPORTED_AT_FMT));
        bundle.put("categories", categories);
        return bundle;
    }

    /**
     * 各类别当前数据量（类别下所有表行数之和），用于导出界面展示。
     */
    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (CategoryDef def : CATEGORIES) {
            long total = 0;
            for (String table : def.tables()) {
                Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
                total += (count == null ? 0 : count);
            }
            result.put(def.key(), total);
        }
        return result;
    }

    private List<Map<String, Object>> readTable(String table) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM " + table + " ORDER BY id");
        List<Map<String, Object>> normalized = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> normalizedRow = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                normalizedRow.put(entry.getKey(), normalizeValue(entry.getValue()));
            }
            normalized.add(normalizedRow);
        }
        return normalized;
    }

    /** JDBC 读取的值 → JSON 友好类型（时间用 H2 原生可解析的字符串格式，保证往返精度无损） */
    private Object normalizeValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp ts) {
            return ts.toString();
        }
        if (value instanceof LocalDateTime ldt) {
            return Timestamp.valueOf(ldt).toString();
        }
        if (value instanceof java.sql.Date d) {
            return d.toString();
        }
        if (value instanceof java.sql.Time t) {
            return t.toString();
        }
        if (value instanceof java.util.Date d) {
            return new Timestamp(d.getTime()).toString();
        }
        if (value instanceof Clob clob) {
            try {
                return clob.getSubString(1, (int) clob.length());
            } catch (Exception e) {
                log.warn("CLOB 读取失败: {}", e.getMessage());
                return null;
            }
        }
        if (value instanceof byte[] bytes) {
            // 当前覆盖表无 BLOB 列，防御性处理为 Base64 字符串
            return Base64.getEncoder().encodeToString(bytes);
        }
        return value;
    }

    // ==================== 导入 ====================

    /**
     * 导入备份数据（合并策略：同 id 覆盖更新 / 新 id 追加插入，不清空现有数据）。
     *
     * @param bundle 备份结构（与 {@link #exportData} 输出一致）
     * @return 统计：{inserted, updated, failed, details: [{category, label, tables: [{table, inserted, updated, failed, errors?}]}]}
     */
    public Map<String, Object> importData(Map<String, Object> bundle) {
        if (bundle == null || !APP_TAG.equals(bundle.get("app"))) {
            throw new IllegalArgumentException("不是有效的 CodeCraft 备份文件");
        }
        Object versionObj = bundle.get("formatVersion");
        if (versionObj instanceof Number n && n.intValue() > FORMAT_VERSION) {
            throw new IllegalArgumentException("备份文件版本（" + n.intValue() + "）高于当前应用支持的版本（"
                    + FORMAT_VERSION + "），请升级应用后再导入");
        }
        Object categoriesObj = bundle.get("categories");
        if (!(categoriesObj instanceof Map<?, ?> categoriesMap) || categoriesMap.isEmpty()) {
            throw new IllegalArgumentException("备份文件不包含任何数据类别");
        }

        int totalInserted = 0;
        int totalUpdated = 0;
        int totalFailed = 0;
        List<Map<String, Object>> details = new ArrayList<>();
        Set<String> touchedTables = new LinkedHashSet<>();

        for (CategoryDef def : CATEGORIES) {
            Object categoryObj = categoriesMap.get(def.key());
            if (!(categoryObj instanceof Map<?, ?> category)) {
                continue;
            }
            Object tablesObj = category.get("tables");
            if (!(tablesObj instanceof Map<?, ?> tables)) {
                continue;
            }

            List<Map<String, Object>> tableDetails = new ArrayList<>();
            for (String table : def.tables()) {
                Object rowsObj = tables.get(table);
                if (!(rowsObj instanceof List<?> rows) || rows.isEmpty()) {
                    continue;
                }

                int inserted = 0;
                int updated = 0;
                int failed = 0;
                List<String> errors = new ArrayList<>();
                for (Object rowObj : rows) {
                    if (!(rowObj instanceof Map<?, ?> row)) {
                        failed++;
                        continue;
                    }
                    try {
                        if (upsertRow(table, row)) {
                            inserted++;
                        } else {
                            updated++;
                        }
                    } catch (Exception e) {
                        failed++;
                        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                        log.warn("数据导入失败 [{}.{}]: {}", def.key(), table, msg);
                        if (errors.size() < 10) {
                            errors.add(shorten(msg));
                        }
                    }
                }
                touchedTables.add(table);

                Map<String, Object> tableDetail = new LinkedHashMap<>();
                tableDetail.put("table", table);
                tableDetail.put("inserted", inserted);
                tableDetail.put("updated", updated);
                tableDetail.put("failed", failed);
                if (!errors.isEmpty()) {
                    tableDetail.put("errors", errors);
                }
                tableDetails.add(tableDetail);
                log.info("数据导入 [{} / {}]：新增 {} / 更新 {} / 失败 {}", def.label(), table, inserted, updated, failed);

                totalInserted += inserted;
                totalUpdated += updated;
                totalFailed += failed;
            }

            if (!tableDetails.isEmpty()) {
                Map<String, Object> categoryDetail = new LinkedHashMap<>();
                categoryDetail.put("category", def.key());
                categoryDetail.put("label", def.label());
                categoryDetail.put("tables", tableDetails);
                details.add(categoryDetail);
            }
        }

        // 修复自增序列：导入行自带显式 id，需把序列推进到 MAX(id)+1，避免后续新增数据主键冲突
        for (String table : touchedTables) {
            try {
                resetIdentity(table);
            } catch (Exception e) {
                log.warn("重置 {} 自增序列失败: {}", table, e.getMessage());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inserted", totalInserted);
        result.put("updated", totalUpdated);
        result.put("failed", totalFailed);
        result.put("details", details);
        return result;
    }

    /**
     * 单行 UPSERT：按主键命中且行已存在则 UPDATE（返回 false），不存在则 INSERT（返回 true）。
     * <p>
     * 行存在性判断：先 UPDATE，返回 0 行时再 SELECT 复核 —— 兼容"值未变化时部分数据库返回 0 行"的差异，
     * 避免把"已存在但值相同"的行误判为不存在而重复 INSERT（主键冲突）。
     * </p>
     */
    private boolean upsertRow(String table, Map<?, ?> row) {
        List<String> cols = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (Map.Entry<?, ?> entry : row.entrySet()) {
            String col = String.valueOf(entry.getKey());
            if (!col.matches(COL_PATTERN)) {
                continue; // 列名非法（防注入）：跳过该列
            }
            cols.add(col);
            values.add(entry.getValue());
        }
        if (cols.isEmpty()) {
            throw new IllegalArgumentException("行数据无有效列");
        }

        int idIdx = -1;
        for (int i = 0; i < cols.size(); i++) {
            if ("id".equalsIgnoreCase(cols.get(i))) {
                idIdx = i;
                break;
            }
        }

        if (idIdx >= 0 && values.get(idIdx) != null) {
            Object idValue = values.get(idIdx);
            StringBuilder setClause = new StringBuilder();
            List<Object> args = new ArrayList<>();
            for (int i = 0; i < cols.size(); i++) {
                if (i == idIdx) {
                    continue;
                }
                if (setClause.length() > 0) {
                    setClause.append(", ");
                }
                setClause.append(cols.get(i)).append(" = ?");
                args.add(values.get(i));
            }
            if (setClause.length() > 0) {
                args.add(idValue);
                int n = jdbcTemplate.update("UPDATE " + table + " SET " + setClause + " WHERE " + cols.get(idIdx) + " = ?",
                        args.toArray());
                if (n > 0) {
                    return false;
                }
                // 复核：行存在但值未变化（部分数据库返回 0）→ 视为已更新，避免重复插入
                Integer exists = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM " + table + " WHERE " + cols.get(idIdx) + " = ?", Integer.class, idValue);
                if (exists != null && exists > 0) {
                    return false;
                }
            }
        }

        String colList = String.join(", ", cols);
        String placeholders = String.join(", ", Collections.nCopies(cols.size(), "?"));
        jdbcTemplate.update("INSERT INTO " + table + " (" + colList + ") VALUES (" + placeholders + ")", values.toArray());
        return true;
    }

    /** 自增序列修复：ALTER TABLE t ALTER COLUMN id RESTART WITH MAX(id)+1 */
    private void resetIdentity(String table) {
        Long maxId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM " + table, Long.class);
        long next = (maxId == null ? 0L : maxId) + 1L;
        jdbcTemplate.execute("ALTER TABLE " + table + " ALTER COLUMN id RESTART WITH " + next);
    }

    /** 类别 key 归一化：null/空 = 全部；否则匹配已知类别（忽略大小写与空白、忽略未知 key） */
    private Set<String> normalizeKeys(List<String> categoryKeys) {
        Set<String> wanted = new LinkedHashSet<>();
        if (categoryKeys == null || categoryKeys.isEmpty()) {
            for (CategoryDef def : CATEGORIES) {
                wanted.add(def.key());
            }
            return wanted;
        }
        for (String key : categoryKeys) {
            if (key == null) {
                continue;
            }
            String trimmed = key.trim();
            for (CategoryDef def : CATEGORIES) {
                if (def.key().equalsIgnoreCase(trimmed)) {
                    wanted.add(def.key());
                    break;
                }
            }
        }
        return wanted;
    }

    private String shorten(String text) {
        int max = 300;
        if (text == null) {
            return "未知错误";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /** 类别定义：key（接口 / 备份 JSON 中的键）、label（界面展示名）、tables（涉及的表，父表先于子表） */
    public record CategoryDef(String key, String label, List<String> tables) {
    }
}
