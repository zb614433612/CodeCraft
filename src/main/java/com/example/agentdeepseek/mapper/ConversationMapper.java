package com.example.agentdeepseek.mapper;

import com.example.agentdeepseek.model.entity.Conversation;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 会话数据访问接口
 */
@Mapper
@Repository
public interface ConversationMapper {

    /**
     * 创建会话表（如果不存在）
     */
    @Select("SELECT 1")
    int createTable();

    /**
     * 插入新会话
     * @param conversation 会话实体
     * @return 受影响的行数
     */
    @Insert("INSERT INTO conversation (name, user_id, agent_type, created_at, updated_at) VALUES (#{name}, #{userId}, #{agentType}, #{createdAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Conversation conversation);

    /**
     * 根据ID查询会话
     * @param id 会话ID
     * @return 会话实体
     */
    @Select("SELECT id, name, user_id, agent_type, created_at, updated_at FROM conversation WHERE id = #{id}")
    @Results(id = "conversationResultMap", value = {
        @Result(property = "id", column = "id"),
        @Result(property = "name", column = "name"),
        @Result(property = "userId", column = "user_id"),
        @Result(property = "agentType", column = "agent_type"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    Optional<Conversation> selectById(Long id);

    /**
     * 查询所有会话（按创建时间倒序）
     * @return 会话列表
     */
    @Select("SELECT id, name, user_id, agent_type, created_at, updated_at FROM conversation ORDER BY created_at DESC")
    @ResultMap("conversationResultMap")
    List<Conversation> selectAll();

    /**
     * 根据用户ID查询会话列表（按创建时间倒序）
     * @param userId 用户ID
     * @return 会话列表
     */
    @Select("SELECT id, name, user_id, agent_type, created_at, updated_at FROM conversation WHERE user_id = #{userId} ORDER BY created_at DESC")
    @ResultMap("conversationResultMap")
    List<Conversation> selectByUserId(Long userId);

    /**
     * 根据用户ID和会话类型查询会话列表（按创建时间倒序）
     * @param userId 用户ID
     * @param agentType 会话类型
     * @return 会话列表
     */
    @Select("SELECT id, name, user_id, agent_type, created_at, updated_at FROM conversation WHERE user_id = #{userId} AND agent_type = #{agentType} ORDER BY created_at DESC")
    @ResultMap("conversationResultMap")
    List<Conversation> selectByUserIdAndAgentType(@Param("userId") Long userId, @Param("agentType") String agentType);

    /**
     * 更新会话信息（名称和更新时间）
     * @param conversation 会话实体
     * @return 受影响的行数
     */
    @Update("UPDATE conversation SET name = #{name}, updated_at = #{updatedAt} WHERE id = #{id}")
    int update(Conversation conversation);

    /**
     * 删除会话
     * @param id 会话ID
     * @return 受影响的行数
     */
    @Delete("DELETE FROM conversation WHERE id = #{id}")
    int delete(Long id);
}