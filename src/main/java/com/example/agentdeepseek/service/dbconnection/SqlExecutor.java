package com.example.agentdeepseek.service.dbconnection;

import com.example.agentdeepseek.model.vo.SqlExecuteResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * SQL 检查与执行核心组件（管理页「执行 SQL」与 AI 工具 execute_sql 共用）
 * <p>
 * 检查策略分两档：
 * <ul>
 *   <li>{@link #checkStrict(String)}：严格模式（AI 工具）——单语句 + SELECT/写操作白名单 + 5000 字符上限；</li>
 *   <li>{@link #checkUi(String)}：宽松模式（管理页）——允许任意语句与多条语句（分号分隔）+ 20000 字符上限。</li>
 * </ul>
 * 执行采用「应用层拆分 + 逐条执行」：SQL 先按非引号内分号拆分为语句列表，再逐条执行
 * （不依赖驱动的多语句能力，H2/MySQL/PostgreSQL 行为一致），每条语句的结果与错误定位独立可用。
 * 单条 SELECT 且无显式 LIMIT 时自动追加 LIMIT（多取一行用于截断探测）；结果集按 maxRows
 * 截断（setMaxRows 下压 + 读取层兜底）。调用方负责连接的获取与关闭。
 * </p>
 */
@Slf4j
@Component
public class SqlExecutor {

    /** 单条 SQL 最大长度（字符）——严格模式（AI 工具 execute_sql） */
    public static final int MAX_SQL_LENGTH = 5000;
    /** SQL 最大长度（字符）——宽松模式（管理页「执行 SQL」，支持批量多语句） */
    public static final int MAX_SQL_LENGTH_UI = 20000;
    /** 查询结果集默认最大返回行数 */
    public static final int DEFAULT_MAX_SELECT_ROWS = 200;
    /** 查询结果集可请求的最大行数上限 */
    public static final int MAX_SELECT_ROWS_LIMIT = 1000;
    /** 语句执行超时（秒），防止长查询占住连接 */
    private static final int QUERY_TIMEOUT_SECONDS = 30;

    /** 匹配前导注释的正则（块注释与行注释） */
    private static final Pattern COMMENT_PATTERN = Pattern.compile("^\\s*(/\\*.*?\\*/\\s*|--[^\n]*\n)*", Pattern.DOTALL);
    /** 匹配已有 LIMIT 子句的正则（忽略大小写、单词边界） */
    private static final Pattern LIMIT_PATTERN = Pattern.compile("\\bLIMIT\\b", Pattern.CASE_INSENSITIVE);
    /** 匹配 SQL 字符串字面量的正则（单引号和双引号） */
    private static final Pattern STRING_LITERAL_PATTERN = Pattern.compile("'[^']*'|\"[^\"]*\"");
    /** 写操作关键字列表 */
    private static final List<String> WRITE_KEYWORDS = List.of(
            "INSERT", "UPDATE", "DELETE", "REPLACE",
            "CREATE", "ALTER", "DROP", "TRUNCATE",
            "MERGE", "GRANT", "REVOKE");

    /** 检查失败原因 */
    public enum CheckError { EMPTY, TOO_LONG, MULTIPLE_STATEMENTS, UNKNOWN_TYPE }

    /**
     * 检查结果
     *
     * @param ok        是否通过
     * @param error     失败原因（ok=true 时为 null）
     * @param sqlType   首关键字分类：SELECT / WRITE / UNKNOWN（宽松模式不拦截 UNKNOWN）
     * @param sqlLength 输入 SQL 长度（字符，用于错误提示）
     */
    public record CheckResult(boolean ok, CheckError error, String sqlType, int sqlLength) {
    }

    /** SQL 执行异常（包装底层 SQLException 的原始消息，供上层转用户提示） */
    public static class SqlExecutionException extends RuntimeException {
        public SqlExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ==================== 检查 ====================

    /** 严格检查（AI 工具 execute_sql）：单语句 + SELECT/写操作白名单 + 5000 字符上限 */
    public CheckResult checkStrict(String sql) {
        return check(sql, MAX_SQL_LENGTH, false);
    }

    /** 宽松检查（管理页「执行 SQL」）：允许任意语句与多条语句，20000 字符上限 */
    public CheckResult checkUi(String sql) {
        return check(sql, MAX_SQL_LENGTH_UI, true);
    }

    private CheckResult check(String sql, int maxLength, boolean allowMultipleStatements) {
        String trimmed = sql == null ? "" : sql.trim();
        List<String> statements = splitStatements(trimmed);
        if (statements.isEmpty()) {
            return new CheckResult(false, CheckError.EMPTY, "UNKNOWN", trimmed.length());
        }
        if (trimmed.length() > maxLength) {
            return new CheckResult(false, CheckError.TOO_LONG, "UNKNOWN", trimmed.length());
        }
        if (!allowMultipleStatements && statements.size() > 1) {
            return new CheckResult(false, CheckError.MULTIPLE_STATEMENTS, "UNKNOWN", trimmed.length());
        }
        String sqlType = detectSqlType(stripComments(statements.get(0)));
        if (!allowMultipleStatements && "UNKNOWN".equals(sqlType)) {
            return new CheckResult(false, CheckError.UNKNOWN_TYPE, "UNKNOWN", trimmed.length());
        }
        return new CheckResult(true, null, sqlType, trimmed.length());
    }

    // ==================== 执行 ====================

    /**
     * 执行 SQL（支持单条或多条语句），返回结构化多结果。
     * <p>
     * - SQL 按非引号内分号拆分为语句列表，逐条执行（每条语句的更新计数/结果集独立返回）；<br>
     * - SELECT 且未带 LIMIT 时自动追加 LIMIT（下压到数据库减少传输，多取一行用于截断探测）；<br>
     * - 每个结果集最多返回 maxRows 行（setMaxRows 驱动下压 + 读取层兜底），超出标记 truncated；<br>
     * - 任一条语句失败即中断并抛异常（错误消息含第几条；已执行语句按数据库自动提交语义生效）。
     * </p>
     *
     * @param connection 已打开的物理连接（调用方负责关闭）
     * @param sql        SQL 语句（调用方需先通过 check 校验）
     * @param dbLabel    目标库描述（结果标注，防混淆）
     * @param maxRows    每个结果集最大返回行数（可选，默认 200，上限 1000）
     */
    public SqlExecuteResult execute(Connection connection, String sql, String dbLabel, Integer maxRows) {
        String trimmed = sql == null ? "" : sql.trim();
        List<String> statements = splitStatements(trimmed);
        if (statements.isEmpty()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        int limit = clampMaxRows(maxRows);

        SqlExecuteResult result = new SqlExecuteResult();
        result.setDbLabel(dbLabel);
        List<SqlExecuteResult.StatementResult> statementResults = new ArrayList<>();
        long start = System.nanoTime();
        int executingIndex = 0;
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            // 驱动层行列限制：多读一行用于探测结果是否被截断
            statement.setMaxRows(limit + 1);

            for (String stmt : statements) {
                executingIndex++;
                String execStmt = stmt;
                // SELECT 且无显式 LIMIT 时追加 LIMIT（下压 + 多取一行用于截断探测）
                if ("SELECT".equals(detectSqlType(stripComments(stmt)))) {
                    String sqlNoLiterals = STRING_LITERAL_PATTERN.matcher(stmt).replaceAll("");
                    if (!LIMIT_PATTERN.matcher(sqlNoLiterals).find()) {
                        execStmt = stmt + " LIMIT " + (limit + 1);
                    }
                }

                boolean hasResultSet = statement.execute(execStmt);
                while (true) {
                    if (hasResultSet) {
                        statementResults.add(readResultSet(statement, statementResults.size(), limit));
                    } else {
                        int updateCount = statement.getUpdateCount();
                        if (updateCount == -1) {
                            break; // 本条语句没有更多结果
                        }
                        SqlExecuteResult.StatementResult sr = new SqlExecuteResult.StatementResult();
                        sr.setIndex(statementResults.size());
                        sr.setSqlType("WRITE");
                        sr.setAffectedRows(Math.max(updateCount, 0));
                        statementResults.add(sr);
                    }
                    hasResultSet = statement.getMoreResults();
                }
            }
        } catch (SQLException e) {
            log.warn("SQL 执行失败: dbLabel={}, statementIndex={}, err={}", dbLabel, executingIndex, e.getMessage());
            String prefix = executingIndex > 0 ? "第 " + executingIndex + " 条语句执行失败: " : "";
            throw new SqlExecutionException(prefix + e.getMessage() + "（已执行的语句可能已生效，请自行确认）", e);
        } finally {
            result.setElapsedMs((System.nanoTime() - start) / 1_000_000);
        }
        result.setResults(statementResults);
        result.setStatementCount(statementResults.size());
        return result;
    }

    /** 读取当前结果集（最多 maxRows 行，超过则标记截断），列名取 label（无 label 用 name） */
    private SqlExecuteResult.StatementResult readResultSet(Statement statement, int index, int maxRows) throws SQLException {
        SqlExecuteResult.StatementResult sr = new SqlExecuteResult.StatementResult();
        sr.setIndex(index);
        sr.setSqlType("SELECT");
        try (ResultSet rs = statement.getResultSet()) {
            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();
            List<String> columns = new ArrayList<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                String label = meta.getColumnLabel(i);
                columns.add(label == null || label.isEmpty() ? meta.getColumnName(i) : label);
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            boolean truncated = false;
            while (rs.next()) {
                if (rows.size() >= maxRows) {
                    truncated = true; // 已达上限且还有下一行
                    break;
                }
                Map<String, Object> row = new LinkedHashMap<>(columnCount);
                for (int i = 1; i <= columnCount; i++) {
                    row.put(columns.get(i - 1), convertValue(rs.getObject(i)));
                }
                rows.add(row);
            }
            sr.setColumns(columns);
            sr.setRows(rows);
            sr.setTotalRows(rows.size());
            sr.setTruncated(truncated);
        }
        return sr;
    }

    /** 值类型归一化：保证 JSON 序列化精度与可读性（与 execute_sql 工具历史行为一致） */
    private Object convertValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof BigInteger bigInt) {
            return bigInt.toString();
        }
        if (value instanceof BigDecimal bigDec) {
            return bigDec.toPlainString();
        }
        if (value instanceof Float || value instanceof Double) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof Number num) {
            return num.toString();
        }
        if (value instanceof Boolean) {
            return value;
        }
        if (value instanceof byte[] bytes) {
            return "（二进制数据 " + bytes.length + " 字节）";
        }
        return value.toString();
    }

    private int clampMaxRows(Integer maxRows) {
        if (maxRows == null || maxRows <= 0) {
            return DEFAULT_MAX_SELECT_ROWS;
        }
        return Math.min(maxRows, MAX_SELECT_ROWS_LIMIT);
    }

    // ==================== SQL 文本解析（私有） ====================

    /** 去除 SQL 语句前导注释（块注释与行注释） */
    private String stripComments(String sql) {
        return COMMENT_PATTERN.matcher(sql).replaceAll("").trim();
    }

    /** 检测 SQL 语句类型（去除注释后取第一个关键字） */
    private String detectSqlType(String cleanSql) {
        if (cleanSql.isEmpty()) {
            return "UNKNOWN";
        }
        String[] parts = cleanSql.split("\\s+");
        if (parts.length == 0) {
            return "UNKNOWN";
        }
        String keyword = parts[0].toUpperCase();
        return "SELECT".equals(keyword) ? "SELECT"
                : WRITE_KEYWORDS.contains(keyword) ? "WRITE"
                : "UNKNOWN";
    }

    /**
     * 将 SQL 按分号拆分为语句列表（引号/注释感知；空段与纯空白段忽略）。
     * <p>
     * 拆分后逐条执行：既保证 H2/MySQL/PostgreSQL 行为一致（不依赖驱动多语句能力），
     * 也让每条语句的结果（结果集/影响行数）与错误定位独立可用。
     * 支持：单引号（含 '' 双写与 \ 转义）、双引号、-- 行注释、块注释。
     * </p>
     */
    private List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';

            if (inLineComment) {
                current.append(c);
                if (c == '\n') {
                    inLineComment = false;
                }
                continue;
            }
            if (inBlockComment) {
                current.append(c);
                if (c == '*' && next == '/') {
                    current.append(next);
                    i++;
                    inBlockComment = false;
                }
                continue;
            }
            if (!inSingleQuote && !inDoubleQuote) {
                if (c == '-' && next == '-') {
                    current.append(c).append(next);
                    i++;
                    inLineComment = true;
                    continue;
                }
                if (c == '/' && next == '*') {
                    current.append(c).append(next);
                    i++;
                    inBlockComment = true;
                    continue;
                }
                if (c == ';') {
                    addStatement(statements, current);
                    continue;
                }
            }
            if (c == '\'' && !inDoubleQuote) {
                current.append(c);
                if (inSingleQuote && next == '\'') {
                    // '' 双写转义：字面量内的单引号，保持字符串内
                    current.append(next);
                    i++;
                } else {
                    inSingleQuote = !inSingleQuote;
                }
                continue;
            }
            if (c == '"' && !inSingleQuote) {
                current.append(c);
                if (inDoubleQuote && next == '"') {
                    current.append(next);
                    i++;
                } else {
                    inDoubleQuote = !inDoubleQuote;
                }
                continue;
            }
            if (c == '\\' && inSingleQuote) {
                // 反斜杠转义（MySQL 风格）：连同下一字符一并吞入字符串
                current.append(c);
                if (i + 1 < sql.length()) {
                    current.append(sql.charAt(i + 1));
                    i++;
                }
                continue;
            }
            current.append(c);
        }
        addStatement(statements, current);
        return statements;
    }

    /** 收尾当前语句段（去首尾空白；空段忽略）并清空缓冲 */
    private void addStatement(List<String> statements, StringBuilder current) {
        String stmt = current.toString().trim();
        if (!stmt.isEmpty()) {
            statements.add(stmt);
        }
        current.setLength(0);
    }
}
