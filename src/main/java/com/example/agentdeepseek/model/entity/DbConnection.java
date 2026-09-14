package com.example.agentdeepseek.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 外部数据库连接配置（Phase 20）
 * <p>
 * 用户在管理页配置业务数据库（IP/账号/密码），AI 通过 execute_sql 的 connection
 * 参数指定连接执行 SQL；不指定时仍连 CodeCraft 自身系统库。
 * 密码以 {@code passwordEncrypted}（AES-GCM）存储，API 不回显明文。
 * </p>
 */
@Data
public class DbConnection {

    private Long id;

    /** 连接名称（execute_sql connection 参数：名称或 id），如「订单库」 */
    private String name;

    /** 数据库类型：mysql / postgresql / h2 */
    private String dbType;

    /** 主机地址（h2 file 模式可空，文件路径放 databaseName） */
    private String host;

    /** 端口（mysql 3306 / postgresql 5432，可空用默认） */
    private Integer port;

    /** 数据库名（h2 file 模式为 .mv.db 文件路径） */
    private String databaseName;

    /** 用户名（h2 默认 sa） */
    private String username;

    /** 密码密文（AES-GCM Base64）——不回显 */
    private String passwordEncrypted;

    /** JDBC URL 附加参数，如 useSSL=false&serverTimezone=Asia/Shanghai */
    private String extraParams;

    /** 是否启用：1=启用 0=停用 */
    private Integer enabled;

    /** 归属用户（null=系统级共享） */
    private Long userId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ============ 非表字段（入参/出参辅助） ============

    /** 创建/更新时传入的明文密码（不入库；留空=不修改密码） */
    private String passwordPlain;

    /** 展示用：是否已配置密码（不回显明文） */
    private Boolean hasPassword;
}
