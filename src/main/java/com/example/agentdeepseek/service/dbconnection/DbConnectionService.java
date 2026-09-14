package com.example.agentdeepseek.service.dbconnection;

import com.example.agentdeepseek.mapper.DbConnectionMapper;
import com.example.agentdeepseek.model.entity.DbConnection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 外部数据库连接配置服务（Phase 20）
 * <p>
 * CRUD + 测试连接 + 工具解析。安全约定：密码密文存储（CredentialCipher AES-GCM），
 * 所有出参脱敏（passwordEncrypted/passwordPlain 置空，仅 hasPassword 布尔标记）。
 * </p>
 */
@Slf4j
@Service
public class DbConnectionService {

    private final DbConnectionMapper dbConnectionMapper;
    private final CredentialCipher credentialCipher;
    private final DbConnectionManager dbConnectionManager;

    public DbConnectionService(DbConnectionMapper dbConnectionMapper,
                               CredentialCipher credentialCipher,
                               DbConnectionManager dbConnectionManager) {
        this.dbConnectionMapper = dbConnectionMapper;
        this.credentialCipher = credentialCipher;
        this.dbConnectionManager = dbConnectionManager;
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
