package com.example.agentdeepseek.mapper;

import com.example.agentdeepseek.model.entity.Lesson;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 踩坑经验数据访问接口
 * 确定性索引：error_signature 唯一去重 + (project_key, tool_name, error_category, error_code) 复合索引硬过滤
 */
@Mapper
@Repository
public interface LessonMapper {

    String COLUMNS = "id, project_key, tool_name, error_category, error_code, error_signature, " +
            "symptom, root_cause, solution, params_json, applicable_cond, keywords, " +
            "status, source, hit_count, success_count, fail_count, created_at, updated_at";

    @Insert("INSERT INTO lesson (project_key, tool_name, error_category, error_code, error_signature, " +
            "symptom, root_cause, solution, params_json, applicable_cond, keywords, " +
            "status, source, hit_count, success_count, fail_count, created_at, updated_at) " +
            "VALUES (#{projectKey}, #{toolName}, #{errorCategory}, #{errorCode}, #{errorSignature}, " +
            "#{symptom}, #{rootCause}, #{solution}, #{paramsJson}, #{applicableCond}, #{keywords}, " +
            "#{status}, #{source}, #{hitCount}, #{successCount}, #{failCount}, #{createdAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Lesson lesson);

    @Select("SELECT " + COLUMNS + " FROM lesson WHERE id = #{id}")
    @Results(id = "lessonResultMap", value = {
        @Result(property = "id", column = "id"),
        @Result(property = "projectKey", column = "project_key"),
        @Result(property = "toolName", column = "tool_name"),
        @Result(property = "errorCategory", column = "error_category"),
        @Result(property = "errorCode", column = "error_code"),
        @Result(property = "errorSignature", column = "error_signature"),
        @Result(property = "symptom", column = "symptom"),
        @Result(property = "rootCause", column = "root_cause"),
        @Result(property = "solution", column = "solution"),
        @Result(property = "paramsJson", column = "params_json"),
        @Result(property = "applicableCond", column = "applicable_cond"),
        @Result(property = "keywords", column = "keywords"),
        @Result(property = "status", column = "status"),
        @Result(property = "source", column = "source"),
        @Result(property = "hitCount", column = "hit_count"),
        @Result(property = "successCount", column = "success_count"),
        @Result(property = "failCount", column = "fail_count"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    Optional<Lesson> selectById(Long id);

    /** 指纹查重：同坑不重复入库 */
    @Select("SELECT " + COLUMNS + " FROM lesson WHERE error_signature = #{signature} LIMIT 1")
    @ResultMap("lessonResultMap")
    Optional<Lesson> selectBySignature(@Param("signature") String signature);

    /**
     * 第一级硬过滤：project_key 必填 + tool_name/error_category/error_code 可选精确匹配
     * F6 全局共享池：project_key IN (当前项目, '__global__')，项目内经验优先，全局经验兜底
     * 排序：项目内优先 → 有效优先 → 命中次数 → 成功率（贝叶斯平滑，防除零）
     */
    @Select("<script>" +
            "SELECT " + COLUMNS + " FROM lesson " +
            "WHERE project_key IN (#{projectKey}, '" + Lesson.GLOBAL_PROJECT_KEY + "') AND status IN (0, 1)" +
            "<if test='toolName != null and toolName != \"\"'> AND tool_name = #{toolName}</if>" +
            "<if test='errorCategory != null and errorCategory != \"\"'> AND error_category = #{errorCategory}</if>" +
            "<if test='errorCode != null and errorCode != \"\"'> AND error_code = #{errorCode}</if>" +
            " ORDER BY CASE WHEN project_key = #{projectKey} THEN 0 ELSE 1 END, " +
            "status DESC, hit_count DESC, " +
            "(success_count + 1.0) / (success_count + fail_count + 2.0) DESC " +
            "LIMIT #{limit}" +
            "</script>")
    @ResultMap("lessonResultMap")
    List<Lesson> selectByScope(@Param("projectKey") String projectKey,
                                @Param("toolName") String toolName,
                                @Param("errorCategory") String errorCategory,
                                @Param("errorCode") String errorCode,
                                @Param("limit") int limit);

    /**
     * 第三级兜底模糊检索：project_key 必填 + 关键词 LIKE 匹配
     * F6 全局共享池：project_key IN (当前项目, '__global__')，项目内优先
     * 覆盖 symptom/root_cause/solution/keywords 四字段（H2 无 FULLTEXT，用 LIKE 顶住，量级小无压力）
     * 注：ESCAPE '\' 配合 Service 层 escapeLikeKeyword 转义，防止 % _ 被当通配符
     */
    @Select("<script>" +
            "SELECT " + COLUMNS + " FROM lesson " +
            "WHERE project_key IN (#{projectKey}, '" + Lesson.GLOBAL_PROJECT_KEY + "') AND status IN (0, 1)" +
            "<if test='toolName != null and toolName != \"\"'> AND tool_name = #{toolName}</if>" +
            " AND (symptom LIKE CONCAT('%', #{keyword}, '%') ESCAPE '\\' " +
            " OR root_cause LIKE CONCAT('%', #{keyword}, '%') ESCAPE '\\' " +
            " OR solution LIKE CONCAT('%', #{keyword}, '%') ESCAPE '\\' " +
            " OR keywords LIKE CONCAT('%', #{keyword}, '%') ESCAPE '\\')" +
            " ORDER BY CASE WHEN project_key = #{projectKey} THEN 0 ELSE 1 END, " +
            "status DESC, hit_count DESC, " +
            "(success_count + 1.0) / (success_count + fail_count + 2.0) DESC " +
            "LIMIT #{limit}" +
            "</script>")
    @ResultMap("lessonResultMap")
    List<Lesson> selectFuzzy(@Param("projectKey") String projectKey,
                              @Param("toolName") String toolName,
                              @Param("keyword") String keyword,
                              @Param("limit") int limit);

    /** 检索命中计数（原子） */
    @Update("UPDATE lesson SET hit_count = hit_count + 1, updated_at = #{updatedAt} WHERE id = #{id}")
    int incrementHitCount(@Param("id") Long id, @Param("updatedAt") java.time.LocalDateTime updatedAt);

    /**
     * 反馈：应用经验后有效
     * success_count + 1；草稿且成功 ≥2 次 → 自动转正 status=1
     */
    @Update("UPDATE lesson SET success_count = success_count + 1, " +
            "status = CASE WHEN status = 0 AND success_count + 1 >= 2 THEN 1 ELSE status END, " +
            "updated_at = #{updatedAt} WHERE id = #{id}")
    int feedbackSuccess(@Param("id") Long id, @Param("updatedAt") java.time.LocalDateTime updatedAt);

    /**
     * 反馈：应用经验后仍失败
     * fail_count + 1；连续失败 ≥5 次 → 自动隐藏 status=2
     */
    @Update("UPDATE lesson SET fail_count = fail_count + 1, " +
            "status = CASE WHEN fail_count + 1 >= 5 THEN 2 ELSE status END, " +
            "updated_at = #{updatedAt} WHERE id = #{id}")
    int feedbackFail(@Param("id") Long id, @Param("updatedAt") java.time.LocalDateTime updatedAt);

    /**
     * 补全/更新经验内容（LLM 模板提炼：把草稿完善成「模板+变量」结构）
     * 只更新非空字段，status 保持草稿（转正仍由反馈闭环驱动）
     */
    @Update("<script>" +
            "UPDATE lesson SET updated_at = #{updatedAt}" +
            "<if test='rootCause != null'> , root_cause = #{rootCause}</if>" +
            "<if test='solution != null'> , solution = #{solution}</if>" +
            "<if test='paramsJson != null'> , params_json = #{paramsJson}</if>" +
            "<if test='applicableCond != null'> , applicable_cond = #{applicableCond}</if>" +
            "<if test='keywords != null'> , keywords = #{keywords}</if>" +
            " WHERE id = #{id}" +
            "</script>")
    int updateContent(@Param("id") Long id,
                      @Param("rootCause") String rootCause,
                      @Param("solution") String solution,
                      @Param("paramsJson") String paramsJson,
                      @Param("applicableCond") String applicableCond,
                      @Param("keywords") String keywords,
                      @Param("updatedAt") java.time.LocalDateTime updatedAt);

    /**
     * 列出项目最近经验（巡检/管理用）
     * F6 全局共享池：project_key IN (当前项目, '__global__')，项目内优先
     * 排序：项目内优先 → 有效优先 → 命中次数 → 更新时间
     */
    @Select("<script>" +
            "SELECT " + COLUMNS + " FROM lesson " +
            "WHERE project_key IN (#{projectKey}, '" + Lesson.GLOBAL_PROJECT_KEY + "') AND status IN (0, 1)" +
            " ORDER BY CASE WHEN project_key = #{projectKey} THEN 0 ELSE 1 END, " +
            "status DESC, hit_count DESC, updated_at DESC " +
            "LIMIT #{limit}" +
            "</script>")
    @ResultMap("lessonResultMap")
    List<Lesson> selectRecent(@Param("projectKey") String projectKey, @Param("limit") int limit);

    // ============================================================
    // Phase 3：管理 API 支持（分页 / 统计 / 删除）
    // ============================================================

    /**
     * 分页查询（管理页面用）：project_key 必填 + status/tool_name/error_code 可选过滤
     * 排序：有效优先 → 命中次数 → 更新时间
     */
    @Select("<script>" +
            "SELECT " + COLUMNS + " FROM lesson " +
            "WHERE 1=1" +
            "<if test='projectKey != null and projectKey != \"\"'> AND project_key IN (#{projectKey}, '" + Lesson.GLOBAL_PROJECT_KEY + "')</if>" +
            "<if test='status != null'> AND status = #{status}</if>" +
            "<if test='toolName != null and toolName != \"\"'> AND tool_name = #{toolName}</if>" +
            "<if test='errorCode != null and errorCode != \"\"'> AND error_code = #{errorCode}</if>" +
            " ORDER BY status DESC, hit_count DESC, updated_at DESC " +
            "LIMIT #{size} OFFSET #{offset}" +
            "</script>")
    @ResultMap("lessonResultMap")
    List<Lesson> selectPage(@Param("projectKey") String projectKey,
                            @Param("status") Integer status,
                            @Param("toolName") String toolName,
                            @Param("errorCode") String errorCode,
                            @Param("offset") int offset,
                            @Param("size") int size);

    /** 分页总数（与 selectPage 同条件） */
    @Select("<script>" +
            "SELECT COUNT(*) FROM lesson " +
            "WHERE 1=1" +
            "<if test='projectKey != null and projectKey != \"\"'> AND project_key IN (#{projectKey}, '" + Lesson.GLOBAL_PROJECT_KEY + "')</if>" +
            "<if test='status != null'> AND status = #{status}</if>" +
            "<if test='toolName != null and toolName != \"\"'> AND tool_name = #{toolName}</if>" +
            "<if test='errorCode != null and errorCode != \"\"'> AND error_code = #{errorCode}</if>" +
            "</script>")
    long countByFilter(@Param("projectKey") String projectKey,
                       @Param("status") Integer status,
                       @Param("toolName") String toolName,
                       @Param("errorCode") String errorCode);

    /**
     * 经验库统计（成长看板）：总数 / 各状态数 / 总命中 / 成功失败 / 来源分布
     * 返回单行聚合结果（无数据时返回一行全 0）
     */
    @Select("<script>" +
            "SELECT " +
            "COUNT(*) AS total, " +
            "COALESCE(SUM(CASE WHEN status = 0 THEN 1 ELSE 0 END), 0) AS draft_count, " +
            "COALESCE(SUM(CASE WHEN status = 1 THEN 1 ELSE 0 END), 0) AS active_count, " +
            "COALESCE(SUM(CASE WHEN status = 2 THEN 1 ELSE 0 END), 0) AS hidden_count, " +
            "COALESCE(SUM(hit_count), 0) AS total_hits, " +
            "COALESCE(SUM(success_count), 0) AS total_success, " +
            "COALESCE(SUM(fail_count), 0) AS total_fail, " +
            "COALESCE(SUM(CASE WHEN source = 'auto' THEN 1 ELSE 0 END), 0) AS auto_count, " +
            "COALESCE(SUM(CASE WHEN source = 'llm' THEN 1 ELSE 0 END), 0) AS llm_count, " +
            "COALESCE(SUM(CASE WHEN source = 'manual' THEN 1 ELSE 0 END), 0) AS manual_count, " +
            "COALESCE(SUM(CASE WHEN created_at &gt;= #{since} THEN 1 ELSE 0 END), 0) AS recent_count " +
            "FROM lesson WHERE 1=1" +
            "<if test='projectKey != null and projectKey != \"\"'> AND project_key IN (#{projectKey}, '" + Lesson.GLOBAL_PROJECT_KEY + "')</if>" +
            "</script>")
    Map<String, Object> selectStats(@Param("projectKey") String projectKey,
                                    @Param("since") java.time.LocalDateTime since);

    /** 物理删除（管理操作：确认误录/废弃经验） */
    @Delete("DELETE FROM lesson WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
