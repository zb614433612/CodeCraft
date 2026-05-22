package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;
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
@ToolPermission(category = OperationCategory.DATABASE, affectsData = true, highRisk = true, description = "执行SQL语句")
public class ExecuteSqlTool implements Tool {

    /** 单条 SQL 最大长度（字符） */
    private static final int MAX_SQL_LENGTH = 5000;
    /** SELECT 查询自动限制行数 */
    private static final int MAX_SELECT_ROWS = 200;
    /** 匹配注释前缀的正则 */
    private static final Pattern COMMENT_PATTERN = Pattern.compile("^\\s*(/\\*.*?\\*/\\s*|--[^\n]*\n)*", Pattern.DOTALL);
    /** 匹配已有 LIMIT 子句的正则（忽略大小写、单词边界） */
    private static final Pattern LIMIT_PATTERN = Pattern.compile("\\bLIMIT\\b", Pattern.CASE_INSENSITIVE);
    /** 匹配 SQL 字符串字面量的正则 */
    private static final Pattern STRING_LITERAL_PATTERN = Pattern.compile("'[^']*'|'[^']*");
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
        return "执行 SQL 查询和数据库修改操作。\n"
                + "【适用场景】查询数据库表结构、验证数据是否写入成功、排查数据异常、执行必要的增删改操作\n"
                + "【使用方式】SELECT 查询：直接传入 SQL（自动追加 LIMIT " + MAX_SELECT_ROWS + " 防止全表返回）。手动模式下所有 SQL 操作均需用户授权；自动模式下高危操作（DDL/DROP 等）仍需授权\n"
                + "【注意】不支持多语句（以分号分隔的多条 SQL），每次只执行一条；SQL 最大 " + MAX_SQL_LENGTH + " 字符；SELECT 建议加 WHERE 条件避免全表扫描";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();
        ObjectNode sql = objectMapper.createObjectNode();
        sql.put("type", "string");
        sql.put("description", "【必填】单条 SQL 语句。SELECT 示例：\"SELECT id, name FROM users WHERE status = 'active'\"。写操作示例：\"UPDATE users SET status = 'inactive' WHERE id = 1\"。SELECT 查询务必加 WHERE 条件，避免全表扫描");
        properties.set("sql", sql);

        parameters.set("properties", properties);
        parameters.putArray("required").add("sql");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String sql = arguments.path("sql").asText().trim();
        if (sql.isEmpty()) {
            return "【错误类型】【缺少参数】sql 字段缺失或为空。请提供一条完整的 SQL 语句。示例：{\"sql\": \"SELECT * FROM users WHERE id = 1\"}";
        }

        // 长度检查
        if (sql.length() > MAX_SQL_LENGTH) {
            return "【错误类型】【SQL 过长】SQL 语句 " + sql.length() + " 字符超出" + MAX_SQL_LENGTH + "字符限制。请精简查询条件，或拆分为多次调用（分别查询不同表/不同条件）";
        }

        // 多语句检查
        if (hasMultipleStatements(sql)) {
            return "【错误类型】【多语句禁止】检测到多条 SQL 语句（包含分号分隔）。请每次只执行一条 SQL，将多条语句拆分为多次独立调用。例如 UPDATE 和 SELECT 分两次调用";
        }

        // 提取 SQL 类型（去除注释前缀后判断）
        String cleanSql = stripComments(sql);
        String sqlType = detectSqlType(cleanSql);

        try {
            if ("SELECT".equals(sqlType)) {
                return executeSelect(sql);
            } else if ("WRITE".equals(sqlType)) {
                return executeUpdate(sql);
            } else {
                return "【错误类型】【SQL 类型未知】无法识别的 SQL 语句类型。仅支持：SELECT 查询 和 写操作（INSERT/UPDATE/DELETE/ALTER/CREATE/DROP/TRUNCATE）。请检查 SQL 开头关键字是否正确，去掉前导注释后重试";
            }
        } catch (Exception e) {
            log.error("SQL 执行失败: {}", sql, e);
            return "【错误类型】【SQL 执行异常】" + e.getClass().getSimpleName() + ": " + e.getMessage() + "。建议检查：① 表名/字段名是否存在 ② 字段类型是否匹配 ③ 约束条件是否冲突（如唯一索引重复）④ 外键依赖是否满足";
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
        // 先去除字符串字面量再检测 LIMIT，避免字面量内的 LIMIT 被误匹配
        String sqlNoLiterals = STRING_LITERAL_PATTERN.matcher(sql).replaceAll("");
        if (!LIMIT_PATTERN.matcher(sqlNoLiterals).find()) {
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
        // LIMIT 追加标记（用于后续判断是否为我们追加的）
        if (rowCount >= MAX_SELECT_ROWS && LIMIT_PATTERN.matcher(sqlNoLiterals).find()) {
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
