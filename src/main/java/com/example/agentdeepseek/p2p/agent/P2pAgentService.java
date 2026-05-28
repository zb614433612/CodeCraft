package com.example.agentdeepseek.p2p.agent;

import com.example.agentdeepseek.mapper.*;
import com.example.agentdeepseek.model.dto.ChatRequest;
import com.example.agentdeepseek.model.dto.PendingQuestion;
import com.example.agentdeepseek.model.entity.*;
import com.example.agentdeepseek.p2p.connection.P2pConnectionManager;
import com.example.agentdeepseek.p2p.protocol.MessageFrame;
import com.example.agentdeepseek.p2p.protocol.MessageType;
import com.example.agentdeepseek.p2p.service.P2pChatService;
import com.example.agentdeepseek.service.DeepSeekService;
import com.example.agentdeepseek.service.PendingQuestionStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * P2P Agent 授权与调用服务
 * <p>
 * 核心逻辑：
 * 1. 授权：双方各存一份记录，方向相反
 * 2. 取消授权：先更新本地DB，再尝试通知对方（best effort）
 * 3. 调用时校验：被调用方收到 AGENT_INVOKE 时必须查本地授权，非 active 则拒绝
 * 4. 上下文继承：通过 p2p_agent_conversation 表实现 peerId+agentId → conversationId 映射
 * </p>
 */
@Service
public class P2pAgentService {

    private static final Logger log = LoggerFactory.getLogger(P2pAgentService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int AI_TIMEOUT_SECONDS = 180;

    @Lazy
    @Autowired
    private P2pConnectionManager connectionManager;

    private final P2pChatService chatService;
    private final DeepSeekService deepSeekService;
    private final PendingQuestionStore pendingQuestionStore;
    private final P2pAgentAuthorizationMapper authMapper;
    private final P2pAgentConversationMapper convMappingMapper;
    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper conversationMessageMapper;
    private final AgentConfigMapper agentConfigMapper;
    private final UserMapper userMapper;
    private final JdbcTemplate jdbcTemplate;

    /** 正在执行的 Agent 调用（requestId → CompletableFuture） */
    private final Map<String, CompletableFuture<String>> pendingInvocations = new ConcurrentHashMap<>();

    public P2pAgentService(P2pChatService chatService,
                           DeepSeekService deepSeekService,
                           PendingQuestionStore pendingQuestionStore,
                           P2pAgentAuthorizationMapper authMapper,
                           P2pAgentConversationMapper convMappingMapper,
                           ConversationMapper conversationMapper,
                           ConversationMessageMapper conversationMessageMapper,
                           AgentConfigMapper agentConfigMapper,
                           UserMapper userMapper,
                           JdbcTemplate jdbcTemplate) {
        this.chatService = chatService;
        this.deepSeekService = deepSeekService;
        this.pendingQuestionStore = pendingQuestionStore;
        this.authMapper = authMapper;
        this.convMappingMapper = convMappingMapper;
        this.conversationMapper = conversationMapper;
        this.conversationMessageMapper = conversationMessageMapper;
        this.agentConfigMapper = agentConfigMapper;
        this.userMapper = userMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void init() {
        authMapper.createTableIfNotExists();
        authMapper.createIndexPeerDir();
        authMapper.createIndexPeerAgent();
        authMapper.createUniqueIndex();
        // 兼容旧表：添加 agent_name 列
        migrateAuthTable();
        convMappingMapper.createTableIfNotExists();
        convMappingMapper.createIndexPeerAgent();
        convMappingMapper.createIndexConv();
        convMappingMapper.createUniqueIndex();
        log.info("[P2P-Agent] Authorization & conversation mapping tables ensured");
    }

    private void migrateAuthTable() {
        String[] alters = {
            "ALTER TABLE p2p_agent_authorization ADD COLUMN agent_name VARCHAR(100) DEFAULT ''",
            "ALTER TABLE p2p_agent_authorization ADD COLUMN agent_description VARCHAR(500) DEFAULT ''",
            "ALTER TABLE p2p_agent_authorization ADD COLUMN agent_avatar VARCHAR(20) DEFAULT '🤖'",
            "ALTER TABLE p2p_agent_authorization ADD COLUMN user_id BIGINT DEFAULT NULL"
        };
        for (String sql : alters) {
            try {
                jdbcTemplate.execute(sql);
                log.info("[P2P-Agent] Migrated: {}", sql);
            } catch (Exception e) {
                log.debug("[P2P-Agent] Migration skipped: {}", e.getMessage());
            }
        }
    }

    // ==================== 授权管理 ====================

    /**
     * 授权 Agent 给对方
     * 调用时机：用户在 P2P 面板勾选 Agent 并点击「授权」
     * @return 冲突用户名（null 表示授权成功）
     */
    public String grantAuthorization(String peerId, List<Long> agentConfigIds, Long userId) {
        for (Long agentId : agentConfigIds) {
            // 冲突检测：同一 peer + 同一 agent + direction=sent，已有其他用户授权过？
            List<P2pAgentAuthorization> existing = authMapper.findActiveByPeerAndDirection(peerId, "sent");
            for (P2pAgentAuthorization exist : existing) {
                if (exist.getAgentConfigId().equals(agentId) && !userId.equals(exist.getUserId())) {
                    String conflictName = getUserName(exist.getUserId());
                    log.warn("[P2P-Agent] Auth conflict: agent={}, peer={}, existingUser={}, newUser={}",
                            agentId, peerId, exist.getUserId(), userId);
                    return conflictName;
                }
            }

            P2pAgentAuthorization auth = new P2pAgentAuthorization(
                    peerId, agentId, getAgentName(agentId),
                    getAgentDescription(agentId), getAgentAvatar(agentId), userId,
                    "sent", "active", LocalDateTime.now());
            authMapper.upsert(auth);
            log.info("[P2P-Agent] Granted agent {} to peer {} by user {}", agentId, peerId, userId);
        }

        // 发送 AGENT_AUTH_GRANT 给对方（best effort），附带 userId
        sendAuthGrantMessage(peerId, agentConfigIds, userId);

        // 记录到聊天
        String agentNames = getAgentNames(agentConfigIds);
        chatService.saveMessage(peerId, connectionManager.getMyName(),
                "授权了 Agent：" + agentNames, "sent",
                "auth_grant", null, null);
        return null;
    }

    /**
     * 取消授权
     * 1. 先更新本地DB status=cancelled（即使对方不在线也生效）
     * 2. 尝试发送 AGENT_AUTH_CANCEL 通知对方（best effort）
     */
    public void cancelAuthorization(String peerId, List<Long> agentConfigIds) {
        for (Long agentId : agentConfigIds) {
            authMapper.cancel(peerId, agentId, "sent");
            log.info("[P2P-Agent] Cancelled agent {} authorization to peer {}", agentId, peerId);
        }

        // 发送取消通知
        sendAuthCancelMessage(peerId, agentConfigIds);

        String agentNames = getAgentNames(agentConfigIds);
        chatService.saveMessage(peerId, connectionManager.getMyName(),
                "取消了 Agent 授权：" + agentNames, "sent",
                "auth_cancel", null, null);
    }

    /**
     * 获取我授权给某节点的 Agent 列表
     * Agent是本地创建的，优先查本地agent_config表；若被清空（覆盖安装），用冗余字段fallback。
     */
    public List<AgentConfig> getMyAuthToPeer(String peerId) {
        List<P2pAgentAuthorization> auths = authMapper.findActiveByPeerAndDirection(peerId, "sent");
        log.info("[P2P-Agent] getMyAuthToPeer: peerId={}, found {} auths", peerId, auths.size());
        List<AgentConfig> result = new ArrayList<>();
        for (P2pAgentAuthorization a : auths) {
            // 优先查本地 agent_config
            var opt = agentConfigMapper.selectById(a.getAgentConfigId());
            if (opt.isPresent() && (opt.get().getEnabled() == null || opt.get().getEnabled() == 1)) {
                result.add(opt.get());
            } else {
                // fallback：用授权记录中的冗余信息
                AgentConfig ac = new AgentConfig();
                ac.setId(a.getAgentConfigId());
                ac.setName(a.getAgentName() != null && !a.getAgentName().isEmpty()
                        ? a.getAgentName() : "Agent#" + a.getAgentConfigId());
                ac.setDescription(a.getAgentDescription());
                ac.setAvatar(a.getAgentAvatar() != null ? a.getAgentAvatar() : "🤖");
                ac.setEnabled(1);
                result.add(ac);
            }
        }
        return result;
    }

    /**
     * 获取某节点授权给我的 Agent 列表（我可以调用的）
     * 注意：对方授权的Agent在本地agent_config表中不一定存在（P2P两端Agent配置独立），
     * 因此直接从授权记录的冗余字段构造 AgentConfig，不查 agent_config 表。
     */
    public List<AgentConfig> getPeerAuthToMe(String peerId) {
        List<P2pAgentAuthorization> auths = authMapper.findActiveByPeerAndDirection(peerId, "received");
        log.info("[P2P-Agent] getPeerAuthToMe: peerId={}, found {} auths", peerId, auths.size());
        List<AgentConfig> result = new ArrayList<>();
        for (P2pAgentAuthorization a : auths) {
            AgentConfig ac = new AgentConfig();
            ac.setId(a.getAgentConfigId());
            ac.setName(a.getAgentName() != null && !a.getAgentName().isEmpty()
                    ? a.getAgentName() : "Agent#" + a.getAgentConfigId());
            ac.setDescription(a.getAgentDescription());
            ac.setAvatar(a.getAgentAvatar() != null ? a.getAgentAvatar() : "🤖");
            ac.setEnabled(1);
            result.add(ac);
        }
        return result;
    }

    // ==================== 处理收到的授权消息 ====================

    /**
     * 处理对方发来的授权通知
     */
    public void handleIncomingGrant(String peerId, List<Map<String, Object>> agents, Long fromUserId) {
        log.info("[P2P-Agent] handleIncomingGrant: peerId={}, fromUserId={}, agents={}", peerId, fromUserId, agents);
        int stored = 0;
        for (Map<String, Object> agent : agents) {
            Object rawId = agent.get("id");
            Long agentId = toLong(rawId);
            String agentName = (String) agent.get("name");
            String agentDesc = (String) agent.getOrDefault("description", "");
            String agentAvatar = (String) agent.getOrDefault("avatar", "🤖");
            log.info("[P2P-Agent] handleIncomingGrant agent: rawId={} (type={}), agentId={}, name={}",
                    rawId, rawId != null ? rawId.getClass().getSimpleName() : "null", agentId, agentName);
            if (agentId == null || agentId <= 0) {
                log.warn("[P2P-Agent] handleIncomingGrant skip invalid agentId: {}", agentId);
                continue;
            }

            P2pAgentAuthorization auth = new P2pAgentAuthorization(
                    peerId, agentId, agentName, agentDesc, agentAvatar, fromUserId,
                    "received", "active", LocalDateTime.now());
            try {
                authMapper.upsert(auth);
                stored++;
                log.info("[P2P-Agent] handleIncomingGrant upsert OK: peerId={}, agentId={}", peerId, agentId);
            } catch (Exception e) {
                log.error("[P2P-Agent] handleIncomingGrant upsert FAILED: peerId={}, agentId={}", peerId, agentId, e);
            }
        }
        log.info("[P2P-Agent] handleIncomingGrant stored {} of {} agents", stored, agents.size());

        // 记录到聊天
        List<String> names = agents.stream().map(a -> (String) a.get("name")).filter(Objects::nonNull).toList();
        String content = "授权了 Agent 给你：" + String.join("、", names);
        chatService.saveMessage(peerId, "系统", content, "received",
                "auth_grant", null, null);
        storeToMessageQueue(peerId, "系统", content, "received", "auth_grant", null, null);
    }

    /**
     * 处理对方发来的取消授权通知
     */
    public void handleIncomingCancel(String peerId, List<Long> agentIds) {
        for (Long agentId : agentIds) {
            authMapper.cancel(peerId, agentId, "received");
        }

        String agentNames = getAgentNames(agentIds);
        String content = "取消了 Agent 授权：" + agentNames;
        chatService.saveMessage(peerId, "系统", content, "received",
                "auth_cancel", null, null);
        storeToMessageQueue(peerId, "系统", content, "received", "auth_cancel", null, null);
    }

    // ==================== Agent 调用 ====================

    /**
     * 调用对方已授权的 Agent（我是调用方）
     */
    public String invokeAgent(String peerId, Long agentConfigId, String message, Long userId) {
        String requestId = UUID.randomUUID().toString();
        String agentName = getAgentName(agentConfigId);

        // 构建 payload（附带 userId，被调用方用来创建 conversation）
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("requestId", requestId);
        payload.put("agentConfigId", agentConfigId);
        payload.put("agentName", agentName);
        payload.put("message", message);
        payload.put("userId", userId);

        // 发送 AGENT_INVOKE
        MessageFrame frame = new MessageFrame(MessageType.AGENT_INVOKE, payload.toString());
        try {
            connectionManager.send(peerId, frame).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("[P2P-Agent] Failed to send AGENT_INVOKE to {}", peerId, e);
            return null;
        }

        // 记录到聊天
        chatService.saveMessage(peerId, connectionManager.getMyName(),
                "调用 Agent「" + agentName + "」: " + message, "sent",
                "agent_invoke", agentConfigId, agentName);

        return requestId;
    }

    /**
     * 处理对方发来的 Agent 调用请求（我是被调用方，需要执行 AI）
     * <p>
     * ★ 安全关键：必须校验本地授权记录（direction=sent），非 active 则拒绝
     * 这保证了即使取消授权通知没有送达，对方也无法绕过
     * </p>
     */
    public String handleIncomingInvoke(String peerId, String requestId,
                                        Long agentConfigId, String agentName, String message,
                                        Long fromUserId) {
        // ====== 校验授权 ======
        P2pAgentAuthorization auth = authMapper.findActive(peerId, agentConfigId, "sent");
        if (auth == null) {
            log.warn("[P2P-Agent] Rejected agent invoke from {}: agent {} not authorized (or cancelled)", peerId, agentConfigId);
            // 发送拒绝响应
            sendAgentResponse(peerId, requestId, agentConfigId, agentName,
                    "❌ 调用被拒绝：你无权使用此 Agent，请联系对方重新授权", "rejected");
            // 也记录到本机聊天
            String rejectMsg = "对方尝试调用 Agent「" + agentName + "」被拒绝：未授权";
            chatService.saveMessage(peerId, "系统", rejectMsg, "received",
                    "agent_response", agentConfigId, agentName);
            storeToMessageQueue(peerId, "系统", rejectMsg, "received",
                    "agent_response", agentConfigId, agentName);
            return "rejected";
        }

        log.info("[P2P-Agent] Handling agent invoke from {}: agent={}, message={}", peerId, agentConfigId, message);

        // ====== 获取或创建会话（上下文继承） ======
        Long conversationId = getOrCreateConversation(peerId, agentConfigId, agentName, fromUserId);

        // ====== 记录对方调用消息 ======
        String invokeMsg = "对方调用 Agent「" + agentName + "」: " + message;
        chatService.saveMessage(peerId, "系统", invokeMsg, "received",
                "agent_invoke", agentConfigId, agentName);
        storeToMessageQueue(peerId, "系统", invokeMsg, "received",
                "agent_invoke", agentConfigId, agentName);

        // ====== 执行 AI ======
        String resultContent;
        try {
            resultContent = executeAiSync(conversationId, message, agentConfigId);
        } catch (Exception e) {
            log.error("[P2P-Agent] AI execution failed for peer={}", peerId, e);
            sendAgentResponse(peerId, requestId, agentConfigId, agentName,
                    "❌ Agent 执行出错：" + e.getMessage(), "error");
            chatService.saveMessage(peerId, "系统",
                    "Agent「" + agentName + "」执行出错：" + e.getMessage(), "received",
                    "agent_response", agentConfigId, agentName);
            storeToMessageQueue(peerId, "系统",
                    "Agent「" + agentName + "」执行出错：" + e.getMessage(),
                    "received", "agent_response", agentConfigId, agentName);
            return "error";
        }

        // ====== 发送响应 ======
        sendAgentResponse(peerId, requestId, agentConfigId, agentName, resultContent, "completed");

        // ====== 记录 Agent 回复（sent方向 = 我发出的） ======
        chatService.saveMessage(peerId, "Agent「" + agentName + "」",
                resultContent, "sent",
                "agent_response", agentConfigId, agentName);
        storeToMessageQueue(peerId, "Agent「" + agentName + "」",
                resultContent, "sent",
                "agent_response", agentConfigId, agentName);

        return resultContent;
    }

    /**
     * 处理对方发来的 Agent 响应（我是调用方）
     */
    public void handleIncomingResponse(String peerId, String requestId,
                                        Long agentConfigId, String agentName,
                                        String content, String status) {
        log.info("[P2P-Agent] Received agent response from {}: agent={}, status={}", peerId, agentConfigId, status);

        // 记录到聊天
        String displayContent = content;
        if ("rejected".equals(status)) {
            displayContent = content;
        } else if ("error".equals(status)) {
            displayContent = content;
        }

        chatService.saveMessage(peerId, "Agent「" + agentName + "」",
                displayContent, "received",
                "agent_response", agentConfigId, agentName);
        storeToMessageQueue(peerId, "Agent「" + agentName + "」",
                displayContent, "received", "agent_response", agentConfigId, agentName);
    }

    // ==================== 内部方法 ====================

    /**
     * 获取或创建 peerId + agentConfigId 对应的会话
     * 实现上下文继承：同一台机器对同一Agent始终复用同一会话
     */
    private Long getOrCreateConversation(String peerId, Long agentConfigId, String agentName, Long userId) {
        P2pAgentConversation mapping = convMappingMapper.findByPeerAndAgent(peerId, agentConfigId);
        if (mapping != null) {
            convMappingMapper.updateTime(mapping.getId());
            log.info("[P2P-Agent] Reusing conversation {} for peer={}, agent={}",
                    mapping.getConversationId(), peerId, agentConfigId);
            return mapping.getConversationId();
        }

        // 创建新会话（userId 设为 1=admin，确保 AI 助手页面能查到）
        Conversation conv = new Conversation();
        conv.setName("P2P-" + agentName + "-" + peerId.substring(0, 8));
        conv.setUserId(userId); // 使用调用方传过来的 userId
        conv.setAgentType("code_assistant");
        conv.setAgentConfigId(agentConfigId);
        conv.setCreatedAt(LocalDateTime.now());
        conv.setUpdatedAt(LocalDateTime.now());
        conversationMapper.insert(conv);

        // 保存映射
        P2pAgentConversation newMapping = new P2pAgentConversation(
                peerId, agentConfigId, conv.getId(), LocalDateTime.now());
        convMappingMapper.insert(newMapping);

        log.info("[P2P-Agent] Created new conversation {} for peer={}, agent={}",
                conv.getId(), peerId, agentConfigId);
        return conv.getId();
    }

    /**
     * 同步执行 AI（阻塞等待完成）
     * 使用 DeepSeekService.streamChat + Flux 收集
     */
    private String executeAiSync(Long conversationId, String message, Long agentConfigId) throws Exception {
        ChatRequest request = new ChatRequest();
        request.setMessage(message);
        request.setSessionId(conversationId);
        request.setExecutionMode("auto");  // P2P 调用使用自动模式
        request.setAgentConfigId(agentConfigId);

        // 创建 CompletableFuture 等待完成
        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        StringBuilder contentBuilder = new StringBuilder();

        // 订阅 streamChat，收集所有内容
        deepSeekService.streamChat(request)
                .take(Duration.ofSeconds(AI_TIMEOUT_SECONDS))
                .subscribe(
                        event -> {
                            // 解析 SSE 事件，提取 content
                            try {
                                if (event.startsWith("data: ")) {
                                    event = event.substring(6);
                                }
                                JsonNode node = MAPPER.readTree(event);
                                String eventType = node.has("event") ? node.get("event").asText() : "";

                                if ("chunk".equals(eventType) || "content".equals(eventType)) {
                                    String chunk = node.has("content") ? node.get("content").asText() : "";
                                    contentBuilder.append(chunk);
                                } else if ("done".equals(eventType)) {
                                    // 流结束，从数据库读取最终 assistant 消息
                                    String finalContent = getLastAssistantContent(conversationId);
                                    resultFuture.complete(finalContent != null ? finalContent : contentBuilder.toString());
                                } else if ("ask_user".equals(eventType)) {
                                    // P2P 模式下自动批准需要用户确认的操作
                                    String uuid = node.has("uuid") ? node.get("uuid").asText() : null;
                                    if (uuid != null) {
                                        try {
                                            PendingQuestion pq = pendingQuestionStore.get(uuid);
                                            if (pq != null) {
                                                pq.getFuture().complete("批准");
                                                pendingQuestionStore.remove(uuid);
                                                log.info("[P2P-Agent] Auto-approved ask_user uuid={}", uuid);
                                            }
                                        } catch (Exception ex) {
                                            log.warn("[P2P-Agent] Failed to auto-approve ask_user: {}", ex.getMessage());
                                        }
                                    }
                                } else if ("error".equals(eventType)) {
                                    String errMsg = node.has("content") ? node.get("content").asText() : "Unknown error";
                                    resultFuture.completeExceptionally(new RuntimeException(errMsg));
                                }
                            } catch (Exception e) {
                                // 非 JSON 事件，可能是纯文本 chunk
                                contentBuilder.append(event);
                            }
                        },
                        error -> {
                            log.error("[P2P-Agent] AI stream error for conversation {}", conversationId, error);
                            // 即使流出错，也尝试读取数据库中的部分结果
                            String partial = getLastAssistantContent(conversationId);
                            if (partial != null) {
                                resultFuture.complete(partial);
                            } else {
                                resultFuture.completeExceptionally(error);
                            }
                        },
                        () -> {
                            // 流正常结束但没收到 done 事件
                            if (!resultFuture.isDone()) {
                                String finalContent = getLastAssistantContent(conversationId);
                                resultFuture.complete(finalContent != null ? finalContent : contentBuilder.toString());
                            }
                        }
                );

        try {
            return resultFuture.get(AI_TIMEOUT_SECONDS + 10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // 超时：尝试读取数据库中的部分结果
            String partial = getLastAssistantContent(conversationId);
            return partial != null ? partial + "\n\n（已超时，仅返回部分结果）" : "❌ Agent 执行超时";
        }
    }

    /**
     * 从数据库读取最后一条 assistant 消息的内容
     */
    private String getLastAssistantContent(Long conversationId) {
        try {
            List<ConversationMessage> messages = conversationMessageMapper.selectByConversationId(conversationId);
            if (messages != null) {
                // 从后往前找最后一条 assistant 消息
                for (int i = messages.size() - 1; i >= 0; i--) {
                    ConversationMessage msg = messages.get(i);
                    if (msg.getRole() == MessageRole.ASSISTANT && msg.getContent() != null) {
                        return msg.getContent();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[P2P-Agent] Failed to read assistant content for conversation {}", conversationId, e);
        }
        return null;
    }

    // ==================== 消息发送 ====================

    private void sendAuthGrantMessage(String peerId, List<Long> agentIds, Long userId) {
        List<Map<String, Object>> agents = new ArrayList<>();
        for (Long id : agentIds) {
            Map<String, Object> a = new HashMap<>();
            a.put("id", id);
            a.put("name", getAgentName(id));
            a.put("description", getAgentDescription(id));
            a.put("avatar", getAgentAvatar(id));
            agents.add(a);
        }
        ObjectNode payload = MAPPER.createObjectNode();
        payload.putPOJO("agents", agents);
        payload.put("userId", userId);
        payload.put("timestamp", System.currentTimeMillis());
        sendAsync(peerId, new MessageFrame(MessageType.AGENT_AUTH_GRANT, payload.toString()));
    }

    private void sendAuthCancelMessage(String peerId, List<Long> agentIds) {
        ObjectNode payload = MAPPER.createObjectNode();
        ArrayNode arr = payload.putArray("agentIds");
        agentIds.forEach(arr::add);
        payload.put("timestamp", System.currentTimeMillis());
        sendAsync(peerId, new MessageFrame(MessageType.AGENT_AUTH_CANCEL, payload.toString()));
    }

    private void sendAgentResponse(String peerId, String requestId, Long agentConfigId,
                                    String agentName, String content, String status) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("requestId", requestId);
        payload.put("agentConfigId", agentConfigId);
        payload.put("agentName", agentName);
        payload.put("content", content);
        payload.put("status", status);
        sendAsync(peerId, new MessageFrame(MessageType.AGENT_RESPONSE, payload.toString()));
    }

    private void sendAsync(String peerId, MessageFrame frame) {
        try {
            connectionManager.send(peerId, frame);
        } catch (Exception e) {
            log.warn("[P2P-Agent] Failed to send {} to {}: {}", frame.getType(), peerId, e.getMessage());
        }
    }

    /**
     * 将系统消息（授权/响应等）存入内存队列，供前端轮询拉取
     * 构造 CHAT_MESSAGE 帧，payload 包含 messageType 等扩展字段
     */
    private void storeToMessageQueue(String peerId, String senderName, String content,
                                      String direction, String messageType,
                                      Long agentConfigId, String agentName) {
        try {
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("name", senderName);
            payload.put("content", content);
            payload.put("messageType", messageType);
            if (agentConfigId != null) payload.put("agentConfigId", agentConfigId);
            if (agentName != null) payload.put("agentName", agentName);
            MessageFrame frame = new MessageFrame(MessageType.CHAT_MESSAGE, payload.toString());
            connectionManager.storeMessage(peerId, frame);
        } catch (Exception e) {
            log.warn("[P2P-Agent] Failed to store message to queue for peer {}", peerId, e);
        }
    }

    // ==================== 辅助方法 ====================

    private String getUserName(Long userId) {
        if (userId == null) return "未知用户";
        try {
            return userMapper.selectById(userId)
                    .map(u -> u.getNickname() != null ? u.getNickname() : u.getUsername())
                    .orElse("用户#" + userId);
        } catch (Exception e) {
            return "用户#" + userId;
        }
    }

    private String getAgentName(Long agentConfigId) {
        try {
            return agentConfigMapper.selectById(agentConfigId)
                    .map(AgentConfig::getName)
                    .orElse("Agent#" + agentConfigId);
        } catch (Exception e) {
            return "Agent#" + agentConfigId;
        }
    }

    private String getAgentDescription(Long agentConfigId) {
        try {
            return agentConfigMapper.selectById(agentConfigId)
                    .map(AgentConfig::getDescription)
                    .orElse("");
        } catch (Exception e) {
            return "";
        }
    }

    private String getAgentAvatar(Long agentConfigId) {
        try {
            return agentConfigMapper.selectById(agentConfigId)
                    .map(a -> a.getAvatar() != null ? a.getAvatar() : "🤖")
                    .orElse("🤖");
        } catch (Exception e) {
            return "🤖";
        }
    }

    private String getAgentNames(List<Long> agentIds) {
        return agentIds.stream().map(this::getAgentName).reduce((a, b) -> a + "、" + b).orElse("");
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> parseAgentsFromPayload(String payload) {
        try {
            JsonNode node = MAPPER.readTree(payload);
            JsonNode agentsNode = node.get("agents");
            if (agentsNode != null && agentsNode.isArray()) {
                List<Map<String, Object>> result = new ArrayList<>();
                for (JsonNode a : agentsNode) {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", a.get("id").asLong());
                    map.put("name", a.has("name") ? a.get("name").asText() : "");
                    result.add(map);
                }
                return result;
            }
        } catch (Exception e) {
            log.warn("[P2P-Agent] Failed to parse agents from payload", e);
        }
        return List.of();
    }

    public static List<Long> parseAgentIdsFromPayload(String payload) {
        try {
            JsonNode node = MAPPER.readTree(payload);
            JsonNode arr = node.get("agentIds");
            if (arr != null && arr.isArray()) {
                List<Long> result = new ArrayList<>();
                for (JsonNode a : arr) {
                    result.add(a.asLong());
                }
                return result;
            }
        } catch (Exception e) {
            log.warn("[P2P-Agent] Failed to parse agentIds from payload", e);
        }
        return List.of();
    }

    public static String parseStringField(String payload, String field) {
        try {
            JsonNode node = MAPPER.readTree(payload);
            return node.has(field) ? node.get(field).asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static Long parseLongField(String payload, String field) {
        try {
            JsonNode node = MAPPER.readTree(payload);
            return node.has(field) && !node.get(field).isNull() ? node.get(field).asLong() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Long toLong(Object val) {
        if (val instanceof Number n) return n.longValue();
        if (val instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }
}
