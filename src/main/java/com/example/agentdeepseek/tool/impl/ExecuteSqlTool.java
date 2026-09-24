package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.model.entity.DbConnection;
import com.example.agentdeepseek.model.vo.SqlExecuteResult;
import com.example.agentdeepseek.service.dbconnection.DbConnectionManager;
import com.example.agentdeepseek.service.dbconnection.DbConnectionService;
import com.example.agentdeepseek.service.dbconnection.SqlExecutor;
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

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * SQL 查询工具（Phase 20 支持外部连接）
 * 执行 SELECT 查询（自动 LIMIT 保护），增删改操作需获得执行权限。
 * connection 参数可选：不传连 CodeCraft 系统库（默认）；传连接名称/id 连用户在
 * 「数据库连接」管理页配置的外部业务库（MySQL/PostgreSQL/H2）。
 * <p>
 * SQL 检查与执行逻辑由 {@link SqlExecutor} 共享（与「数据库连接」管理页「执行 SQL」同源）；
 * 本工具保持严格模式：仅允许单条 SELECT/写操作语句，执行完后连接即关。
 * </p>
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.DATABASE, affectsData = true, highRisk = true, description = "执行SQL语句")
public class ExecuteSqlTool implements Tool {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final DbConnectionManager dbConnectionManager;
    private final DbConnectionService dbConnectionService;
    private final SqlExecutor sqlExecutor;

    public ExecuteSqlTool(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                          DbConnectionManager dbConnectionManager,
                          DbConnectionService dbConnectionService,
                          SqlExecutor sqlExecutor) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.dbConnectionManager = dbConnectionManager;
        this.dbConnectionService = dbConnectionService;
        this.sqlExecutor = sqlExecutor;
    }

    @Override
    public String getName() {
        return "execute_sql";
    }

    @Override
    public String getDescription() {
        return "执行 SQL 查询和数据库修改操作。\n"
                + "【适用场景】查询数据库表结构、验证数据是否写入成功、排查数据异常、执行必要的增删改操作\n"
                + "【指定连接（可选）】connection 参数=「数据库连接」管理页配置的外部业务库名称或 id（如 订单库），"
                + "不传则连 CodeCraft 自身系统库。连接外部库前先确认用户已配置该连接，查表结构可先 SHOW TABLES / SELECT * FROM information_schema.tables\n"
                + "【使用方式】SELECT 查询：直接传入 SQL（自动追加 LIMIT " + SqlExecutor.DEFAULT_MAX_SELECT_ROWS + " 防止全表返回）。手动模式下所有 SQL 操作均需用户授权；自动模式下高危操作（DDL/DROP 等）仍需授权\n"
                + "【注意】不支持多语句（以分号分隔的多条 SQL），每次只执行一条；SQL 最大 " + SqlExecutor.MAX_SQL_LENGTH + " 字符；SELECT 建议加 WHERE 条件避免全表扫描";
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

        ObjectNode connection = objectMapper.createObjectNode();
        connection.put("type", "string");
        connection.put("description", "【可选】外部数据库连接名称或 id（在「数据库连接」管理页配置），如 订单库 或 3。不传默认连 CodeCraft 自身系统库");
        properties.set("connection", connection);

        parameters.set("properties", properties);
        parameters.putArray("required").add("sql");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String sql = arguments.path("sql").asText().trim();

        // ===== 安全检查（严格模式：单语句 + SELECT/写操作白名单；与「数据库连接」管理页执行 SQL 共享逻辑）=====
        SqlExecutor.CheckResult check = sqlExecutor.checkStrict(sql);
        if (!check.ok()) {
            return renderCheckError(check);
        }

        // ===== Phase 20：解析目标连接（connection 可选；不传=CodeCraft 系统库）=====
        String dbLabel = "CodeCraft 系统库";
        String connectionRef = arguments.path("connection").asText("").trim();
        Connection physical;
        try {
            if (connectionRef.isEmpty()) {
                DataSource dataSource = jdbcTemplate.getDataSource();
                if (dataSource == null) {
                    return "【错误类型】【系统库不可用】CodeCraft 系统库数据源未初始化。请去掉 connection 参数重试，或联系管理员检查服务配置";
                }
                physical = dataSource.getConnection();
            } else {
                DbConnection connCfg = dbConnectionService.resolveForTool(connectionRef);
                if (connCfg == null) {
                    return "【连接不存在】未找到启用中的数据库连接 \"" + connectionRef + "\"。\n"
                            + "【建议】在「数据库连接」管理页确认连接名称/id 及启用状态；或去掉 connection 参数查询 CodeCraft 自身系统库";
                }
                physical = dbConnectionManager.open(connCfg);
                String hostLabel = "h2".equals(connCfg.getDbType())
                        ? connCfg.getDatabaseName()
                        : connCfg.getHost() + ":" + (connCfg.getPort() != null ? connCfg.getPort() : "默认端口")
                        + "/" + connCfg.getDatabaseName();
                dbLabel = "外部库[" + connCfg.getName() + "] " + connCfg.getDbType() + "@" + hostLabel;
                log.info("execute_sql 使用外部连接: ref={}, name={}, type={}", connectionRef, connCfg.getName(), connCfg.getDbType());
            }
        } catch (Exception e) {
            if (!connectionRef.isEmpty()) {
                log.error("外部连接打开失败: ref={}, err={}", connectionRef, e.getMessage(), e);
                return "【连接失败】无法连接外部数据库 \"" + connectionRef + "\"：" + e.getMessage() + "\n"
                        + "【建议】在「数据库连接」管理页用「测试连接」检查配置";
            }
            log.error("系统库连接打开失败: err={}", e.getMessage(), e);
            return renderExecutionError(e, dbLabel);
        }

        // ===== 执行（连接用后即关）=====
        try (Connection conn = physical) {
            SqlExecuteResult result = sqlExecutor.execute(conn, sql, dbLabel, SqlExecutor.DEFAULT_MAX_SELECT_ROWS);
            SqlExecuteResult.StatementResult first = result.getResults().isEmpty()
                    ? null : result.getResults().get(0);
            if (first != null && "SELECT".equals(first.getSqlType())) {
                return renderSelectResult(result, first);
            }
            return renderWriteResult(result, first);
        } catch (Exception e) {
            log.error("SQL 执行失败: target={}, sql={}", dbLabel, sql, e);
            return renderExecutionError(e, dbLabel);
        }
    }

    /** 检查失败 → 给 LLM 的结构化错误消息（与重构前保持一致） */
    private String renderCheckError(SqlExecutor.CheckResult check) {
        return switch (check.error()) {
            case EMPTY -> "【错误类型】【缺少参数】sql 字段缺失或为空。请提供一条完整的 SQL 语句。示例：{\"sql\": \"SELECT * FROM users WHERE id = 1\"}";
            case TOO_LONG -> "【错误类型】【SQL 过长】SQL 语句 " + check.sqlLength() + " 字符超出" + SqlExecutor.MAX_SQL_LENGTH + "字符限制。请精简查询条件，或拆分为多次调用（分别查询不同表/不同条件）";
            case MULTIPLE_STATEMENTS -> "【错误类型】【多语句禁止】检测到多条 SQL 语句（包含分号分隔）。请每次只执行一条 SQL，将多条语句拆分为多次独立调用。例如 UPDATE 和 SELECT 分两次调用";
            case UNKNOWN_TYPE -> "【错误类型】【SQL 类型未知】无法识别的 SQL 语句类型。仅支持：SELECT 查询 和 写操作（INSERT/UPDATE/DELETE/ALTER/CREATE/DROP/TRUNCATE）。请检查 SQL 开头关键字是否正确，去掉前导注释后重试";
        };
    }

    /** SELECT 结果 → 文本（保持原输出格式：目标库描述 + 行数 + JSON 数组） */
    private String renderSelectResult(SqlExecuteResult result, SqlExecuteResult.StatementResult first) {
        ArrayNode resultArray = objectMapper.valueToTree(first.getRows());
        StringBuilder summary = new StringBuilder("目标库：").append(result.getDbLabel())
                .append("\n查询完成，返回 ").append(first.getTotalRows()).append(" 行");
        if (Boolean.TRUE.equals(first.getTruncated())) {
            summary.append("（受 ").append(SqlExecutor.DEFAULT_MAX_SELECT_ROWS).append(" 行限制，结果可能不完整）");
        }
        return summary + "\n" + resultArray.toPrettyString();
    }

    /** WRITE 结果 → 文本（保持原输出格式：目标库描述 + 影响行数） */
    private String renderWriteResult(SqlExecuteResult result, SqlExecuteResult.StatementResult first) {
        int affected = first == null || first.getAffectedRows() == null ? 0 : first.getAffectedRows();
        return "目标库：" + result.getDbLabel() + "\n执行成功，影响 " + affected + " 行";
    }

    /** 执行异常 → 给 LLM 的结构化错误消息（保持原格式：异常类名 + 消息 + 排查建议） */
    private String renderExecutionError(Exception e, String dbLabel) {
        return "【错误类型】【SQL 执行异常】" + e.getClass().getSimpleName() + ": " + e.getMessage()
                + "。建议检查：① 表名/字段名是否存在 ② 字段类型是否匹配 ③ 约束条件是否冲突（如唯一索引重复）④ 外键依赖是否满足 ⑤ 目标库为 " + dbLabel;
    }
}
