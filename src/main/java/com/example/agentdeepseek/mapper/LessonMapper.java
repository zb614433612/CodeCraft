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
            "status, source, hit_count, success_count, fail_count, type, goal, env_params, created_at, updated_at";

    @Insert("INSERT INTO lesson (project_key, tool_name, error_category, error_code, error_signature, " +
            "symptom, root_cause, solution, params_json, applicable_cond, keywords, " +
            "status, source, hit_count, success_count, fail_count, type, goal, env_params, created_at, updated_at) " +
            "VALUES (#{projectKey}, #{toolName}, #{errorCategory}, #{errorCode}, #{errorSignature}, " +
            "#{symptom}, #{rootCause}, #{solution}, #{paramsJson}, #{applicableCond}, #{keywords}, " +
            "#{status}, #{source}, #{hitCount}, #{successCount}, #{failCount}, #{type}, #{goal}, #{envParams}, #{createdAt}, #{updatedAt})")
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
        @Result(property = "type", column = "type"),
        @Result(property = "goal", column = "goal"),
        @Result(property = "envParams", column = "env_params"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    Optional<Lesson> selectById(Long id);

    /** 指纹查重：同坑不重复入库 */
    @Select("SELECT " + COLUMNS + " FROM lesson WHERE error_signature = #{signature} LIMIT 1")
    @ResultMap("lessonResultMap")
    Optional<Lesson> selectBySignature(@Param("signature") String signature);

    /**
     * 第一级硬过滤：project_key 必填 + tool_name/error_category/error_code/type 可选精确匹配
     * P0-1：error_code 匹配大小写不敏感（UPPER 化）——LLM 传小写/混大小写短码均可命中（数据量小无索引压力）。
     * F6 全局共享池：project_key IN (当前项目, '__global__')，项目内经验优先，全局经验兜底
     * 排序：项目内优先 → 有效优先 → 命中次数 → 成功率（贝叶斯平滑，防除零）
     */
    @Select("<script>" +
            "SELECT " + COLUMNS + " FROM lesson " +
            "WHERE project_key IN (#{projectKey}, '" + Lesson.GLOBAL_PROJECT_KEY + "') AND status IN (0, 1)" +
            "<if test='toolName != null and toolName != \"\"'> AND tool_name = #{toolName}</if>" +
            "<if test='errorCategory != null and errorCategory != \"\"'> AND error_category = #{errorCategory}</if>" +
            "<if test='errorCode != null and errorCode != \"\"'> AND UPPER(error_code) = UPPER(#{errorCode})</if>" +
            "<if test='type != null and type != \"\"'> AND type = #{type}</if>" +
            " ORDER BY CASE WHEN project_key = #{projectKey} THEN 0 ELSE 1 END, " +
            "status DESC, (success_count + 1.0) / (success_count + fail_count + 2.0) DESC, " +
            "hit_count DESC " +
            "LIMIT #{limit}" +
            "</script>")
    @ResultMap("lessonResultMap")
    List<Lesson> selectByScope(@Param("projectKey") String projectKey,
                                @Param("toolName") String toolName,
                                @Param("errorCategory") String errorCategory,
                                @Param("errorCode") String errorCode,
                                @Param("type") String type,
                                @Param("limit") int limit);

    /**
     * 第三级兜底模糊检索（P0 重构：多词 OR 匹配 + 大小写不敏感）。
     * project_key 必填 + 关键词列表任一命中（每词跨 symptom/root_cause/solution/keywords/goal 五字段 OR）；
     * F6 全局共享池：project_key IN (当前项目, '__global__')，项目内优先。
     * 覆盖 H2 无 FULLTEXT 的缺口，量级小无压力；字段匹配统一 LOWER 化消除大小写差异失配；
     * 词内 % _ \ 由 Service 层 escapeLikeKeyword 转义 + ESCAPE '\' 防通配符注入。
     * 排序：项目内优先 → 有效优先 → 命中次数 → 成功率（贝叶斯平滑）；「词命中数」精排由 Service 层完成。
     */
    @Select("<script>" +
            "SELECT " + COLUMNS + " FROM lesson " +
            "WHERE project_key IN (#{projectKey}, '" + Lesson.GLOBAL_PROJECT_KEY + "') AND status IN (0, 1)" +
            "<if test='toolName != null and toolName != \"\"'> AND tool_name = #{toolName}</if>" +
            "<if test='type != null and type != \"\"'> AND type = #{type}</if>" +
            "<if test='keywords != null and keywords.size() > 0'>" +
            " AND (" +
            "<foreach collection='keywords' item='w' separator=' OR '>" +
            "(LOWER(symptom) LIKE CONCAT('%', LOWER(#{w}), '%') ESCAPE '\\' " +
            " OR LOWER(root_cause) LIKE CONCAT('%', LOWER(#{w}), '%') ESCAPE '\\' " +
            " OR LOWER(solution) LIKE CONCAT('%', LOWER(#{w}), '%') ESCAPE '\\' " +
            " OR LOWER(keywords) LIKE CONCAT('%', LOWER(#{w}), '%') ESCAPE '\\' " +
            " OR LOWER(goal) LIKE CONCAT('%', LOWER(#{w}), '%') ESCAPE '\\')" +
            "</foreach>" +
            ")" +
            "</if>" +
            " ORDER BY CASE WHEN project_key = #{projectKey} THEN 0 ELSE 1 END, " +
            "status DESC, (success_count + 1.0) / (success_count + fail_count + 2.0) DESC, " +
            "hit_count DESC " +
            "LIMIT #{limit}" +
            "</script>")
    @ResultMap("lessonResultMap")
    List<Lesson> selectFuzzy(@Param("projectKey") String projectKey,
                              @Param("toolName") String toolName,
                              @Param("keywords") List<String> keywords,
                              @Param("type") String type,
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
     * L2：goal 可选更新（DETOUR 检索维度补全，只补空不覆盖）
     */
    @Update("<script>" +
            "UPDATE lesson SET updated_at = #{updatedAt}" +
            "<if test='rootCause != null'> , root_cause = #{rootCause}</if>" +
            "<if test='solution != null'> , solution = #{solution}</if>" +
            "<if test='paramsJson != null'> , params_json = #{paramsJson}</if>" +
            "<if test='applicableCond != null'> , applicable_cond = #{applicableCond}</if>" +
            "<if test='keywords != null'> , keywords = #{keywords}</if>" +
            "<if test='goal != null'> , goal = #{goal}</if>" +
            " WHERE id = #{id}" +
            "</script>")
    int updateContent(@Param("id") Long id,
                      @Param("rootCause") String rootCause,
                      @Param("solution") String solution,
                      @Param("paramsJson") String paramsJson,
                      @Param("applicableCond") String applicableCond,
                      @Param("keywords") String keywords,
                      @Param("goal") String goal,
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
            "<if test='type != null and type != \"\"'> AND type = #{type}</if>" +
            "<if test='zeroHit != null and zeroHit'> AND hit_count = 0</if>" +
            " ORDER BY status DESC, hit_count DESC, updated_at DESC " +
            "LIMIT #{size} OFFSET #{offset}" +
            "</script>")
    @ResultMap("lessonResultMap")
    List<Lesson> selectPage(@Param("projectKey") String projectKey,
                            @Param("status") Integer status,
                            @Param("toolName") String toolName,
                            @Param("errorCode") String errorCode,
                            @Param("type") String type,
                            @Param("zeroHit") Boolean zeroHit,
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
            "<if test='type != null and type != \"\"'> AND type = #{type}</if>" +
            "<if test='zeroHit != null and zeroHit'> AND hit_count = 0</if>" +
            "</script>")
    long countByFilter(@Param("projectKey") String projectKey,
                       @Param("status") Integer status,
                       @Param("toolName") String toolName,
                       @Param("errorCode") String errorCode,
                       @Param("type") String type,
                       @Param("zeroHit") Boolean zeroHit);

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
            "COALESCE(SUM(CASE WHEN type = 'DETOUR' THEN 1 ELSE 0 END), 0) AS detour_count, " +
            "COALESCE(SUM(CASE WHEN created_at &gt;= #{since} THEN 1 ELSE 0 END), 0) AS recent_count " +
            "FROM lesson WHERE 1=1" +
            "<if test='projectKey != null and projectKey != \"\"'> AND project_key IN (#{projectKey}, '" + Lesson.GLOBAL_PROJECT_KEY + "')</if>" +
            "</script>")
    Map<String, Object> selectStats(@Param("projectKey") String projectKey,
                                    @Param("since") java.time.LocalDateTime since);

    // ============================================================
    // P2：管理操作——聚类查询 + 归并更新
    // ============================================================

    /**
     * P2：聚类查询——同 project+tool+category+code 的重复组（管理员归并入口）。
     * 排除 DETOUR（其去重为 goal 维度，不按工具/码聚类）；返回分组键 + 条数 + 代表 id。
     * 排序：条数降序（重复越多的组越靠前）。
     */
    @Select("<script>" +
            "SELECT project_key, tool_name, error_category, error_code, COUNT(*) AS cnt, MIN(id) AS sample_id " +
            "FROM lesson WHERE (type IS NULL OR type != 'DETOUR')" +
            "<if test='projectKey != null and projectKey != \"\"'> AND project_key = #{projectKey}</if>" +
            " GROUP BY project_key, tool_name, error_category, error_code" +
            " HAVING COUNT(*) > 1" +
            " ORDER BY cnt DESC, project_key, tool_name, error_code" +
            " LIMIT #{limit}" +
            "</script>")
    List<Map<String, Object>> selectClusterGroups(@Param("projectKey") String projectKey,
                                                  @Param("limit") int limit);

    /** P2：归并结果回写（计数汇聚 + canonical 码 + 新签名） */
    @Update("UPDATE lesson SET hit_count = #{hitCount}, success_count = #{successCount}, " +
            "fail_count = #{failCount}, error_code = #{errorCode}, error_signature = #{signature}, " +
            "updated_at = #{updatedAt} WHERE id = #{id}")
    int updateMerged(@Param("id") Long id,
                     @Param("hitCount") int hitCount,
                     @Param("successCount") int successCount,
                     @Param("failCount") int failCount,
                     @Param("errorCode") String errorCode,
                     @Param("signature") String signature,
                     @Param("updatedAt") java.time.LocalDateTime updatedAt);

    /** 物理删除（管理操作：确认误录/废弃经验） */
    @Delete("DELETE FROM lesson WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    /**
     * P0 归一化管线：LLM 语义归一化结果回写增强草稿（只补空不覆盖，与 F2 合并语义一致）。
     * 并发安全：WHERE error_code = 'UNKNOWN' 保证只更新「规则通道落空」的草稿——
     * 若 C2/LLM 已补全（error_code 已非 UNKNOWN），本更新影响 0 行，不会覆盖。
     * root_cause 仅补空；error_category 仅当旧值为 UNKNOWN/OTHER 时补（避免覆盖已有分类）。
     * H1 修复：error_code 变动时同步重算 error_signature（由 Service 传入新指纹）——
     * 保证内容与指纹一致，后续 C2 复盘等通道用新 error_code 算指纹时可命中合并，防同坑双条。
     *
     * @return 实际更新行数（0=无需回写或已被其他通道补全）
     */
    @Update("UPDATE lesson SET " +
            "error_category = CASE WHEN error_category IN ('OTHER','UNKNOWN') THEN #{errorCategory} ELSE error_category END, " +
            "error_code = CASE WHEN error_code = 'UNKNOWN' THEN #{errorCode} ELSE error_code END, " +
            "root_cause = CASE WHEN root_cause IS NULL OR root_cause = '' THEN #{rootCause} ELSE root_cause END, " +
            "error_signature = CASE WHEN error_code = 'UNKNOWN' THEN #{signature} ELSE error_signature END, " +
            "updated_at = #{updatedAt} " +
            "WHERE id = #{id} AND error_code = 'UNKNOWN'")
    int updateNormalized(@Param("id") Long id,
                         @Param("errorCategory") String errorCategory,
                         @Param("errorCode") String errorCode,
                         @Param("rootCause") String rootCause,
                         @Param("signature") String signature,
                         @Param("updatedAt") java.time.LocalDateTime updatedAt);
}
