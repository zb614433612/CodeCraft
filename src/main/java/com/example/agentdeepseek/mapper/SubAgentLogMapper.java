package com.example.agentdeepseek.mapper;

import com.example.agentdeepseek.model.entity.SubAgentLog;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 子Agent执行记录数据访问接口
 */
@Mapper
@Repository
public interface SubAgentLogMapper {

    @Insert("INSERT INTO sub_agent_log (agent_id, parent_conversation_id, parent_turn_id, name, status, " +
            "instructions, context_mode, tools, skills, summary, full_messages, file_changes, " +
            "compile_result, iterations_used, max_iterations, error_message, created_at, completed_at, updated_at) " +
            "VALUES (#{agentId}, #{parentConversationId}, #{parentTurnId}, #{name}, #{status}, " +
            "#{instructions}, #{contextMode}, #{tools}, #{skills}, #{summary}, #{fullMessages}, #{fileChanges}, " +
            "#{compileResult}, #{iterationsUsed}, #{maxIterations}, #{errorMessage}, #{createdAt}, #{completedAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SubAgentLog subAgentLog);

    @Select("SELECT id, agent_id, parent_conversation_id, parent_turn_id, name, status, " +
            "instructions, context_mode, tools, skills, summary, full_messages, file_changes, " +
            "compile_result, iterations_used, max_iterations, error_message, created_at, completed_at, updated_at " +
            "FROM sub_agent_log WHERE id = #{id}")
    @Results(id = "subAgentLogResultMap", value = {
        @Result(property = "id", column = "id"),
        @Result(property = "agentId", column = "agent_id"),
        @Result(property = "parentConversationId", column = "parent_conversation_id"),
        @Result(property = "parentTurnId", column = "parent_turn_id"),
        @Result(property = "name", column = "name"),
        @Result(property = "status", column = "status"),
        @Result(property = "instructions", column = "instructions"),
        @Result(property = "contextMode", column = "context_mode"),
        @Result(property = "tools", column = "tools"),
        @Result(property = "skills", column = "skills"),
        @Result(property = "summary", column = "summary"),
        @Result(property = "fullMessages", column = "full_messages"),
        @Result(property = "fileChanges", column = "file_changes"),
        @Result(property = "compileResult", column = "compile_result"),
        @Result(property = "iterationsUsed", column = "iterations_used"),
        @Result(property = "maxIterations", column = "max_iterations"),
        @Result(property = "errorMessage", column = "error_message"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "completedAt", column = "completed_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    Optional<SubAgentLog> selectById(Long id);

    @Select("SELECT id, agent_id, parent_conversation_id, parent_turn_id, name, status, " +
            "instructions, context_mode, tools, skills, summary, full_messages, file_changes, " +
            "compile_result, iterations_used, max_iterations, error_message, created_at, completed_at, updated_at " +
            "FROM sub_agent_log WHERE agent_id = #{agentId} ORDER BY created_at DESC LIMIT 1")
    @ResultMap("subAgentLogResultMap")
    Optional<SubAgentLog> selectByAgentId(@Param("agentId") String agentId);

    @Select("SELECT id, agent_id, parent_conversation_id, parent_turn_id, name, status, " +
            "instructions, context_mode, tools, skills, summary, full_messages, file_changes, " +
            "compile_result, iterations_used, max_iterations, error_message, created_at, completed_at, updated_at " +
            "FROM sub_agent_log WHERE parent_conversation_id = #{conversationId} ORDER BY created_at ASC")
    @ResultMap("subAgentLogResultMap")
    List<SubAgentLog> selectByConversationId(@Param("conversationId") Long conversationId);

    /**
     * 列表查询（轻量版）：<b>不查 full_messages</b>。
     * <p>
     * 子Agent面板只需要状态 / 摘要 / 文件变更等元信息，而 full_messages 是整段消息历史 JSON
     * （单条可达数十 KB）。原 {@link #selectByConversationId} 会把每条记录的全部历史都拉出来，
     * 一次列表请求在长会话下就会变成几百 KB 的无用传输。
     * </p>
     */
    @Select("SELECT id, agent_id, parent_conversation_id, parent_turn_id, name, status, " +
            "instructions, context_mode, tools, skills, summary, file_changes, " +
            "compile_result, iterations_used, max_iterations, error_message, created_at, completed_at, updated_at " +
            "FROM sub_agent_log WHERE parent_conversation_id = #{conversationId} ORDER BY created_at ASC")
    @ResultMap("subAgentLogResultMap")
    List<SubAgentLog> selectMetaByConversationId(@Param("conversationId") Long conversationId);

    @Update("UPDATE sub_agent_log SET status = #{status}, summary = #{summary}, " +
            "full_messages = #{fullMessages}, file_changes = #{fileChanges}, " +
            "compile_result = #{compileResult}, iterations_used = #{iterationsUsed}, " +
            "error_message = #{errorMessage}, completed_at = #{completedAt}, updated_at = #{updatedAt} " +
            "WHERE id = #{id}")
    int update(SubAgentLog subAgentLog);

    @Delete("DELETE FROM sub_agent_log WHERE parent_conversation_id = #{conversationId}")
    int deleteByConversationId(@Param("conversationId") Long conversationId);

    @Delete("DELETE FROM sub_agent_log WHERE parent_turn_id = #{turnId}")
    int deleteByParentTurnId(@Param("turnId") String turnId);

    @Delete("DELETE FROM sub_agent_log WHERE id = #{id}")
    int deleteById(Long id);
}
