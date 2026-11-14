package com.example.agentdeepseek.mapper;

import com.example.agentdeepseek.model.entity.LlmTokenUsage;
import com.example.agentdeepseek.model.vo.TokenUsageGroupVO;
import com.example.agentdeepseek.model.vo.TokenUsageSummaryVO;
import com.example.agentdeepseek.model.vo.TokenUsageTurnVO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * LLM 调用用量数据访问接口（成本可观测）
 * <p>
 * 设计要点：
 * <ul>
 *   <li>每轮 API 请求一条记录，不做「会话级预聚合」——否则看不到工具循环的轮次膨胀</li>
 *   <li>命中/未命中分开存：两者单价相差约 120 倍，混算会得出完全错误的成本结论</li>
 *   <li>所有聚合 SQL 使用下划线别名，依赖全局 map-underscore-to-camel-case 映射到 VO 驼峰字段</li>
 * </ul>
 * </p>
 */
@Mapper
@Repository
public interface LlmTokenUsageMapper {

    /**
     * 插入一条用量记录
     */
    @Insert("INSERT INTO llm_token_usage (conversation_id, turn_id, message_id, iteration, provider, model, "
            + "prompt_tokens, completion_tokens, total_tokens, cache_hit_tokens, cache_miss_tokens, "
            + "reasoning_tokens, cost_estimate, created_at) VALUES ("
            + "#{conversationId}, #{turnId}, #{messageId}, #{iteration}, #{provider}, #{model}, "
            + "#{promptTokens}, #{completionTokens}, #{totalTokens}, #{cacheHitTokens}, #{cacheMissTokens}, "
            + "#{reasoningTokens}, #{costEstimate}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(LlmTokenUsage usage);

    /**
     * 插入一条用量记录（不含 turn_id）
     * <p>
     * 兜底用：老库若因方言差异没能迁移出 {@code turn_id} 列，带该列的 insert 会整条失败，
     * 导致成本记账彻底断掉。降级用这条语句写入，代价只是「本轮无法按轮展示」，
     * 但会话/时间维度汇总（成本看板）仍然完整。
     * </p>
     */
    @Insert("INSERT INTO llm_token_usage (conversation_id, message_id, iteration, provider, model, "
            + "prompt_tokens, completion_tokens, total_tokens, cache_hit_tokens, cache_miss_tokens, "
            + "reasoning_tokens, cost_estimate, created_at) VALUES ("
            + "#{conversationId}, #{messageId}, #{iteration}, #{provider}, #{model}, "
            + "#{promptTokens}, #{completionTokens}, #{totalTokens}, #{cacheHitTokens}, #{cacheMissTokens}, "
            + "#{reasoningTokens}, #{costEstimate}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertWithoutTurn(LlmTokenUsage usage);

    /**
     * 会话维度汇总
     */
    @Select("SELECT COUNT(*) AS request_count, "
            + "COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens, "
            + "COALESCE(SUM(completion_tokens), 0) AS completion_tokens, "
            + "COALESCE(SUM(total_tokens), 0) AS total_tokens, "
            + "COALESCE(SUM(cache_hit_tokens), 0) AS cache_hit_tokens, "
            + "COALESCE(SUM(cache_miss_tokens), 0) AS cache_miss_tokens, "
            + "COALESCE(SUM(reasoning_tokens), 0) AS reasoning_tokens, "
            + "COALESCE(SUM(cost_estimate), 0) AS cost_estimate, "
            // COUNT(列) 只数非空值：= 0 表示这批记录都没有单价，金额不可用（≠ 花了 0 元）
            + "COUNT(cost_estimate) AS priced_request_count "
            + "FROM llm_token_usage WHERE conversation_id = #{conversationId}")
    TokenUsageSummaryVO summarizeByConversation(@Param("conversationId") Long conversationId);

    /**
     * 时间范围汇总（from 含，to 不含）
     */
    @Select("SELECT COUNT(*) AS request_count, "
            + "COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens, "
            + "COALESCE(SUM(completion_tokens), 0) AS completion_tokens, "
            + "COALESCE(SUM(total_tokens), 0) AS total_tokens, "
            + "COALESCE(SUM(cache_hit_tokens), 0) AS cache_hit_tokens, "
            + "COALESCE(SUM(cache_miss_tokens), 0) AS cache_miss_tokens, "
            + "COALESCE(SUM(reasoning_tokens), 0) AS reasoning_tokens, "
            + "COALESCE(SUM(cost_estimate), 0) AS cost_estimate, "
            + "COUNT(cost_estimate) AS priced_request_count "
            + "FROM llm_token_usage WHERE created_at >= #{from} AND created_at < #{to}")
    TokenUsageSummaryVO summarizeByTimeRange(@Param("from") LocalDateTime from,
                                             @Param("to") LocalDateTime to);

    /**
     * 按模型分组统计（时间范围内，按费用倒序）
     */
    @Select("SELECT model AS group_key, COUNT(*) AS request_count, "
            + "COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens, "
            + "COALESCE(SUM(completion_tokens), 0) AS completion_tokens, "
            + "COALESCE(SUM(total_tokens), 0) AS total_tokens, "
            + "COALESCE(SUM(cache_hit_tokens), 0) AS cache_hit_tokens, "
            + "COALESCE(SUM(cache_miss_tokens), 0) AS cache_miss_tokens, "
            + "COALESCE(SUM(cost_estimate), 0) AS cost_estimate, "
            + "COUNT(cost_estimate) AS priced_request_count "
            + "FROM llm_token_usage WHERE created_at >= #{from} "
            + "GROUP BY model ORDER BY SUM(cost_estimate) DESC")
    List<TokenUsageGroupVO> summarizeByModel(@Param("from") LocalDateTime from);

    /**
     * 按会话分组统计（时间范围内，按费用倒序取前 N）
     */
    @Select("SELECT CONCAT('', conversation_id) AS group_key, COUNT(*) AS request_count, "
            + "COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens, "
            + "COALESCE(SUM(completion_tokens), 0) AS completion_tokens, "
            + "COALESCE(SUM(total_tokens), 0) AS total_tokens, "
            + "COALESCE(SUM(cache_hit_tokens), 0) AS cache_hit_tokens, "
            + "COALESCE(SUM(cache_miss_tokens), 0) AS cache_miss_tokens, "
            + "COALESCE(SUM(cost_estimate), 0) AS cost_estimate, "
            + "COUNT(cost_estimate) AS priced_request_count "
            + "FROM llm_token_usage WHERE created_at >= #{from} "
            + "GROUP BY conversation_id ORDER BY SUM(cost_estimate) DESC LIMIT #{limit}")
    List<TokenUsageGroupVO> summarizeTopConversations(@Param("from") LocalDateTime from,
                                                      @Param("limit") int limit);

    /**
     * 会话内明细（按 id 正序，便于观察轮次演进）
     */
    @Select("SELECT id, conversation_id, turn_id, message_id, iteration, provider, model, "
            + "prompt_tokens, completion_tokens, total_tokens, cache_hit_tokens, cache_miss_tokens, "
            + "reasoning_tokens, cost_estimate, created_at "
            + "FROM llm_token_usage WHERE conversation_id = #{conversationId} ORDER BY id ASC")
    @Results(id = "tokenUsageResultMap", value = {
            @Result(property = "id", column = "id"),
            @Result(property = "conversationId", column = "conversation_id"),
            @Result(property = "turnId", column = "turn_id"),
            @Result(property = "messageId", column = "message_id"),
            @Result(property = "iteration", column = "iteration"),
            @Result(property = "provider", column = "provider"),
            @Result(property = "model", column = "model"),
            @Result(property = "promptTokens", column = "prompt_tokens"),
            @Result(property = "completionTokens", column = "completion_tokens"),
            @Result(property = "totalTokens", column = "total_tokens"),
            @Result(property = "cacheHitTokens", column = "cache_hit_tokens"),
            @Result(property = "cacheMissTokens", column = "cache_miss_tokens"),
            @Result(property = "reasoningTokens", column = "reasoning_tokens"),
            @Result(property = "costEstimate", column = "cost_estimate"),
            @Result(property = "createdAt", column = "created_at")
    })
    List<LlmTokenUsage> selectByConversationId(@Param("conversationId") Long conversationId);

    /**
     * 会话内按「轮次」聚合（一次用户提问 = 一轮，含工具循环内的多次请求）
     * <p>
     * 只统计 turn_id 非空的记录：存量数据（v3.0.9 及更早）没有 turn_id，
     * 硬塞进某一轮会造成「某轮成本虚高」，让用户误判。它们仍计入会话/时间维度汇总。
     * 排序用 MIN(id) 而非 turn_id：turn_id 是「时间戳-随机串」，按字符串排序在跨天时不可靠。
     * </p>
     */
    @Select("SELECT turn_id AS turn_id, COUNT(*) AS request_count, "
            + "COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens, "
            + "COALESCE(SUM(completion_tokens), 0) AS completion_tokens, "
            + "COALESCE(SUM(total_tokens), 0) AS total_tokens, "
            + "COALESCE(SUM(cache_hit_tokens), 0) AS cache_hit_tokens, "
            + "COALESCE(SUM(cache_miss_tokens), 0) AS cache_miss_tokens, "
            + "COALESCE(SUM(reasoning_tokens), 0) AS reasoning_tokens, "
            + "COALESCE(SUM(cost_estimate), 0) AS cost_estimate, "
            + "COUNT(cost_estimate) AS priced_request_count "
            + "FROM llm_token_usage WHERE conversation_id = #{conversationId} AND turn_id IS NOT NULL "
            + "GROUP BY turn_id ORDER BY MIN(id) ASC")
    List<TokenUsageTurnVO> summarizeByTurn(@Param("conversationId") Long conversationId);

    /**
     * 会话内最近 N 条明细（倒序）
     */
    @Select("SELECT id, conversation_id, message_id, iteration, provider, model, "
            + "prompt_tokens, completion_tokens, total_tokens, cache_hit_tokens, cache_miss_tokens, "
            + "reasoning_tokens, cost_estimate, created_at "
            + "FROM llm_token_usage WHERE conversation_id = #{conversationId} "
            + "ORDER BY id DESC LIMIT #{limit}")
    @ResultMap("tokenUsageResultMap")
    List<LlmTokenUsage> selectRecentByConversationId(@Param("conversationId") Long conversationId,
                                                     @Param("limit") int limit);

    /**
     * 清理指定时间之前的记录（保留期外数据）
     */
    @Delete("DELETE FROM llm_token_usage WHERE created_at < #{before}")
    int deleteBefore(@Param("before") LocalDateTime before);

    /**
     * 删除某个会话的全部用量记录（会话删除时级联清理）
     * <p>
     * 为什么不是「保留历史账单」：用量记录带 conversation_id 但没有外键，
     * 会话删掉后这些行会变成孤儿数据 —— 成本看板里会出现「会话#42（已删除）」的幽灵分组，
     * 且用户无法再通过任何入口定位它。用户明确要求删除会话即删除其成本记录，故按级联处理。
     * 时间维度汇总（按天/按月）不受影响，因为它只关心已经发生的花费。
     * </p>
     */
    @Delete("DELETE FROM llm_token_usage WHERE conversation_id = #{conversationId}")
    int deleteByConversationId(@Param("conversationId") Long conversationId);
}
