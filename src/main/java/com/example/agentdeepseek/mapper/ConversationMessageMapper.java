package com.example.agentdeepseek.mapper;

import com.example.agentdeepseek.model.entity.ConversationMessage;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 会话消息数据访问接口
 */
@Mapper
@Repository
public interface ConversationMessageMapper {

    /**
     * 创建会话消息表（如果不存在）
     */
    @Select("SELECT 1")
    int createTable();

    /**
     * 插入新消息
     * @param message 消息实体
     * @return 受影响的行数
     */
    @Insert("INSERT INTO conversation_message (conversation_id, role, content, reasoning, tool_calls, created_at) VALUES (#{conversationId}, #{role, typeHandler=org.apache.ibatis.type.EnumTypeHandler}, #{content}, #{reasoning}, #{toolCalls}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ConversationMessage message);

    /**
     * 根据会话ID查询消息列表（按创建时间正序）
     * @param conversationId 会话ID
     * @return 消息列表
     */
    @Select("SELECT id, conversation_id, role, content, reasoning, tool_calls, created_at FROM conversation_message WHERE conversation_id = #{conversationId} ORDER BY created_at ASC")
    @Results(id = "ConversationMessageResultMap", value = {
        @Result(property = "id", column = "id"),
        @Result(property = "conversationId", column = "conversation_id"),
        @Result(property = "role", column = "role", typeHandler = org.apache.ibatis.type.EnumTypeHandler.class),
        @Result(property = "content", column = "content"),
        @Result(property = "reasoning", column = "reasoning"),
        @Result(property = "toolCalls", column = "tool_calls"),
        @Result(property = "createdAt", column = "created_at")
    })
    List<ConversationMessage> selectByConversationId(Long conversationId);

    /**
     * 根据消息ID查询消息
     * @param id 消息ID
     * @return 消息实体
     */
    @Select("SELECT id, conversation_id, role, content, reasoning, tool_calls, created_at FROM conversation_message WHERE id = #{id}")
    @ResultMap("ConversationMessageResultMap")
    ConversationMessage selectById(Long id);

    /**
     * 删除指定会话的所有消息
     * @param conversationId 会话ID
     * @return 受影响的行数
     */
    @Delete("DELETE FROM conversation_message WHERE conversation_id = #{conversationId}")
    int deleteByConversationId(Long conversationId);

    /**
     * 删除指定消息
     * @param id 消息ID
     * @return 受影响的行数
     */
    @Delete("DELETE FROM conversation_message WHERE id = #{id}")
    int delete(Long id);

    /**
     * 更新消息内容
     * @param id 消息ID
     * @param content 新内容
     * @return 受影响的行数
     */
    @Update("UPDATE conversation_message SET content = #{content} WHERE id = #{id}")
    int updateContent(@Param("id") Long id, @Param("content") String content);
}