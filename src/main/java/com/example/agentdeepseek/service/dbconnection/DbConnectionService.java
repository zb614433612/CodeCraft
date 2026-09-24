package com.example.agentdeepseek.service.dbconnection;

import com.example.agentdeepseek.mapper.DbConnectionMapper;
import com.example.agentdeepseek.model.entity.DbConnection;
import com.example.agentdeepseek.model.vo.SqlExecuteResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 外部数据库连接配置服务（Phase 20）
 * <p>
 * CRUD + 测试连接 + 工具解析 + 管理页执行 SQL。安全约定：密码密文存储（CredentialCipher AES-GCM），
 * 所有出参脱敏（passwordEncrypted/passwordPlain 置空，仅 hasPassword 布尔标记）。
 * </p>
 */
@Slf4j
@Service
public class DbConnectionService {

    private final DbConnectionMapper dbConnectionMapper;
    private final CredentialCipher credentialCipher;
    private final DbConnectionManager dbConnectionManager;
    private final SqlExecutor sqlExecutor;

    public DbConnectionService(DbConnectionMapper dbConnectionMapper,
                               CredentialCipher credentialCipher,
                               DbConnectionManager dbConnectionManager,
                               SqlExecutor sqlExecutor) {
        this.dbConnectionMapper = dbConnectionMapper;
        this.credentialCipher = credentialCipher;
        this.dbConnectionManager = dbConnectionManager;
        this.sqlExecutor = sqlExecutor;
    }

    /** 可见连接列表（系统级 + 本人），出参脱敏 */
    public List<DbConnection> list(Long userId) {
        List<DbConnection> rows = dbConnectionMapper.selectVisible(userId);
        List<DbConnection> result = new ArrayList<>(rows.size());
        for (DbConnection c : rows) {
            result.add(sanitize(c));
        }
        return result;
    }

    /** 创建连接：密码加密入库 */
    public DbConnection create(DbConnection c) {
        validate(c, true);
        LocalDateTime now = LocalDateTime.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        if (c.getEnabled() == null) {
            c.setEnabled(1);
        }
        c.setPasswordEncrypted(credentialCipher.encrypt(c.getPasswordPlain()));
        dbConnectionMapper.insert(c);
        log.info("创建数据库连接: id={}, name={}, type={}, host={}", c.getId(), c.getName(), c.getDbType(), c.getHost());
        return sanitize(c);
    }

    /** 更新连接：passwordPlain 非空才重加密；为空保留原密文 */
    public DbConnection update(DbConnection c) {
        if (c.getId() == null) {
            throw new IllegalArgumentException("缺少连接 id");
        }
        DbConnection existing = dbConnectionMapper.selectById(c.getId())
                .orElseThrow(() -> new IllegalArgumentException("连接不存在: id=" + c.getId()));
        validate(c, false);
        c.setCreatedAt(existing.getCreatedAt());
        c.setUpdatedAt(LocalDateTime.now());
        if (c.getPasswordPlain() != null && !c.getPasswordPlain().isEmpty()) {
            c.setPasswordEncrypted(credentialCipher.encrypt(c.getPasswordPlain()));
        } else {
            c.setPasswordEncrypted(existing.getPasswordEncrypted());
        }
        dbConnectionMapper.update(c);
        log.info("更新数据库连接: id={}, name={}", c.getId(), c.getName());
        return sanitize(c);
    }

    public void delete(Long id) {
        dbConnectionMapper.delete(id);
        log.info("删除数据库连接: id={}", id);
    }

    /** 测试连接：入参可为未保存的配置（带 passwordPlain）或仅 id（服务端解密已存密码） */
    public String test(DbConnection c) {
        DbConnection target = c;
        if (c.getId() != null && (c.getPasswordPlain() == null || c.getPasswordPlain().isEmpty())) {
            DbConnection stored = dbConnectionMapper.selectById(c.getId())
                    .orElseThrow(() -> new IllegalArgumentException("连接不存在: id=" + c.getId()));
            target = stored;
        }
        // dbType 缺省（前端编辑只传 id 时）从已存配置补齐
        if (target.getDbType() == null && c.getDbType() != null) {
            target.setDbType(c.getDbType());
        }
        return dbConnectionManager.testConnection(target);
    }

    /**
     * 工具解析：execute_sql connection 参数（名称或 id）→ 启用连接配置（含解密明文）。
     *
     * @return 命中返回配置；未命中/已停用返回 null
     */
    public DbConnection resolveForTool(String ref) {
        DbConnection c = dbConnectionManager.resolve(ref);
        if (c == null) {
            return null;
        }
        if (c.getEnabled() == null || c.getEnabled() != 1) {
            log.warn("数据库连接已停用，拒绝工具访问: name={}, id={}", c.getName(), c.getId());
            return null;
        }
        return c;
    }

    /**
     * 执行 SQL（管理页「执行 SQL」）：校验连接可见性与启用状态后执行，返回结构化多结果。
     * 宽松模式：允许任意语句与多条语句（分号分隔按顺序执行）；MySQL 多语句依赖
     * allowMultiQueries=true（DbConnectionManager 构建 URL 时默认开启）。
     *
     * @param userId  当前用户（连接可见性校验：系统级或本人）
     * @param id      连接 id
     * @param sql     SQL 语句（支持多条，按顺序执行）
     * @param maxRows 每个查询结果集的最大返回行数（可选，默认 200，上限 1000）
     */
    public SqlExecuteResult executeSql(Long userId, Long id, String sql, Integer maxRows) {
        // 宽松检查（管理页：允许任意语句与多条语句）
        SqlExecutor.CheckResult check = sqlExecutor.checkUi(sql);
        if (!check.ok()) {
            throw new IllegalArgumentException(uiCheckErrorMessage(check));
        }
        DbConnection conn = dbConnectionMapper.selectById(id)
                .orElseThrow(() -> new IllegalArgumentException("连接不存在: id=" + id));
        if (conn.getUserId() != null && !conn.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权访问该数据库连接");
        }
        if (conn.getEnabled() == null || conn.getEnabled() != 1) {
            throw new IllegalArgumentException("连接已停用，请先启用后再执行 SQL");
        }
        conn.setPasswordPlain(credentialCipher.decrypt(conn.getPasswordEncrypted()));
        String dbLabel = buildDbLabel(conn);
        log.info("管理页执行 SQL: id={}, name={}, sqlLength={}", id, conn.getName(), sql.length());
        try (Connection physical = dbConnectionManager.open(conn)) {
            return sqlExecutor.execute(physical, sql, dbLabel, maxRows);
        } catch (SQLException e) {
            // try-with-resources 关闭连接异常（执行异常为 RuntimeException，由 Controller 统一处理）
            log.warn("执行 SQL 关闭连接异常: id={}, err={}", id, e.getMessage());
            throw new RuntimeException("执行失败: " + e.getMessage(), e);
        }
    }

    /** 构建目标库描述（结果标注用，避免与系统库混淆） */
    private String buildDbLabel(DbConnection conn) {
        String hostLabel = "h2".equals(conn.getDbType())
                ? conn.getDatabaseName()
                : conn.getHost() + ":" + (conn.getPort() != null ? conn.getPort() : "默认端口") + "/" + conn.getDatabaseName();
        return "外部库[" + conn.getName() + "] " + conn.getDbType() + "@" + hostLabel;
    }

    /** 宽松检查失败 → 用户提示消息 */
    private String uiCheckErrorMessage(SqlExecutor.CheckResult check) {
        return switch (check.error()) {
            case EMPTY -> "SQL 不能为空";
            case TOO_LONG -> "SQL 语句 " + check.sqlLength() + " 字符超出 " + SqlExecutor.MAX_SQL_LENGTH_UI + " 字符限制，请精简后重试";
            default -> "SQL 检查未通过：" + check.error();
        };
    }

    /** 出参脱敏：密文/明文密码一律置空，仅暴露 hasPassword */
    private DbConnection sanitize(DbConnection c) {
        boolean hasPwd = c.getPasswordEncrypted() != null && !c.getPasswordEncrypted().isEmpty();
        c.setPasswordEncrypted(null);
        c.setPasswordPlain(null);
        c.setHasPassword(hasPwd);
        return c;
    }

    private void validate(DbConnection c, boolean creating) {
        if (creating && (c.getName() == null || c.getName().isBlank())) {
            throw new IllegalArgumentException("连接名称不能为空（execute_sql 用名称或 id 指定）");
        }
        if (creating && (c.getDbType() == null || c.getDbType().isBlank())) {
            throw new IllegalArgumentException("数据库类型不能为空（mysql / postgresql / h2）");
        }
        String type = c.getDbType() == null ? "" : c.getDbType().toLowerCase();
        if (!List.of("mysql", "postgresql", "h2").contains(type)) {
            throw new IllegalArgumentException("不支持的数据库类型: " + c.getDbType() + "（仅支持 mysql / postgresql / h2）");
        }
        c.setDbType(type);
        if (!"h2".equals(type) && (c.getHost() == null || c.getHost().isBlank())) {
            throw new IllegalArgumentException("主机地址不能为空");
        }
        if (c.getDatabaseName() == null || c.getDatabaseName().isBlank()) {
            throw new IllegalArgumentException("数据库名不能为空");
        }
        // 密码：创建时必须提供（除 h2 无密码场景）；更新时可选（空=不改）
        if (creating && (c.getPasswordPlain() == null || c.getPasswordPlain().isEmpty())
                && !(c.getUsername() == null || c.getUsername().isBlank() || "sa".equals(c.getUsername()))) {
            throw new IllegalArgumentException("密码不能为空");
        }
    }
}
