package com.example.agentdeepseek.service.dbconnection;

import com.example.agentdeepseek.mapper.DbConnectionMapper;
import com.example.agentdeepseek.model.entity.DbConnection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * 外部数据库连接管理器（Phase 20）
 * <p>
 * 负责：JDBC URL 构建（mysql/postgresql/h2）、按引用（id/名称）解析连接、
 * 打开物理连接（按需建连，无连接池——execute_sql 低频调用，避免池泄漏管理）、
 * 测试连接。密码从 DbConnectionMapper 读密文后经 CredentialCipher 解密。
 * </p>
 */
@Slf4j
@Component
public class DbConnectionManager {

    private final DbConnectionMapper dbConnectionMapper;
    private final CredentialCipher credentialCipher;

    public DbConnectionManager(DbConnectionMapper dbConnectionMapper, CredentialCipher credentialCipher) {
        this.dbConnectionMapper = dbConnectionMapper;
        this.credentialCipher = credentialCipher;
    }

    // ==================== 解析 ====================

    /**
     * 按引用解析连接配置：纯数字=id；否则按名称（忽略大小写、enabled=1 优先）。
     *
     * @return 连接配置（含解密后的明文密码临时置于 passwordPlain；不含则返回 null）
     */
    public DbConnection resolve(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String trimmed = ref.trim();
        // 先按 id
        if (trimmed.matches("\\d+")) {
            Optional<DbConnection> byId = dbConnectionMapper.selectById(Long.parseLong(trimmed));
            if (byId.isPresent()) {
                fillPassword(byId.get());
                return byId.get();
            }
        }
        // 再按名称（忽略大小写；优先启用的）
        List<DbConnection> all = dbConnectionMapper.selectAll();
        for (DbConnection c : all) {
            if (trimmed.equalsIgnoreCase(c.getName())) {
                fillPassword(c);
                return c;
            }
        }
        return null;
    }

    /** 填充明文密码到 passwordPlain（仅内存使用，不落库不回显） */
    private void fillPassword(DbConnection c) {
        String plain = credentialCipher.decrypt(c.getPasswordEncrypted());
        c.setPasswordPlain(plain);
    }

    // ==================== URL 构建 ====================

    /** 构建 JDBC URL（mysql/postgresql/h2），extraParams 为 URL 查询参数（& 分隔） */
    public String buildJdbcUrl(DbConnection c) {
        String type = c.getDbType() == null ? "mysql" : c.getDbType().toLowerCase();
        String extra = (c.getExtraParams() == null || c.getExtraParams().isBlank()) ? "" : c.getExtraParams().trim();
        String url;
        switch (type) {
            case "postgresql" -> {
                int port = c.getPort() != null ? c.getPort() : 5432;
                url = "jdbc:postgresql://" + c.getHost() + ":" + port + "/" + c.getDatabaseName();
            }
            case "h2" -> {
                // databaseName 为 .mv.db 文件路径（不含扩展名或含均可）
                String db = c.getDatabaseName();
                if (db == null || db.isBlank()) {
                    throw new IllegalArgumentException("h2 连接需要 database_name（文件路径）");
                }
                String file = db.toLowerCase().endsWith(".mv.db") ? db.substring(0, db.length() - 6) : db;
                url = "jdbc:h2:file:" + file + ";MODE=MySQL;DATABASE_TO_UPPER=false";
                if (!extra.isEmpty()) {
                    url += ";" + extra;
                }
                return url;
            }
            default -> { // mysql
                int port = c.getPort() != null ? c.getPort() : 3306;
                url = "jdbc:mysql://" + c.getHost() + ":" + port + "/" + c.getDatabaseName();
            }
        }
        if (!extra.isEmpty()) {
            url += (url.contains("?") ? "&" : "?") + extra;
        }
        return url;
    }

    /** 打开物理连接（调用方负责 try-with-resources 关闭） */
    public Connection open(DbConnection c) {
        try {
            String url = buildJdbcUrl(c);
            Class.forName(driverClassName(c.getDbType()));
            Properties props = new Properties();
            if (c.getUsername() != null && !c.getUsername().isBlank()) {
                props.setProperty("user", c.getUsername());
            }
            if (c.getPasswordPlain() != null) {
                props.setProperty("password", c.getPasswordPlain());
            }
            return DriverManager.getConnection(url, props);
        } catch (Exception e) {
            log.warn("打开外部数据库连接失败: name={}, type={}, host={}, err={}",
                    c.getName(), c.getDbType(), c.getHost(), e.getMessage());
            throw new RuntimeException("连接失败: " + e.getMessage(), e);
        }
    }

    private String driverClassName(String dbType) {
        String type = dbType == null ? "mysql" : dbType.toLowerCase();
        return switch (type) {
            case "postgresql" -> "org.postgresql.Driver";
            case "h2" -> "org.h2.Driver";
            default -> "com.mysql.cj.jdbc.Driver";
        };
    }

    // ==================== 测试连接 ====================

    /** 测试连接：成功返回 null；失败返回错误信息（已脱敏去掉堆栈） */
    public String testConnection(DbConnection c) {
        // 仅当调用方未直接提供明文密码时才从密文解密补全：
        // 未保存配置的测试场景前端传的是 passwordPlain（passwordEncrypted 为空），
        // 若无条件用解密结果覆盖，会把明文密码置 null，导致连接"无密码"裸连被拒。
        if (c.getPasswordPlain() == null || c.getPasswordPlain().isEmpty()) {
            c.setPasswordPlain(credentialCipher.decrypt(c.getPasswordEncrypted()));
        }
        try (Connection ignored = open(c)) {
            return null;
        } catch (Exception e) {
            log.warn("测试数据库连接失败: name={}, type={}, host={}, err={}",
                    c.getName(), c.getDbType(), c.getHost(), e.getMessage());
            return e.getMessage();
        }
    }
}
