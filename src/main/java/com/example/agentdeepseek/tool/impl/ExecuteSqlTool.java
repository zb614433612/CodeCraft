package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.ExecutionTokenManager;
import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.util.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * SQL 查询工具
 * 执行 SELECT 查询（自动 LIMIT 保护），增删改操作需获得执行权限
 */
@Slf4j
@Component
public class ExecuteSqlTool implements Tool {

    private static final int MAX_SELECT_ROWS = 200;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ExecutionTokenManager executionTokenManager;

    public ExecuteSqlTool(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, ExecutionTokenManager executionTokenManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.executionTokenManager = executionTokenManager;
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

        String sqlType = detectSqlType(sql);

        // 写操作在手动模式下需要权限锁
        if (!"SELECT".equals(sqlType)) {
            if ("manual".equals(ToolContext.getMode())) {
                Long sessionId = ToolContext.getConversationId();
                if (sessionId == null || !executionTokenManager.tryConsume(sessionId)) {
                    return "错误：增删改操作需要先通过 ask_execution 工具获得您的执行许可";
                }
            }
        }

        try {
            if ("SELECT".equals(sqlType)) {
                return executeSelect(sql);
            } else {
                return executeUpdate(sql);
            }
        } catch (Exception e) {
            log.error("SQL 执行失败: {}", sql, e);
            return "错误：SQL 执行失败 - " + e.getMessage();
        }
    }

    /**
     * 检测 SQL 语句类型
     */
    private String detectSqlType(String sql) {
        String trimmed = sql.trim().toUpperCase();
        // 取第一个关键字
        String[] parts = trimmed.split("\\s+");
        if (parts.length == 0) return "UNKNOWN";
        String keyword = parts[0];
        return switch (keyword) {
            case "SELECT" -> "SELECT";
            case "INSERT", "UPDATE", "DELETE", "REPLACE" -> "DML";
            case "CREATE", "ALTER", "DROP", "TRUNCATE" -> "DDL";
            default -> "UNKNOWN";
        };
    }

    /**
     * 执行 SELECT 查询
     */
    private String executeSelect(String sql) {
        // 自动追加 LIMIT
        String limitedSql = sql;
        if (!sql.toUpperCase().contains(" LIMIT ")) {
            limitedSql = sql + " LIMIT " + MAX_SELECT_ROWS;
        }

        log.debug("执行 SELECT: {}", limitedSql);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(limitedSql);

        ArrayNode resultArray = objectMapper.createArrayNode();
        for (Map<String, Object> row : rows) {
            ObjectNode rowNode = objectMapper.createObjectNode();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                Object value = entry.getValue();
                if (value == null) {
                    rowNode.putNull(entry.getKey());
                } else if (value instanceof Number num) {
                    rowNode.put(entry.getKey(), num.doubleValue());
                } else {
                    rowNode.put(entry.getKey(), value.toString());
                }
            }
            resultArray.add(rowNode);
        }

        int rowCount = rows.size();
        String summary = "查询完成，返回 " + rowCount + " 行";
        if (!sql.toUpperCase().contains(" LIMIT ")) {
            summary += "（自动限制最多 " + MAX_SELECT_ROWS + " 行）";
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
