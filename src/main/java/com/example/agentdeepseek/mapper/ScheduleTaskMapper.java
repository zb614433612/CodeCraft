package com.example.agentdeepseek.mapper;

import com.example.agentdeepseek.model.entity.ScheduleTask;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
@Repository
public interface ScheduleTaskMapper {

    @Results(id = "taskResultMap", value = {
        @Result(property = "id", column = "id"),
        @Result(property = "name", column = "name"),
        @Result(property = "agentType", column = "agent_type"),
        @Result(property = "agentConfigId", column = "agent_config_id"),
        @Result(property = "instruction", column = "instruction"),
        @Result(property = "cronExpression", column = "cron_expression"),
        @Result(property = "executeTime", column = "execute_time"),
        @Result(property = "status", column = "status"),
        @Result(property = "lastExecuteTime", column = "last_execute_time"),
        @Result(property = "lastConversationId", column = "last_conversation_id"),
        @Result(property = "executeCount", column = "execute_count"),
        @Result(property = "maxExecuteCount", column = "max_execute_count"),
        @Result(property = "userId", column = "user_id"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    @Select("SELECT * FROM schedule_task ORDER BY created_at DESC")
    List<ScheduleTask> selectAll();

    @Select("SELECT * FROM schedule_task WHERE id = #{id}")
    @ResultMap("taskResultMap")
    ScheduleTask selectById(Long id);

    @Insert("INSERT INTO schedule_task (name, agent_type, agent_config_id, instruction, cron_expression, execute_time, status, max_execute_count, user_id) " +
            "VALUES (#{name}, #{agentType}, #{agentConfigId}, #{instruction}, #{cronExpression}, #{executeTime}, " +
            "COALESCE(#{status}, 'ENABLED'), COALESCE(#{maxExecuteCount}, 0), #{userId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ScheduleTask task);

    @Update("UPDATE schedule_task SET name=#{name}, agent_type=#{agentType}, agent_config_id=#{agentConfigId}, instruction=#{instruction}, " +
            "cron_expression=#{cronExpression}, execute_time=#{executeTime}, status=#{status}, " +
            "max_execute_count=#{maxExecuteCount} WHERE id=#{id}")
    int update(ScheduleTask task);

    @Update("UPDATE schedule_task SET status=#{status} WHERE id=#{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    @Update("UPDATE schedule_task SET execute_count=execute_count+1, last_execute_time=NOW(), " +
            "last_conversation_id=#{conversationId} WHERE id=#{id}")
    int updateExecuteInfo(@Param("id") Long id, @Param("conversationId") Long conversationId);

    @Update("UPDATE schedule_task SET execute_time=#{executeTime} WHERE id=#{id}")
    int updateExecuteTime(@Param("id") Long id, @Param("executeTime") LocalDateTime executeTime);

    @Delete("DELETE FROM schedule_task WHERE id = #{id}")
    int delete(Long id);

    @Select("SELECT * FROM schedule_task WHERE status = 'ENABLED' " +
            "AND (execute_time IS NULL OR execute_time <= NOW()) " +
            "AND (max_execute_count = 0 OR execute_count < max_execute_count) " +
            "ORDER BY execute_time ASC")
    @ResultMap("taskResultMap")
    List<ScheduleTask> selectDueTasks();
}
