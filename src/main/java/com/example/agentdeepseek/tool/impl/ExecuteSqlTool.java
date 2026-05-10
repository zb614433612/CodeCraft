package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.PermissionContext;
import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.util.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * SQL 查询工具
 * 执行 SELECT 查询（自动 LIMIT 保护），增删改操作需获得执行权限
 */
@Slf4j
@Component
public class ExecuteSqlTool implements Tool {

    /** 单条 SQL 最大长度（字符） */
    private static final int MAX_SQL_LENGTH = 5000;
    /** SELECT 查询自动限制行数 */
    private static final int MAX_SELECT_ROWS = 200;
    /** 匹配注释前缀的正则 */
    private static final Pattern COMMENT_PATTERN = Pattern.compile("^\\s*(/\\*.*?\\*/\\s*|--[^\n]*\n)*", Pattern.DOTALL);
    /** 匹配已有 LIMIT 子句的正则（忽略大小写、单词边界） */
    private static final Pattern LIMIT_PATTERN = Pattern.compile("\\bLIMIT\\b", Pattern.CASE_INSENSITIVE);
    /** 写操作关键字列表 */
    private static final List<String> WRITE_KEYWORDS = List.of(
            "INSERT", "UPDATE", "DELETE", "REPLACE",
            "CREATE", "ALTER", "DROP", "TRUNCATE",
            "MERGE", "GRANT", "REVOKE"
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ExecuteSqlTool(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "execute_sql";
    }

    @Override
    public String getDescription() {
        return "执行 SQL 查询和修改操作。SELECT 查询会自动限制最多 " + MAX_SELECT_ROWS + " 行。"
                + "INSERT/UPDATE/DELETE/ALTER/CREATE/DROP/TRUNCATE 等写操作在手动模式下需要 ask_execution 授权。"
                + "不支持多语句同时执行";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();
        ObjectNode sql = objectMapper.createObjectNode();
        sql.put("type", "string");
        sql.put("description", "要执行的 SQL 语句。SELECT 查询建议加 WHERE 条件避免全表扫描。");
        properties.set("sql", sql);

        parameters.set("properties", properties);
        parameters.putArray("required").add("sql");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String sql = arguments.path("sql").asText().trim();
        if (sql.isEmpty()) {
            return "错误：缺少必要参数 sql";
        }

        // 长度检查
        if (sql.length() > MAX_SQL_LENGTH) {
            return "错误：SQL 语句过长（" + sql.length() + " 字符），最大允许 " + MAX_SQL_LENGTH + " 字符";
        }

        // 多语句检查
        if (hasMultipleStatements(sql)) {
            return "错误：不支持同时执行多条 SQL 语句";
        }

        // 提取 SQL 类型（去除注释前缀后判断）
        String cleanSql = stripComments(sql);
        String sqlType = detectSqlType(cleanSql);

        // 写操作在手动模式下需要权限锁
        if (!"SELECT".equals(sqlType)) {
            if ("manual".equals(ToolContext.getMode())) {
                String response = PermissionContext.requestPermission(getName(), arguments, ToolContext.getConversationId());
                if (response != null) return response;
            }
        }

        try {
            if ("SELECT".equals(sqlType)) {
                return executeSelect(sql);
            } else if ("WRITE".equals(sqlType)) {
                return executeUpdate(sql);
            } else {
                return "错误：无法识别的 SQL 类型，仅支持 SELECT 和常见写操作（INSERT/UPDATE/DELETE/ALTER/CREATE/DROP/TRUNCATE）";
            }
        } catch (Exception e) {
            log.error("SQL 执行失败: {}", sql, e);
            return "错误：SQL 执行失败 - " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /**
     * 去除 SQL 语句前导注释（块注释 /* ... *​/ 和行注释 --）
     */
    private String stripComments(String sql) {
        String result = COMMENT_PATTERN.matcher(sql).replaceAll("");
        return result.trim();
    }

    /**
     * 检测 SQL 语句类型（去除注释后取第一个关键字）
     */
    private String detectSqlType(String cleanSql) {
        if (cleanSql.isEmpty()) return "UNKNOWN";
        String[] parts = cleanSql.split("\\s+");
        if (parts.length == 0) return "UNKNOWN";
        String keyword = parts[0].toUpperCase();
        return "SELECT".equals(keyword) ? "SELECT"
                : WRITE_KEYWORDS.contains(keyword) ? "WRITE"
                : "UNKNOWN";
    }

    /**
     * 检测是否包含多条 SQL 语句（非引号内的分号）
     */
    private boolean hasMultipleStatements(String sql) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        int statementCount = 0;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (c == ';' && !inSingleQuote && !inDoubleQuote) {
                statementCount++;
                if (statementCount >= 2) return true;
            }
        }
        return false;
    }

    /**
     * 执行 SELECT 查询
     */
    private String executeSelect(String sql) {
        // 使用正则检测是否已有 LIMIT 子句
        if (!LIMIT_PATTERN.matcher(sql).find()) {
            sql = sql + " LIMIT " + MAX_SELECT_ROWS;
        }

        log.debug("执行 SELECT: {}", sql);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);

        ArrayNode resultArray = objectMapper.createArrayNode();
        for (Map<String, Object> row : rows) {
            ObjectNode rowNode = objectMapper.createObjectNode();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                Object value = entry.getValue();
                if (value == null) {
                    rowNode.putNull(entry.getKey());
                } else if (value instanceof Integer || value instanceof Long) {
                    // 整数直接转数字（不丢失精度）
                    rowNode.put(entry.getKey(), ((Number) value).longValue());
                } else if (value instanceof BigInteger bigInt) {
                    // BigInteger 可能超出 long 范围，转字符串
                    rowNode.put(entry.getKey(), bigInt.toString());
                } else if (value instanceof BigDecimal bigDec) {
                    // BigDecimal 保持精度
                    rowNode.put(entry.getKey(), bigDec.toPlainString());
                } else if (value instanceof Float || value instanceof Double) {
                    rowNode.put(entry.getKey(), ((Number) value).doubleValue());
                } else if (value instanceof Number num) {
                    // 其他数值类型
                    rowNode.put(entry.getKey(), num.toString());
                } else if (value instanceof Boolean b) {
                    rowNode.put(entry.getKey(), b);
                } else {
                    rowNode.put(entry.getKey(), value.toString());
                }
            }
            resultArray.add(rowNode);
        }

        int rowCount = rows.size();
        StringBuilder summary = new StringBuilder("查询完成，返回 ").append(rowCount).append(" 行");
        if (!LIMIT_PATTERN.matcher(sql).find() || sql.length() > sql.lastIndexOf(" LIMIT ") + 20) {
            // 如果是我们追加的 LIMIT
        }
        if (rowCount >= MAX_SELECT_ROWS && LIMIT_PATTERN.matcher(sql).find()) {
            summary.append("（受 ").append(MAX_SELECT_ROWS).append(" 行限制，结果可能不完整）");
        }

        return summary + "\n" + resultArray.toPrettyString();
    }

    /**
     * 执行写操作
     */
    private String executeUpdate(String sql) {
        log.debug("执行更新: {}", sql);
        int affected = jdbcTemplate.update(sql);
        return "执行成功，影响 " + affected + " 行";
    }
}
