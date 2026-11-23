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
     * 按压缩水位线增量查询「构建上下文所需」的消息（2026-11-23）。
     *
     * <p><b>为什么需要它</b>：{@link #selectByConversationId} 会全量加载整个会话
     * （实测某长会话 31426 条 / 4412 万字符），其中绝大部分都已被压缩记录覆盖、
     * 加载后在内存里又被 {@code applyCompactionRecords} 丢掉 —— 等于每次请求都白白
     * 读几十 MB。压缩只减少了发给模型的内容，并没有减少 DB 读取量。</p>
     *
     * <p><b>过滤规则</b>：保留全部 SYSTEM（系统提示词永不参与压缩）+ {@code id > afterId}
     * （水位线之后仍需参与上下文的消息）。索引可直接命中
     * {@code idx_conversation_id(conversation_id)} + 主键 id。</p>
     *
     * @param conversationId 会话 ID
     * @param afterId        压缩水位线（{@code conversation_compaction.end_message_id}）；
     *                       没有压缩记录时不要调用本方法，仍走全量查询
     * @return 消息列表（按 id 升序）
     */
    @Select("SELECT id, conversation_id, role, content, reasoning, tool_calls, turn_id, message_type, created_at "
            + "FROM conversation_message WHERE conversation_id = #{conversationId} "
            + "AND (role = 'SYSTEM' OR id > #{afterId}) ORDER BY id ASC")
    @ResultMap("ConversationMessageResultMap")
    List<ConversationMessage> selectForContext(@Param("conversationId") Long conversationId,
                                               @Param("afterId") long afterId);

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
     * 统计某条消息之后累计的文本字符量（content + reasoning），用于压缩冷却判断。
     *
     * <p><b>为什么按字符而不是按轮数</b>：一轮工具循环可能只有几条消息（几千字符），
     * 也可能上百条（上百万字符）。按轮数冷却会让「大轮次会话」在冷却期内暴涨到窗口上限，
     * 按字符量则与真实上下文增长线性对应。</p>
     *
     * <p>TOOL 消息的工具结果存在 {@code reasoning} 字段，必须一起统计，否则严重低估。</p>
     *
     * @param conversationId 会话 ID
     * @param afterId        水位线消息 ID（只统计 id &gt; afterId 的）
     * @return 累计字符数（无数据返回 0）
     */
    @Select("SELECT COALESCE(SUM(COALESCE(CHAR_LENGTH(content), 0) + COALESCE(CHAR_LENGTH(reasoning), 0)), 0) "
            + "FROM conversation_message WHERE conversation_id = #{conversationId} AND id > #{afterId}")
    long sumTextLengthAfterId(@Param("conversationId") Long conversationId,
                              @Param("afterId") Long afterId);

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
     * 查询某一轮（turnId）最早的一条消息 id —— 「从该轮起删除」的起点。
     *
     * <p>后端只给 user 消息写 turn_id，因此命中的就是那轮的提问；<br>
     * 同一 turnId 若有多条（历史脏数据），取 id 最小的一条，保证删除范围从提问开始。</p>
     *
     * @param conversationId 会话ID
     * @param turnId         轮次ID
     * @return 起始消息 id；找不到返回 null
     */
    @Select("SELECT id FROM conversation_message WHERE conversation_id = #{conversationId} "
            + "AND turn_id = #{turnId} ORDER BY id ASC LIMIT 1")
    Long selectFirstIdByTurnId(@Param("conversationId") Long conversationId,
                               @Param("turnId") String turnId);

    /**
     * 删除 id &gt;= fromId 的所有消息（含 fromId 自身）——「重新生成」丢弃该轮及其后内容。
     *
     * <p>限定 conversationId，避免调用方传错 id 时误删别的会话。</p>
     *
     * @param conversationId 会话ID
     * @param fromId         起始消息 id（含）
     * @return 受影响的行数
     */
    @Delete("DELETE FROM conversation_message WHERE conversation_id = #{conversationId} AND id >= #{fromId}")
    int deleteFromId(@Param("conversationId") Long conversationId, @Param("fromId") Long fromId);

    /**
     * 批量查询若干轮次（turnId）的用户提问原文 —— 给「改动回滚」面板标注「这是哪一轮」用。
     *
     * <p>为什么需要：快照（snapshot）只记录 turnId / 时间 / 文件数，而聊天页默认只加载最近 5 轮，
     * 更早的轮次不在内存里，前端无从得知提问内容。这里按 turnId 精确取，避免全量拉历史消息。</p>
     *
     * <p>返回 VO 而不是 Map：{@code content} 是 LONGTEXT（H2 → CLOB），Map 方式拿到的是
     * {@code JdbcClob}，还得在调用方判类型；声明 String 字段后由 StringTypeHandler 取值。</p>
     *
     * @param conversationId 会话ID
     * @param turnIds        轮次ID集合（调用方需保证非空）
     * @return 每个命中轮次的 turnId + 提问原文
     */
    @Select("<script>"
            + "SELECT turn_id AS turn_id, content AS content FROM conversation_message "
            + "WHERE conversation_id = #{conversationId} AND role = 'USER' AND turn_id IN "
            + "<foreach collection='turnIds' item='tid' open='(' separator=',' close=')'>#{tid}</foreach>"
            + " ORDER BY id ASC"
            + "</script>")
    @Results(id = "TurnPromptResultMap", value = {
        @Result(property = "turnId", column = "turn_id"),
        @Result(property = "content", column = "content")
    })
    List<com.example.agentdeepseek.model.vo.TurnPromptVO> selectUserPromptsByTurnIds(
            @Param("conversationId") Long conversationId,
            @Param("turnIds") List<String> turnIds);

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