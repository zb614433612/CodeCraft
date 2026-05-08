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

    @Insert("INSERT INTO skill (name, description, tool_names, instructions, confidence, " +
            "usage_count, success_count, fail_count, user_id, agent_type, created_at, updated_at) " +
            "VALUES (#{name}, #{description}, #{toolNames}, #{instructions}, #{confidence}, " +
            "#{usageCount}, #{successCount}, #{failCount}, #{userId}, #{agentType}, #{createdAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Skill skill);

    @Select("SELECT id, name, description, tool_names, instructions, confidence, " +
            "usage_count, success_count, fail_count, user_id, agent_type, created_at, updated_at " +
            "FROM skill WHERE id = #{id}")
    @Results(id = "skillResultMap", value = {
        @Result(property = "id", column = "id"),
        @Result(property = "name", column = "name"),
        @Result(property = "description", column = "description"),
        @Result(property = "toolNames", column = "tool_names"),
        @Result(property = "instructions", column = "instructions"),
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

    @Select("SELECT id, name, description, tool_names, instructions, confidence, " +
            "usage_count, success_count, fail_count, user_id, agent_type, created_at, updated_at " +
            "FROM skill WHERE user_id = #{userId} AND agent_type = #{agentType} " +
            "ORDER BY confidence DESC, usage_count DESC")
    @ResultMap("skillResultMap")
    List<Skill> selectByUserAndAgent(@Param("userId") Long userId, @Param("agentType") String agentType);

    @Select("SELECT id, name, description, tool_names, instructions, confidence, " +
            "usage_count, success_count, fail_count, user_id, agent_type, created_at, updated_at " +
            "FROM skill WHERE user_id = #{userId} AND agent_type = #{agentType} " +
            "AND confidence >= 0.1 ORDER BY confidence DESC, usage_count DESC")
    @ResultMap("skillResultMap")
    List<Skill> selectActiveByUserAndAgent(@Param("userId") Long userId, @Param("agentType") String agentType);

    @Select("SELECT id, name, description, tool_names, instructions, confidence, " +
            "usage_count, success_count, fail_count, user_id, agent_type, created_at, updated_at " +
            "FROM skill WHERE user_id = #{userId} ORDER BY created_at DESC")
    @ResultMap("skillResultMap")
    List<Skill> selectByUserId(@Param("userId") Long userId);

    @Update("UPDATE skill SET name = #{name}, description = #{description}, " +
            "tool_names = #{toolNames}, instructions = #{instructions}, " +
            "confidence = #{confidence}, usage_count = #{usageCount}, " +
            "success_count = #{successCount}, fail_count = #{failCount} " +
            "WHERE id = #{id}")
    int update(Skill skill);

    @Delete("DELETE FROM skill WHERE id = #{id}")
    int delete(Long id);
}
