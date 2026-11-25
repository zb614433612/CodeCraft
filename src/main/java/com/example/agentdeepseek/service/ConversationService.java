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
     * 分页查询会话消息（带上下文模式，2026-11-25）。
     *
     * <p>为什么要带 mode：分页接口要回传「当前上下文大小」，而三种模式的真实请求体差异巨大 ——
     * full 回传整段思考链、balanced 置空占位、compact 再把历史工具结果压成摘要。
     * 不带 mode 就只能拿到「上一次请求所用模式」的旧值，切换模式后界面数字纹丝不动。</p>
     *
     * @param conversationId 会话 ID
     * @param beforeId       分页游标（首页传 null）
     * @param rounds         本次加载轮数
     * @param contextMode    full / balanced / compact；null 时按后端默认解析
     * @return 分页结果（含按 mode 计算的 contextTokens）
     */
    ConversationMessagePageVO getMessagesPage(Long conversationId, Long beforeId, int rounds, String contextMode);

    /**
     * 按上下文模式计算「当前会发给模型的上下文 token 数」（2026-11-25）。
     *
     * <p>口径与真实重建严格对齐：压缩摘要 + 水位线之后的全部消息；
     * full 计入历史 reasoning，balanced 不计入，compact 额外把历史工具结果按摘要长度计入；
     * 最后补上 tools 定义的固定地板。供分页接口与「切换模式即时刷新」接口共用。</p>
     *
     * @param conversationId 会话 ID
     * @param contextMode    full / balanced / compact；null 时按后端默认解析
     * @return 估算 token；无消息返回 0
     */
    int computeContextTokens(Long conversationId, String contextMode);

    /**
     * 只算上下文统计（不返回消息）——聊天页切换上下文模式时的轻量刷新入口（2026-11-25）。
     *
     * <p>返回的 {@code ConversationMessagePageVO} 中 {@code messages} 为 {@code null}，
     * 调用方只读 {@code contextTokens / contextMode / lastPromptTokens}。
     * 相比重新拉一遍消息分页，它不会重置前端的翻页游标与已加载历史。</p>
     *
     * @param conversationId 会话 ID
     * @param contextMode    full / balanced / compact；null 时按后端默认解析
     * @return 仅含上下文统计字段的分页 VO
     */
    ConversationMessagePageVO getContextStats(Long conversationId, String contextMode);

    /**
     * 从指定轮次起删除消息（含该轮自身）——「重新生成」的前置动作。
     *
     * <p>语义：找到该 turnId 对应的最早一条消息（即那轮的 user 提问），
     * 把它及其后所有消息一并删除，让前端可以原样重发同一条提问，
     * 而不会在历史里留下两条并存的回复。</p>
     *
     * <p>只删消息、不动快照：这一轮真实发生过的文件改动仍可从「改动与回滚」面板回退，
     * 删掉快照等于把用户的撤销能力一并清掉。</p>
     *
     * @param conversationId 会话ID
     * @param turnId         轮次ID（前端生成，落在 user 消息的 turn_id 上）
     * @return 删除的消息条数；turnId 找不到对应消息时返回 0
     */
    int deleteMessagesFromTurn(Long conversationId, String turnId);

    /**
     * 删除会话及其关联消息
     * @param conversationId 会话ID
     * @return 删除是否成功
     */
    boolean deleteConversation(Long conversationId);
}