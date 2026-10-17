package com.example.agentdeepseek.service;

import com.example.agentdeepseek.model.entity.Conversation;
import com.example.agentdeepseek.model.entity.ConversationMessage;
import com.example.agentdeepseek.model.vo.ConversationMessagePageVO;

import java.util.List;

/**
 * 会话服务接口
 */
public interface ConversationService {

    /**
     * 根据用户ID查询会话列表
     * @param userId 用户ID
     * @param agentType 会话类型（可选，为空则查询所有）
     * @return 会话列表
     */
    List<Conversation> getConversationsByUserId(Long userId, String agentType);

    /**
     * 根据用户ID和Agent配置ID查询会话列表（切换 Agent 时用）
     */
    List<Conversation> getConversationsByAgentConfigId(Long userId, Long agentConfigId);

    /**
     * 根据会话ID查询消息列表（不分页）
     * @param conversationId 会话ID
     * @return 消息列表
     */
    List<ConversationMessage> getMessagesByConversationId(Long conversationId);

    /**
     * 按「轮次」游标分页查询会话消息（从最新往前翻，用于聊天记录懒加载）。
     *
     * <p>一轮 = 一条用户提问（非 desktop_inject）+ 其后全部回复；首页不传 beforeId，
     * 返回最近 rounds 轮；继续往前翻时把上一页的 nextBeforeId 作为 beforeId 传入。</p>
     *
     * @param conversationId 会话ID
     * @param beforeId       分页游标（上一页最早消息的 id；首页传 null）
     * @param rounds         本次加载的轮数（&lt;=0 按默认 5 处理，上限 50）
     * @return 分页结果（messages 按 id 升序 + hasMore + nextBeforeId）
     */
    ConversationMessagePageVO getMessagesPage(Long conversationId, Long beforeId, int rounds);

    /**
     * 删除会话及其关联消息
     * @param conversationId 会话ID
     * @return 删除是否成功
     */
    boolean deleteConversation(Long conversationId);
}