package com.example.agentdeepseek.mapper;

import com.example.agentdeepseek.model.entity.LessonRule;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 归一化规则数据访问（P0 归一化管线 · 规则自学习）
 * 读写 lesson_rule 表：全量查询（提取时优先命中）/ 按 pattern 去重 / 命中计数与转正
 */
@Mapper
@Repository
public interface LessonRuleMapper {

    String COLUMNS = "id, text_pattern, error_category, error_code, hit_count, match_hit_count, source, status, created_at, updated_at";

    /** 全量规则（数据量小，Service 内存 contains 匹配；候选+转正都参与） */
    @Select("SELECT " + COLUMNS + " FROM lesson_rule")
    @Results(id = "lessonRuleResultMap", value = {
            @Result(property = "id", column = "id"),
            @Result(property = "textPattern", column = "text_pattern"),
            @Result(property = "errorCategory", column = "error_category"),
            @Result(property = "errorCode", column = "error_code"),
            @Result(property = "hitCount", column = "hit_count"),
            @Result(property = "matchHitCount", column = "match_hit_count"),
            @Result(property = "source", column = "source"),
            @Result(property = "status", column = "status"),
            @Result(property = "createdAt", column = "created_at"),
            @Result(property = "updatedAt", column = "updated_at")
    })
    List<LessonRule> selectAll();

    /** 按文本片段精确查重（插入前判断：同 pattern 已存在则计数转正，不重复插入） */
    @Select("SELECT " + COLUMNS + " FROM lesson_rule WHERE text_pattern = #{textPattern} LIMIT 1")
    @ResultMap("lessonRuleResultMap")
    Optional<LessonRule> selectByPattern(@Param("textPattern") String textPattern);

    /** 插入候选规则（match_hit_count 初始 0） */
    @Insert("INSERT INTO lesson_rule (text_pattern, error_category, error_code, hit_count, match_hit_count, source, status, created_at, updated_at) " +
            "VALUES (#{textPattern}, #{errorCategory}, #{errorCode}, 1, 0, #{source}, 0, #{createdAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(LessonRule rule);

    /**
     * 同 pattern 再次被沉淀：hit_count + 1；≥2 次自动转正 status=1（P0 简化版转正逻辑）。
     * L7 修复：同步用本次 LLM 的非空 category/code 覆盖更新（模型升级/prompt 调整后
     * 同 pattern 产出更准确结果时，规则不保留陈旧信息）。
     */
    @Update("UPDATE lesson_rule SET hit_count = hit_count + 1, " +
            "status = CASE WHEN hit_count + 1 >= 2 THEN 1 ELSE status END, " +
            "error_category = CASE WHEN #{errorCategory} IS NOT NULL AND #{errorCategory} != '' " +
            "THEN #{errorCategory} ELSE error_category END, " +
            "error_code = CASE WHEN #{errorCode} IS NOT NULL AND #{errorCode} != '' " +
            "THEN #{errorCode} ELSE error_code END, " +
            "updated_at = #{updatedAt} WHERE id = #{id}")
    int incrementHit(@Param("id") Long id,
                     @Param("errorCategory") String errorCategory,
                     @Param("errorCode") String errorCode,
                     @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * P2 使用度追踪：归一化提取时实际命中该规则 → match_hit_count + 1。
     * 使用度是淘汰依据（替代时间淘汰）：被命中过的规则有复用价值，永不因时间淘汰。
     */
    @Update("UPDATE lesson_rule SET match_hit_count = match_hit_count + 1, updated_at = #{updatedAt} WHERE id = #{id}")
    int incrementMatchHit(@Param("id") Long id, @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * P2 容量淘汰（替代时间淘汰）：仅当规则表超容量上限时触发——
     * 淘汰「候选（status=0）+ 从未被提取命中（match_hit_count=0）+ 最旧」的规则。
     * 理由：沉淀过但从没被实际匹配到，说明该文本特征无复用价值，且未经验证，淘汰损失最小。
     * 转正规则（status=1）、被使用过的候选（match_hit_count>0）、人工规则（manual）永不因容量淘汰。
     * 用户长期不碰项目不影响规则存留（无时间维度）。
     *
     * @param n 需要淘汰的条数
     * @return 实际淘汰行数
     */
    @Delete("DELETE FROM lesson_rule WHERE id IN " +
            "(SELECT id FROM (SELECT id FROM lesson_rule WHERE status = 0 AND match_hit_count = 0 " +
            "AND source != 'manual' ORDER BY updated_at ASC LIMIT #{n}) t)")
    int deleteUnusedCandidates(@Param("n") int n);

    /** 规则总数（看板：候选 + 转正） */
    @Select("SELECT COUNT(*) FROM lesson_rule")
    long count();

    /** 转正规则数（看板） */
    @Select("SELECT COUNT(*) FROM lesson_rule WHERE status = 1")
    long countActive();

    /** 候选规则数（status=0 且非人工——与容量淘汰语义一致；L1 修复：不再把 manual 算进候选） */
    @Select("SELECT COUNT(*) FROM lesson_rule WHERE status = 0 AND source != 'manual'")
    long countCandidates();

    /** 提取命中总次数（看板：规则使用度） */
    @Select("SELECT COALESCE(SUM(match_hit_count), 0) FROM lesson_rule")
    long sumMatchHits();

    /** 物理删除（管理操作/验证清理） */
    @Delete("DELETE FROM lesson_rule WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
