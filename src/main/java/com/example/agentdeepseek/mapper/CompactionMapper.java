package com.example.agentdeepseek.mapper;

import com.example.agentdeepseek.model.entity.CompactionRecord;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 上下文压缩记录数据访问接口
 */
@Mapper
@Repository
public interface CompactionMapper {

    /**
     * 创建压缩记录表（如果不存在）
     */
    @Select("SELECT 1")
    int createTable();

    /**
     * 插入压缩记录
     */
    @Insert("INSERT INTO conversation_compaction (conversation_id, summary, start_message_id, end_message_id, token_savings, superseded, created_at) " +
            "VALUES (#{conversationId}, #{summary}, #{startMessageId}, #{endMessageId}, #{tokenSavings}, #{superseded}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(CompactionRecord record);

    /**
     * 根据 ID 查询压缩记录
     */
    @Select("SELECT id, conversation_id, summary, start_message_id, end_message_id, token_savings, superseded, created_at " +
            "FROM conversation_compaction WHERE id = #{id}")
    @Results(id = "compactionResultMap", value = {
            @Result(property = "id", column = "id"),
            @Result(property = "conversationId", column = "conversation_id"),
            @Result(property = "summary", column = "summary"),
            @Result(property = "startMessageId", column = "start_message_id"),
            @Result(property = "endMessageId", column = "end_message_id"),
            @Result(property = "tokenSavings", column = "token_savings"),
            @Result(property = "superseded", column = "superseded"),
            @Result(property = "createdAt", column = "created_at")
    })
    Optional<CompactionRecord> selectById(Long id);

    /**
     * 查询指定会话中所有未被废弃的压缩记录（按创建时间正序）
     */
    @Select("SELECT id, conversation_id, summary, start_message_id, end_message_id, token_savings, superseded, created_at " +
            "FROM conversation_compaction " +
            "WHERE conversation_id = #{conversationId} AND superseded = false " +
            "ORDER BY created_at ASC")
    @ResultMap("compactionResultMap")
    List<CompactionRecord> selectActiveByConversationId(Long conversationId);

    /**
     * 查询指定会话「最新一条未被废弃」的压缩记录。
     *
     * <p><b>为什么需要它（写法B）</b>：新的压缩模型下，摘要是一条<b>累进的完整记录</b>
     * （每次压缩都把旧摘要作为输入一起压进新摘要），因此每会话只需要一条有效记录，
     * 不需要多条区间记录叠加。只取最新一条可以彻底消除「区间匹配 / 索引错位 /
     * 旧摘要被废弃但内容没进新摘要」这一整类问题。</p>
     *
     * @param conversationId 会话 ID
     * @return 最新一条有效记录；没有则返回 {@code null}
     */
    @Select("SELECT id, conversation_id, summary, start_message_id, end_message_id, token_savings, superseded, created_at " +
            "FROM conversation_compaction " +
            "WHERE conversation_id = #{conversationId} AND superseded = false " +
            "ORDER BY id DESC LIMIT 1")
    @ResultMap("compactionResultMap")
    CompactionRecord selectLatestActive(Long conversationId);

    /**
     * 压缩成功后，把会话内除 {@code keepId} 之外的所有记录标记为废弃。
     *
     * <p>写法B 每次产出的都是「旧摘要 + 新消息」合并后的完整摘要，
     * 旧记录的正文已经作为输入参与了本次压缩，所以废弃它们是安全的
     * —— 不会出现旧摘要承载的信息凭空蒸发。</p>
     *
     * @param conversationId 会话 ID
     * @param keepId         本次新产出的记录 ID（保留）
     * @return 被废弃的行数
     */
    @Update("UPDATE conversation_compaction SET superseded = true " +
            "WHERE conversation_id = #{conversationId} AND id <> #{keepId} AND superseded = false")
    int markSupersededExcept(@Param("conversationId") Long conversationId, @Param("keepId") Long keepId);

    /**
     * 将指定压缩记录标记为已废弃（被后续压缩覆盖）
     */
    @Update("UPDATE conversation_compaction SET superseded = true WHERE id = #{id}")
    int markSuperseded(Long id);

    /**
     * 删除指定会话的所有压缩记录
     */
    @Delete("DELETE FROM conversation_compaction WHERE conversation_id = #{conversationId}")
    int deleteByConversationId(Long conversationId);
}
