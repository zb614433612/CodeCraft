package com.example.agentdeepseek.mapper;

import com.example.agentdeepseek.model.entity.LessonNormCache;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 归一化缓存数据访问（P0 归一化管线）
 * 读写 lesson_norm_cache 表：缓存键查询 / upsert / TTL 清理 / 容量裁剪
 */
@Mapper
@Repository
public interface LessonNormCacheMapper {

    String COLUMNS = "cache_key, error_category, error_code, tool_name, root_cause, solution, created_at, updated_at";

    /** 按缓存键查询（TTL 判断在 Service 层：updated_at 超期视为未命中） */
    @Select("SELECT " + COLUMNS + " FROM lesson_norm_cache WHERE cache_key = #{cacheKey}")
    @Results(id = "normCacheResultMap", value = {
            @Result(property = "cacheKey", column = "cache_key"),
            @Result(property = "errorCategory", column = "error_category"),
            @Result(property = "errorCode", column = "error_code"),
            @Result(property = "toolName", column = "tool_name"),
            @Result(property = "rootCause", column = "root_cause"),
            @Result(property = "solution", column = "solution"),
            @Result(property = "createdAt", column = "created_at"),
            @Result(property = "updatedAt", column = "updated_at")
    })
    Optional<LessonNormCache> selectByKey(@Param("cacheKey") String cacheKey);

    /** 写入缓存 */
    @Insert("INSERT INTO lesson_norm_cache (cache_key, error_category, error_code, tool_name, root_cause, solution, created_at, updated_at) " +
            "VALUES (#{cacheKey}, #{errorCategory}, #{errorCode}, #{toolName}, #{rootCause}, #{solution}, #{createdAt}, #{updatedAt})")
    int insert(LessonNormCache cache);

    /** 更新缓存内容与最后命中时间 */
    @Update("UPDATE lesson_norm_cache SET error_category = #{errorCategory}, error_code = #{errorCode}, " +
            "tool_name = #{toolName}, root_cause = #{rootCause}, solution = #{solution}, updated_at = #{updatedAt} " +
            "WHERE cache_key = #{cacheKey}")
    int update(LessonNormCache cache);

    /** 清理过期条目（updated_at < 阈值；TTL 由 Service 计算） */
    @Delete("DELETE FROM lesson_norm_cache WHERE updated_at < #{before}")
    int deleteExpired(@Param("before") LocalDateTime before);

    /** 总条数（容量裁剪判定） */
    @Select("SELECT COUNT(*) FROM lesson_norm_cache")
    long count();

    /** 裁剪最旧 N 条（容量超限时按最后命中时间清理） */
    @Delete("DELETE FROM lesson_norm_cache WHERE cache_key IN " +
            "(SELECT cache_key FROM (SELECT cache_key FROM lesson_norm_cache ORDER BY updated_at ASC LIMIT #{n}) t)")
    int deleteOldest(@Param("n") int n);
}
