package com.example.agentdeepseek.mapper;

import com.example.agentdeepseek.model.entity.DbConnection;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 外部数据库连接配置数据访问接口（Phase 20）
 */
@Mapper
@Repository
public interface DbConnectionMapper {

    @Select("SELECT id, name, db_type, host, port, database_name, username, password_encrypted, extra_params, " +
            "enabled, user_id, created_at, updated_at FROM db_connection WHERE id = #{id}")
    @Results(id = "dbConnectionResultMap", value = {
        @Result(property = "id", column = "id"),
        @Result(property = "name", column = "name"),
        @Result(property = "dbType", column = "db_type"),
        @Result(property = "host", column = "host"),
        @Result(property = "port", column = "port"),
        @Result(property = "databaseName", column = "database_name"),
        @Result(property = "username", column = "username"),
        @Result(property = "passwordEncrypted", column = "password_encrypted"),
        @Result(property = "extraParams", column = "extra_params"),
        @Result(property = "enabled", column = "enabled"),
        @Result(property = "userId", column = "user_id"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    Optional<DbConnection> selectById(Long id);

    /** 可见连接列表：系统级 + 本人（按创建时间倒序） */
    @Select("SELECT id, name, db_type, host, port, database_name, username, password_encrypted, extra_params, " +
            "enabled, user_id, created_at, updated_at FROM db_connection " +
            "WHERE user_id IS NULL OR user_id = #{userId} OR #{userId} IS NULL ORDER BY created_at DESC")
    @ResultMap("dbConnectionResultMap")
    List<DbConnection> selectVisible(@Param("userId") Long userId);

    /** 全部连接（admin 用） */
    @Select("SELECT id, name, db_type, host, port, database_name, username, password_encrypted, extra_params, " +
            "enabled, user_id, created_at, updated_at FROM db_connection ORDER BY created_at DESC")
    @ResultMap("dbConnectionResultMap")
    List<DbConnection> selectAll();

    @Insert("INSERT INTO db_connection (name, db_type, host, port, database_name, username, password_encrypted, " +
            "extra_params, enabled, user_id, created_at, updated_at) " +
            "VALUES (#{name}, #{dbType}, #{host}, #{port}, #{databaseName}, #{username}, #{passwordEncrypted}, " +
            "#{extraParams}, #{enabled}, #{userId}, #{createdAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(DbConnection connection);

    @Update("UPDATE db_connection SET name = #{name}, db_type = #{dbType}, host = #{host}, port = #{port}, " +
            "database_name = #{databaseName}, username = #{username}, " +
            "password_encrypted = #{passwordEncrypted}, extra_params = #{extraParams}, " +
            "enabled = #{enabled}, updated_at = #{updatedAt} WHERE id = #{id}")
    int update(DbConnection connection);

    @Delete("DELETE FROM db_connection WHERE id = #{id}")
    int delete(Long id);
}
