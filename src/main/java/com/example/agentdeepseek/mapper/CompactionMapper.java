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
