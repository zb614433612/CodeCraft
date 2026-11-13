package com.example.agentdeepseek.mapper;

import com.example.agentdeepseek.model.entity.LlmTokenUsage;
import com.example.agentdeepseek.model.vo.TokenUsageGroupVO;
import com.example.agentdeepseek.model.vo.TokenUsageSummaryVO;
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
    @Insert("INSERT INTO llm_token_usage (conversation_id, message_id, iteration, provider, model, "
            + "prompt_tokens, completion_tokens, total_tokens, cache_hit_tokens, cache_miss_tokens, "
            + "reasoning_tokens, cost_estimate, created_at) VALUES ("
            + "#{conversationId}, #{messageId}, #{iteration}, #{provider}, #{model}, "
            + "#{promptTokens}, #{completionTokens}, #{totalTokens}, #{cacheHitTokens}, #{cacheMissTokens}, "
            + "#{reasoningTokens}, #{costEstimate}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(LlmTokenUsage usage);

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
            + "COALESCE(SUM(cost_estimate), 0) AS cost_estimate "
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
            + "COALESCE(SUM(cost_estimate), 0) AS cost_estimate "
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
            + "COALESCE(SUM(cost_estimate), 0) AS cost_estimate "
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
            + "COALESCE(SUM(cost_estimate), 0) AS cost_estimate "
            + "FROM llm_token_usage WHERE created_at >= #{from} "
            + "GROUP BY conversation_id ORDER BY SUM(cost_estimate) DESC LIMIT #{limit}")
    List<TokenUsageGroupVO> summarizeTopConversations(@Param("from") LocalDateTime from,
                                                      @Param("limit") int limit);

    /**
     * 会话内明细（按 id 正序，便于观察轮次演进）
     */
    @Select("SELECT id, conversation_id, message_id, iteration, provider, model, "
            + "prompt_tokens, completion_tokens, total_tokens, cache_hit_tokens, cache_miss_tokens, "
            + "reasoning_tokens, cost_estimate, created_at "
            + "FROM llm_token_usage WHERE conversation_id = #{conversationId} ORDER BY id ASC")
    @Results(id = "tokenUsageResultMap", value = {
            @Result(property = "id", column = "id"),
            @Result(property = "conversationId", column = "conversation_id"),
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
}
