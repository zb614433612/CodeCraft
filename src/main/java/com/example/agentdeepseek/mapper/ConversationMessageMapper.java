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
    @Insert("INSERT INTO conversation_message (conversation_id, role, content, reasoning, tool_calls, turn_id, message_type, created_at) VALUES (#{conversationId}, #{role, typeHandler=org.apache.ibatis.type.EnumTypeHandler}, #{content}, #{reasoning}, #{toolCalls}, #{turnId}, #{messageType}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ConversationMessage message);

    /**
     * 根据会话ID查询消息列表（按创建时间正序）
     * @param conversationId 会话ID
     * @return 消息列表
     */
    @Select("SELECT id, conversation_id, role, content, reasoning, tool_calls, turn_id, message_type, created_at FROM conversation_message WHERE conversation_id = #{conversationId} ORDER BY created_at ASC")
    @Results(id = "ConversationMessageResultMap", value = {
        @Result(property = "id", column = "id"),
        @Result(property = "conversationId", column = "conversation_id"),
        @Result(property = "role", column = "role", typeHandler = org.apache.ibatis.type.EnumTypeHandler.class),
        @Result(property = "content", column = "content"),
        @Result(property = "reasoning", column = "reasoning"),
        @Result(property = "toolCalls", column = "tool_calls"),
        @Result(property = "turnId", column = "turn_id"),
        @Result(property = "messageType", column = "message_type"),
        @Result(property = "createdAt", column = "created_at")
    })
    List<ConversationMessage> selectByConversationId(Long conversationId);

    /**
     * 查询会话中「倒数第 offset+1 个正常用户消息」的 id —— 作为按轮分页的页起点。
     *
     * <p>分页口径（2026-10-17 聊天记录懒加载改造）：</p>
     * <ul>
     *   <li>只有 role=USER 且 message_type 不是 desktop_inject 的消息才算「一轮」的起点；</li>
     *   <li>desktop_inject 是桌面截图自动注入的辅助消息（前端不渲染成用户气泡），不能单独占一轮；</li>
     *   <li>beforeId 为上一页返回的 nextBeforeId（首页传 null），用 COALESCE 规避 H2 中 id &lt; NULL 恒为 false 的问题；</li>
     *   <li>OFFSET 用 ${offset} 字面量拼接（而非 #{offset} 预编译参数）：H2 对参数化 OFFSET 的兼容性不稳定，
     *       而 offset 由 Service 层限定为 0~49 的整数，不存在注入风险。</li>
     * </ul>
     *
     * @param conversationId 会话ID
     * @param beforeId       上一页游标（可为 null，表示从最新开始）
     * @param offset         倒数偏移量（rounds-1：取最近 N 轮的最早一个用户消息）
     * @return 页起点消息ID；用户消息不足时返回 null（调用方应退化为查 beforeId 之前的全部消息）
     */
    @Select("SELECT id FROM conversation_message "
            + "WHERE conversation_id = #{conversationId} AND role = 'USER' "
            + "AND (message_type IS NULL OR message_type <> 'desktop_inject') "
            + "AND id < COALESCE(#{beforeId}, 9223372036854775807) "
            + "ORDER BY id DESC LIMIT 1 OFFSET ${offset}")
    Long selectNthUserMessageIdFromTail(@Param("conversationId") Long conversationId,
                                        @Param("beforeId") Long beforeId,
                                        @Param("offset") int offset);

    /**
     * 按 id 范围查询一页消息（id 升序；startId/beforeId 均可为 null 表示不限该端）
     *
     * @param conversationId 会话ID
     * @param startId        页起点（含）
     * @param beforeId       页终点（不含，上一页游标）
     * @return 消息列表（按 id 升序，供前端 processMessageGroups 按时间正序合并）
     */
    @Select("<script>"
            + "SELECT id, conversation_id, role, content, reasoning, tool_calls, turn_id, message_type, created_at "
            + "FROM conversation_message WHERE conversation_id = #{conversationId} "
            + "<if test='startId != null'>AND id &gt;= #{startId} </if>"
            + "<if test='beforeId != null'>AND id &lt; #{beforeId} </if>"
            + "ORDER BY id ASC"
            + "</script>")
    @ResultMap("ConversationMessageResultMap")
    List<ConversationMessage> selectRange(@Param("conversationId") Long conversationId,
                                          @Param("startId") Long startId,
                                          @Param("beforeId") Long beforeId);

    /**
     * 统计 startId 之前是否还有消息（用于计算 hasMore，决定前端是否还能继续上滑加载）
     */
    @Select("SELECT COUNT(1) FROM conversation_message WHERE conversation_id = #{conversationId} AND id < #{startId}")
    long countBefore(@Param("conversationId") Long conversationId, @Param("startId") Long startId);

    /**
     * 根据消息ID查询消息
     * @param id 消息ID
     * @return 消息实体
     */
    @Select("SELECT id, conversation_id, role, content, reasoning, tool_calls, turn_id, message_type, created_at FROM conversation_message WHERE id = #{id}")
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

    /**
     * 更新消息思考过程（用于代码审查结果持久化）
     * @param id 消息ID
     * @param reasoning 新的思考内容
     * @return 受影响的行数
     */
    @Update("UPDATE conversation_message SET reasoning = #{reasoning} WHERE id = #{id}")
    int updateReasoning(@Param("id") Long id, @Param("reasoning") String reasoning);
}