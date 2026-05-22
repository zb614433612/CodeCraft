package com.example.agentdeepseek.mapper;

import com.example.agentdeepseek.model.entity.Skill;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 技能数据访问接口
 */
@Mapper
@Repository
public interface SkillMapper {

    @Insert("INSERT INTO skill (name, description, tool_names, instructions, trigger_words, confidence, " +
            "usage_count, success_count, fail_count, user_id, agent_type, created_at, updated_at) " +
            "VALUES (#{name}, #{description}, #{toolNames}, #{instructions}, #{triggerWords}, #{confidence}, " +
            "#{usageCount}, #{successCount}, #{failCount}, #{userId}, #{agentType}, #{createdAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Skill skill);

    @Select("SELECT id, name, description, tool_names, instructions, trigger_words, confidence, " +
            "usage_count, success_count, fail_count, user_id, agent_type, created_at, updated_at " +
            "FROM skill WHERE id = #{id}")
    @Results(id = "skillResultMap", value = {
        @Result(property = "id", column = "id"),
        @Result(property = "name", column = "name"),
        @Result(property = "description", column = "description"),
        @Result(property = "toolNames", column = "tool_names"),
        @Result(property = "instructions", column = "instructions"),
        @Result(property = "triggerWords", column = "trigger_words"),
        @Result(property = "confidence", column = "confidence"),
        @Result(property = "usageCount", column = "usage_count"),
        @Result(property = "successCount", column = "success_count"),
        @Result(property = "failCount", column = "fail_count"),
        @Result(property = "userId", column = "user_id"),
        @Result(property = "agentType", column = "agent_type"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    Optional<Skill> selectById(Long id);

    @Select("SELECT id, name, description, tool_names, instructions, trigger_words, confidence, " +
            "usage_count, success_count, fail_count, user_id, agent_type, created_at, updated_at " +
            "FROM skill WHERE user_id = #{userId} AND agent_type = #{agentType} " +
            "ORDER BY confidence DESC, usage_count DESC")
    @ResultMap("skillResultMap")
    List<Skill> selectByUserAndAgent(@Param("userId") Long userId, @Param("agentType") String agentType);

    @Select("SELECT id, name, description, tool_names, instructions, trigger_words, confidence, " +
            "usage_count, success_count, fail_count, user_id, agent_type, created_at, updated_at " +
            "FROM skill WHERE user_id = #{userId} AND agent_type = #{agentType} " +
            "AND confidence >= 0.1 ORDER BY confidence DESC, usage_count DESC")
    @ResultMap("skillResultMap")
    List<Skill> selectActiveByUserAndAgent(@Param("userId") Long userId, @Param("agentType") String agentType);

    @Select("SELECT id, name, description, tool_names, instructions, trigger_words, confidence, " +
            "usage_count, success_count, fail_count, user_id, agent_type, created_at, updated_at " +
            "FROM skill WHERE user_id = #{userId} ORDER BY created_at DESC")
    @ResultMap("skillResultMap")
    List<Skill> selectByUserId(@Param("userId") Long userId);

    @Update("UPDATE skill SET name = #{name}, description = #{description}, " +
            "tool_names = #{toolNames}, instructions = #{instructions}, " +
            "trigger_words = #{triggerWords}, " +
            "confidence = #{confidence}, usage_count = #{usageCount}, " +
            "success_count = #{successCount}, fail_count = #{failCount} " +
            "WHERE id = #{id}")
    int update(Skill skill);

    @Delete("DELETE FROM skill WHERE id = #{id}")
    int delete(Long id);

    /**
     * 原子化报告技能执行结果（无竞态）
     * 使用 SQL 层原子更新避免并发读写导致计数丢失
     * confidence 采用贝叶斯先验 Beta(1,1) Laplace 平滑：
     *   (成功数 + 1) / (总次数 + 2)，初始 0 使用时置信度为 0.5
     */
    @Update("UPDATE skill SET " +
            "usage_count = usage_count + 1, " +
            "success_count = success_count + #{successInc}, " +
            "fail_count = fail_count + #{failInc}, " +
            "confidence = ROUND((success_count + #{successInc} + 1.0) / (usage_count + 3.0), 4) " +
            "WHERE id = #{id}")
    int atomicReportResult(@Param("id") Long id,
                           @Param("successInc") int successInc,
                           @Param("failInc") int failInc);
}
