package com.example.agentdeepseek.service.impl;

import com.example.agentdeepseek.config.DeepSeekConfig;
import com.example.agentdeepseek.mapper.ConversationMapper;
import com.example.agentdeepseek.mapper.ConversationMessageMapper;
import com.example.agentdeepseek.mapper.AgentConfigMapper;
import com.example.agentdeepseek.model.dto.ChatRequest;

import com.example.agentdeepseek.model.entity.AgentTask;
import com.example.agentdeepseek.model.entity.AgentConfig;
import com.example.agentdeepseek.model.entity.Conversation;
import com.example.agentdeepseek.model.entity.ConversationMessage;
import com.example.agentdeepseek.model.entity.MessageRole;
import com.example.agentdeepseek.model.entity.Skill;

import com.example.agentdeepseek.service.DeepSeekService;
import com.example.agentdeepseek.service.PendingQuestionStore;
import com.example.agentdeepseek.service.SkillMatcher;
import com.example.agentdeepseek.service.SkillService;
import com.example.agentdeepseek.service.SnapshotService;
import com.example.agentdeepseek.service.ConfigService;
import com.example.agentdeepseek.util.TokenEstimator;
import com.example.agentdeepseek.tool.ExecutionTokenManager;
import com.example.agentdeepseek.tool.PermissionContext;
import com.example.agentdeepseek.tool.ToolExecutor;
import com.example.agentdeepseek.tool.impl.DeepSeekAnalyzer;
import com.example.agentdeepseek.util.ProjectRootContext;
import com.example.agentdeepseek.tool.permission.PathSecurityChecker;
import com.example.agentdeepseek.tool.permission.ToolPermissionRegistry;
import com.example.agentdeepseek.util.OperationDetailGenerator;
import com.example.agentdeepseek.util.PromptUtil;
import com.example.agentdeepseek.util.TokenEstimator;
import com.example.agentdeepseek.util.ToolContext;
import com.example.agentdeepseek.model.SubAgentResult;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DeepSeek API服务实现类
 * 处理与DeepSeek API的通信
 */
@Slf4j
@Service
public class DeepSeekServiceImpl implements DeepSeekService, InitializingBean {

    private WebClient webClient;
    private final DeepSeekConfig deepSeekConfig;
    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper conversationMessageMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolExecutor toolExecutor;
    private final PendingQuestionStore pendingQuestionStore;
    private final ExecutionTokenManager executionTokenManager;
    private final SkillService skillService;
    private final SkillMatcher skillMatcher;
    private final DeepSeekAnalyzer deepSeekAnalyzer;

    private final SnapshotService snapshotService;
    private final OperationDetailGenerator detailGenerator;
    private final ToolPermissionRegistry permissionRegistry;
    private final PathSecurityChecker pathSecurityChecker;
    private final CompactionService compactionService;
    private final AgentEventBus agentEventBus;

    @Autowired
    private AgentForkManager agentForkManager;

    @Autowired
    private ConfigService configService;

    @Autowired
    private AgentConfigMapper agentConfigMapper;

    // 常量定义
    private static final String DATA_PREFIX = "data: ";
    private static final String TEMPERATURE_FIELD = "temperature";
    private static final int MAX_TOOL_CALL_ITERATIONS = 50;
    private static final double DEFAULT_TEMPERATURE = 1.0;
    private static final int SESSION_NAME_TRUNCATE_LENGTH = 6;
    private static final int MAX_JUDGE_GRANTED_ITERATIONS = 100; // 评委最多累计允许增加的迭代次数

    // ===== 后台任务相关 =====
    private final ExecutorService taskExecutor = new ThreadPoolExecutor(
            2,                              // corePoolSize — 核心常驻线程数
            10,                             // maximumPoolSize — 最大线程数
            30L,                            // keepAliveTime — 空闲线程回收时间
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100), // 工作队列容量，排队超出后创建新线程
            r -> {
                Thread t = new Thread(r, "bg-task-");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy() // 拒绝策略：调用者线程执行
    );
    // 活跃任务的 Sinks 事件总线（conversationId → Sink），委托给 AgentEventBus 管理
    // 活跃任务的 Flux 订阅引用（conversationId → Disposable，用于取消）
    private final Map<Long, Disposable> taskSubscriptions = new ConcurrentHashMap<>();
    // 评委已批准的额外迭代次数（conversationId → totalGranted）
    private final Map<Long, Integer> judgeGrantedIterations = new ConcurrentHashMap<>();


    public DeepSeekServiceImpl(WebClient deepSeekWebClient,
                               DeepSeekConfig deepSeekConfig,
                               ConversationMapper conversationMapper,
                               ConversationMessageMapper conversationMessageMapper,
                               JdbcTemplate jdbcTemplate,
                               ToolExecutor toolExecutor,
                               PendingQuestionStore pendingQuestionStore,
                               ExecutionTokenManager executionTokenManager,
                               SkillService skillService,
                               SkillMatcher skillMatcher,
                               DeepSeekAnalyzer deepSeekAnalyzer,
                               SnapshotService snapshotService,
                               OperationDetailGenerator detailGenerator,
                               ToolPermissionRegistry permissionRegistry,
                               PathSecurityChecker pathSecurityChecker,
                               CompactionService compactionService,
                               AgentEventBus agentEventBus) {
        this.webClient = deepSeekWebClient;
        this.deepSeekConfig = deepSeekConfig;
        this.conversationMapper = conversationMapper;
        this.conversationMessageMapper = conversationMessageMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.toolExecutor = toolExecutor;
        this.pendingQuestionStore = pendingQuestionStore;
        this.executionTokenManager = executionTokenManager;
        this.skillService = skillService;
        this.skillMatcher = skillMatcher;
        this.deepSeekAnalyzer = deepSeekAnalyzer;
        this.snapshotService = snapshotService;
        this.detailGenerator = detailGenerator;
        this.permissionRegistry = permissionRegistry;
        this.pathSecurityChecker = pathSecurityChecker;
        this.compactionService = compactionService;
        this.agentEventBus = agentEventBus;
    }

    @jakarta.annotation.PostConstruct
    public void initDynamicApiKey() {
        // 为 WebClient 添加过滤器，每次请求前从数据库获取最新的 API Key
        this.webClient = this.webClient.mutate()
                .filter((request, next) -> {
                    String userKey = configService.getValue("deepseek_api_key");
                    if (userKey == null || userKey.isEmpty()) {
                        // 未配置 Key 时，返回 SSE 提示消息引导用户去配置页面
                        String msg = "⚠️ 未配置 DeepSeek API Key，请先在左侧菜单底部「配置」页面设置 API Key 后再使用聊天功能。";
                        String sseData = "data: {\"content\": \"" + msg + "\"}\n\ndata: [DONE]\n\n";
                        java.nio.charset.Charset utf8 = java.nio.charset.StandardCharsets.UTF_8;
                        org.springframework.core.io.buffer.DataBuffer buffer =
                                new org.springframework.core.io.buffer.DefaultDataBufferFactory()
                                        .wrap(sseData.getBytes(utf8));
                        return Mono.just(ClientResponse.create(HttpStatus.OK)
                                .header("Content-Type", "text/event-stream")
                                .body(Flux.just(buffer))
                                .build());
                    }
                    // 创建新请求，设置动态 API Key（request.headers() 是只读的，不能用 set）
                    ClientRequest newRequest = ClientRequest.from(request)
                            .headers(headers -> headers.set("Authorization", "Bearer " + userKey))
                            .build();
                    return next.exchange(newRequest);
                })
                .build();
    }

    @Override
    public void afterPropertiesSet() {
        try {
            // 创建表（如果不存在）
            conversationMapper.createTable();
            conversationMessageMapper.createTable();

            // 修改现有表的列类型为LONGTEXT（如果还是TEXT类型）
            try {
                jdbcTemplate.execute("ALTER TABLE conversation_message MODIFY COLUMN content LONGTEXT");
                log.debug("修改conversation_message.content列为LONGTEXT成功");
            } catch (Exception e) {
                log.debug("修改conversation_message.content列失败，可能已经是LONGTEXT: {}", e.getMessage());
            }
            try {
                jdbcTemplate.execute("ALTER TABLE conversation_message MODIFY COLUMN reasoning LONGTEXT");
                log.debug("修改conversation_message.reasoning列为LONGTEXT成功");
            } catch (Exception e) {
                log.debug("修改conversation_message.reasoning列失败，可能已经是LONGTEXT: {}", e.getMessage());
            }

            // 添加tool_calls列到conversation_message表（如果不存在）
            try {
                jdbcTemplate.execute("ALTER TABLE conversation_message ADD COLUMN tool_calls LONGTEXT COMMENT '工具调用数据块（JSON格式）' AFTER reasoning");
                log.debug("添加conversation_message.tool_calls列成功");
            } catch (Exception e) {
                log.debug("添加conversation_message.tool_calls列失败，可能已经存在: {}", e.getMessage());
            }

            // 添加turn_id列到conversation_message表（用于匹配回滚快照）
            try {
                jdbcTemplate.execute("ALTER TABLE conversation_message ADD COLUMN turn_id VARCHAR(50) COMMENT '前端生成的turnId，用于匹配回滚快照' AFTER tool_calls");
                log.debug("添加conversation_message.turn_id列成功");
            } catch (Exception e) {
                log.debug("添加conversation_message.turn_id列失败，可能已经存在: {}", e.getMessage());
            }

            // 添加user_id列到conversation表（如果不存在）
            try {
                jdbcTemplate.execute("ALTER TABLE conversation ADD COLUMN user_id BIGINT COMMENT '用户ID，关联sys_user.id'");
                log.debug("添加conversation.user_id列成功");
            } catch (Exception e) {
                log.debug("添加conversation.user_id列失败，可能已经存在: {}", e.getMessage());
            }

            // 添加user_id索引（如果不存在）
            try {
                jdbcTemplate.execute("ALTER TABLE conversation ADD INDEX idx_user_id (user_id)");
                log.debug("添加conversation.idx_user_id索引成功");
            } catch (Exception e) {
                log.debug("添加conversation.idx_user_id索引失败，可能已经存在: {}", e.getMessage());
            }

            // 添加agent_type列到conversation表（如果不存在）
            try {
                jdbcTemplate.execute("ALTER TABLE conversation ADD COLUMN agent_type VARCHAR(50) DEFAULT 'ai_assistant' COMMENT '会话类型：ai_assistant/chat_assistant/code_assistant' AFTER user_id");
                log.debug("添加conversation.agent_type列成功");
            } catch (Exception e) {
                log.debug("添加conversation.agent_type列失败，可能已经存在: {}", e.getMessage());
            }

            // 迁移旧数据：将agent_type为NULL的记录设为'ai_assistant'
            try {
                int updated = jdbcTemplate.update("UPDATE conversation SET agent_type = 'ai_assistant' WHERE agent_type IS NULL");
                if (updated > 0) {
                    log.info("迁移历史会话数据，共 {} 条记录更新 agent_type = 'ai_assistant'", updated);
                }
            } catch (Exception e) {
                log.debug("迁移历史会话数据失败: {}", e.getMessage());
            }

            // 创建后台任务表（如果不存在）
            try {
                jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS agent_task (" +
                        "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                        "conversation_id BIGINT NOT NULL, " +
                        "status VARCHAR(20) NOT NULL DEFAULT 'running', " +
                        "iteration INT DEFAULT 0, " +
                        "max_iterations INT DEFAULT 50, " +
                        "error_message TEXT, " +
                        "event_count INT DEFAULT 0, " +
                        "created_at DATETIME NOT NULL, " +
                        "updated_at DATETIME NOT NULL, " +
                        "INDEX idx_conversation_id (conversation_id), " +
                        "INDEX idx_status (status)" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                log.debug("创建agent_task表成功");
            } catch (Exception e) {
                log.debug("创建agent_task表失败，可能已经存在: {}", e.getMessage());
            }
            // 添加pending_question列到agent_task表（用于页面刷新后重连展示审批对话框）
            try {
                jdbcTemplate.execute("ALTER TABLE agent_task ADD COLUMN pending_question_uuid VARCHAR(36) AFTER event_count");
                log.debug("添加agent_task.pending_question_uuid列成功");
            } catch (Exception e) {
                log.debug("添加agent_task.pending_question_uuid列失败，可能已经存在: {}", e.getMessage());
            }
            try {
                jdbcTemplate.execute("ALTER TABLE agent_task ADD COLUMN pending_question_text TEXT AFTER pending_question_uuid");
                log.debug("添加agent_task.pending_question_text列成功");
            } catch (Exception e) {
                log.debug("添加agent_task.pending_question_text列失败，可能已经存在: {}", e.getMessage());
            }

            // ===== Agent系统：兼容旧数据库，添加新列 =====
            try {
                jdbcTemplate.execute("ALTER TABLE conversation ADD COLUMN agent_config_id BIGINT AFTER agent_type");
                log.info("添加conversation.agent_config_id列成功");
            } catch (Exception e) {
                log.debug("添加conversation.agent_config_id列失败，可能已存在: {}", e.getMessage());
            }
            try {
                jdbcTemplate.execute("ALTER TABLE conversation ADD COLUMN work_dir VARCHAR(500) AFTER agent_config_id");
                log.info("添加conversation.work_dir列成功");
            } catch (Exception e) {
                log.debug("添加conversation.work_dir列失败，可能已存在: {}", e.getMessage());
            }
            try {
                jdbcTemplate.execute("ALTER TABLE skill ADD COLUMN agent_config_id BIGINT AFTER agent_type");
                log.info("添加skill.agent_config_id列成功");
            } catch (Exception e) {
                log.debug("添加skill.agent_config_id列失败，可能已存在: {}", e.getMessage());
            }
            // 确保 agent_config 表存在
            try {
                jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS agent_config (" +
                        "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                        "name VARCHAR(100) NOT NULL, " +
                        "description VARCHAR(500), " +
                        "avatar VARCHAR(20) DEFAULT '🤖', " +
                        "system_prompt TEXT, " +
                        "tool_names TEXT, " +
                        "model_name VARCHAR(100) DEFAULT 'deepseek-v4-flash', " +
                        "thinking_mode VARCHAR(20) DEFAULT 'non-thinking', " +
                        "execution_mode VARCHAR(10) DEFAULT 'manual', " +
                        "work_dir VARCHAR(500), " +
                        "sort_order INT DEFAULT 0, " +
                        "enabled TINYINT DEFAULT 1, " +
                        "is_default TINYINT DEFAULT 0, " +
                        "is_builtin TINYINT DEFAULT 0, " +
                        "user_id BIGINT, " +
                        "created_at DATETIME NOT NULL, " +
                        "updated_at DATETIME NOT NULL, " +
                        "INDEX idx_user_id (user_id), " +
                        "INDEX idx_enabled (enabled), " +
                        "INDEX idx_sort (sort_order)" +
                        ")");
                log.info("创建agent_config表成功");
            } catch (Exception e) {
                log.debug("创建agent_config表失败，可能已存在: {}", e.getMessage());
            }
            // 初始化默认 Agent
            try {
                jdbcTemplate.update(
                        "INSERT IGNORE INTO agent_config (id, name, description, avatar, system_prompt, tool_names, model_name, thinking_mode, execution_mode, work_dir, sort_order, enabled, is_default, is_builtin, created_at, updated_at) " +
                        "VALUES (1, 'AI 助手', '默认的AI编程助手，拥有全部工具', '🤖', NULL, NULL, 'deepseek-v4-flash', 'non-thinking', 'manual', NULL, 1, 1, 1, 1, NOW(), NOW())");
                log.info("初始化默认Agent成功");
            } catch (Exception e) {
                log.debug("初始化默认Agent失败，可能已存在: {}", e.getMessage());
            }
            // 兼容旧数据：更新菜单名称和Agent名称
            try { jdbcTemplate.update("UPDATE sys_menu SET name = 'AI 助手' WHERE id = 4 AND name = '编码助手'"); } catch (Exception ignored) {}
            try { jdbcTemplate.update("UPDATE agent_config SET name = 'AI 助手' WHERE id = 1 AND is_builtin = 1 AND name = '编码助手'"); } catch (Exception ignored) {}

            log.debug("数据库表初始化完成");
        } catch (Exception e) {
            log.warn("数据库表初始化失败，但应用将继续启动。错误: {}", e.getMessage());
            log.debug("数据库初始化失败详情:", e);
        }
    }

    /**
     * 根据提示词文件名映射会话类型
     */
    private String resolveAgentType(String promptFileName) {
        if (promptFileName == null) return "ai_assistant";
        return switch (promptFileName) {
            case "code_agent_prompt.txt" -> "code_assistant";
            default -> "code_assistant";
        };
    }

    /**
     * 获取或创建会话
     * @param sessionId 可选会话ID，如果为null则创建新会话
     * @param userMessage 用户消息，用于生成会话名称
     * @param userId 用户ID，可以为null
     * @param agentType 会话类型
     * @return 会话对象
     */
    private Conversation getOrCreateConversation(Long sessionId, String userMessage, Long userId, String agentType, Long agentConfigId, String workDir) {
        if (sessionId != null) {
            Optional<Conversation> conversationOpt = conversationMapper.selectById(sessionId);
            if (conversationOpt.isPresent()) {
                Conversation conversation = conversationOpt.get();
                // 更新会话更新时间
                conversation.setUpdatedAt(LocalDateTime.now());
                conversationMapper.update(conversation);
                return conversation;
            }
            // 如果会话不存在，则创建新会话（或抛出异常，这里选择创建新会话）
            log.warn("指定的会话ID {} 不存在，将创建新会话", sessionId);
        }
        // 创建新会话
        String sessionName = userMessage.length() > SESSION_NAME_TRUNCATE_LENGTH
                ? userMessage.substring(0, SESSION_NAME_TRUNCATE_LENGTH)
                : userMessage;
        Conversation conversation = new Conversation(sessionName, userId, agentType, agentConfigId);
        if (workDir != null) {
            conversation.setWorkDir(workDir);
        }
        conversationMapper.insert(conversation);
        log.debug("创建新会话: ID={}, Name={}, UserId={}, AgentType={}, AgentConfigId={}, WorkDir={}",
                conversation.getId(), conversation.getName(), userId, agentType, agentConfigId, workDir);
        return conversation;
    }

    /**
     * 保存用户消息
     * @param conversationId 会话ID
     * @param content 消息内容
     * @param turnId 前端生成的 turnId（用于匹配回滚快照）
     */
    private void saveUserMessage(Long conversationId, String content, String turnId) {
        ConversationMessage msg = new ConversationMessage(conversationId, MessageRole.USER, content, null, null);
        msg.setTurnId(turnId);
        conversationMessageMapper.insert(msg);
    }

    /**
     * 保存助手消息（包含思考过程和内容）
     * @param conversationId 会话ID
     * @param content 消息内容（将作为content字段存储）
     * @param reasoning 思考过程
     */
    private void saveAssistantMessage(Long conversationId, String content, String reasoning) {
        saveConversationMessage(conversationId, MessageRole.ASSISTANT, content, reasoning, null);
    }

    /**
     * 保存工具调用消息
     * @param conversationId 会话ID
     * @param content 工具调用结果内容（将存储到reasoning字段）
     */
    private void saveToolMessage(Long conversationId, String content) {
        saveConversationMessage(conversationId, MessageRole.TOOL, null, content, null);
    }

    /**
     * 保存助手思考过程消息（只保存reasoning，content为null）
     * @param conversationId 会话ID
     * @param reasoning 思考过程
     * @param toolCalls 工具调用数据块（JSON格式），可选
     */
    private void saveAssistantReasoning(Long conversationId, String reasoning, String toolCalls) {
        saveConversationMessage(conversationId, MessageRole.ASSISTANT, null, reasoning, toolCalls);
    }


    /**
     * 保存会话消息到数据库
     *
     * @param conversationId 会话ID
     * @param role 消息角色
     * @param content 消息内容（存储到content字段），可以为null
     * @param reasoning 思考过程（存储到reasoning字段），可以为null
     * @param toolCalls 工具调用数据块（JSON格式，存储到tool_calls字段），可以为null
     */
    private void saveConversationMessage(Long conversationId, MessageRole role, String content, String reasoning, String toolCalls) {
        ConversationMessage message = new ConversationMessage(conversationId, role, content, reasoning, toolCalls);
        conversationMessageMapper.insert(message);
        log.debug("保存{}消息: conversationId={}, contentLength={}, reasoningLength={}, toolCallsLength={}",
                role, conversationId,
                content != null ? content.length() : 0,
                reasoning != null ? reasoning.length() : 0,
                toolCalls != null ? toolCalls.length() : 0);
    }

    /**
     * 根据会话ID构建历史消息列表，用于API请求
     * 将数据库中的消息格式转换为DeepSeek API所需的消息格式
     * 集成三级阈值压缩策略：应用已有压缩记录 → 智能压缩 → 滑动窗口丢弃
     * @param conversationId 会话ID
     * @return 消息列表，每个元素包含role、content和可选的tool_calls
     */
    private List<Map<String, Object>> buildMessagesFromHistory(Long conversationId) {
        // 1. 加载原始消息
        List<ConversationMessage> dbMessages = conversationMessageMapper.selectByConversationId(conversationId);

        // 2. 应用已有的压缩记录（将已被压缩的消息替换为摘要）
        compactionService.applyCompactionRecords(conversationId, dbMessages);

        // 3. 将消息转换为 API 格式
        List<Map<String, Object>> result = new ArrayList<>();
        // 记录每个 assistant 消息在 result 中的索引 → 它期望的 tool_call ID 列表
        java.util.Map<Integer, List<String>> assistantToolCallIdsMap = new java.util.HashMap<>();
        // 记录所有已被 TOOL 消息消耗的 tool_call ID
        java.util.Set<String> consumedToolCallIds = new java.util.HashSet<>();
        // 辅助队列：按顺序排队等待 TOOL 消息匹配的 tool_call ID
        java.util.Queue<String> pendingToolCallIdQueue = new java.util.LinkedList<>();

        for (ConversationMessage msg : dbMessages) {
            Map<String, Object> messageMap = new HashMap<>();
            MessageRole role = msg.getRole();

            // 根据角色处理消息
            if (role == MessageRole.USER || role == MessageRole.SYSTEM) {
                messageMap.put("role", role.getValue());
                String content = msg.getContent() != null ? msg.getContent() : "";
                messageMap.put("content", content);
                result.add(messageMap);
            } else if (role == MessageRole.ASSISTANT) {
                messageMap.put("role", "assistant");

                // 遇到新的 assistant 消息时，如果之前还有未消耗的 tool_call ID，
                // 说明上一个 assistant 的 tool_calls 缺少对应的 TOOL 消息
                // 记录这些未消耗的 ID 以便后续精确修复
                if (!pendingToolCallIdQueue.isEmpty()) {
                    log.warn("发现未消耗的tool call IDs，可能数据不一致: {}", pendingToolCallIdQueue);
                    pendingToolCallIdQueue.clear();
                }

                if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                    try {
                        JsonNode toolCallsNode = objectMapper.readTree(msg.getToolCalls());
                        messageMap.put("tool_calls", toolCallsNode);

                        if (toolCallsNode.isArray()) {
                            List<String> idList = new ArrayList<>();
                            for (JsonNode toolCall : toolCallsNode) {
                                JsonNode idNode = toolCall.path("id");
                                if (!idNode.isMissingNode() && !idNode.isNull()) {
                                    String id = idNode.asText();
                                    pendingToolCallIdQueue.add(id);
                                    idList.add(id);
                                }
                            }
                            // 记录当前 assistant 消息期望的 tool_call ID 列表
                            assistantToolCallIdsMap.put(result.size(), idList);
                        }
                    } catch (Exception e) {
                        log.warn("解析tool_calls JSON失败: {}", e.getMessage());
                    }
                }

                String content = msg.getContent();
                if (content != null && !content.isEmpty()) {
                    messageMap.put("content", content);
                }

                String reasoning = msg.getReasoning();
                if (reasoning != null && !reasoning.isEmpty()) {
                    messageMap.put("reasoning_content", reasoning);
                }

                result.add(messageMap);
            } else if (role == MessageRole.TOOL) {
                if (!pendingToolCallIdQueue.isEmpty()) {
                    String toolCallId = pendingToolCallIdQueue.poll();
                    consumedToolCallIds.add(toolCallId);
                    messageMap.put("role", "tool");
                    messageMap.put("tool_call_id", toolCallId);

                    String content = msg.getReasoning() != null ? msg.getReasoning() :
                            (msg.getContent() != null ? msg.getContent() : "");
                    messageMap.put("content", content);

                    result.add(messageMap);
                } else {
                    log.warn("TOOL消息没有对应的tool call ID，跳过该消息: {}", msg.getId());
                }
            }
        }

        // 精确修复：只移除缺少对应 TOOL 消息的 assistant 消息的 tool_calls
        // 判断标准：assistant 消息的 tool_call ID 列表中，存在未被 TOOL 消息消耗的 ID
        boolean hasOrphanToolCalls = false;
        for (java.util.Map.Entry<Integer, List<String>> entry : assistantToolCallIdsMap.entrySet()) {
            int msgIndex = entry.getKey();
            List<String> expectedIds = entry.getValue();
            boolean allConsumed = true;
            for (String id : expectedIds) {
                if (!consumedToolCallIds.contains(id)) {
                    allConsumed = false;
                    break;
                }
            }
            if (!allConsumed) {
                Map<String, Object> msg = result.get(msgIndex);
                if (msg.containsKey("tool_calls")) {
                    msg.remove("tool_calls");
                    log.info("精确修复：从assistant消息[{}]中移除了孤立的tool_calls（缺少对应的TOOL消息）", msgIndex);
                    hasOrphanToolCalls = true;
                }
            }
        }
        if (hasOrphanToolCalls) {
            log.warn("上下文孤立修复完成：移除了 {} 条缺少TOOL消息的assistant消息的tool_calls", 
                     assistantToolCallIdsMap.size() - 
                     (int)assistantToolCallIdsMap.values().stream()
                         .filter(ids -> ids.stream().allMatch(consumedToolCallIds::contains))
                         .count());
        }

        // 4. 三级阈值上下文管理：先尝试压缩，兜底丢弃
        int maxTokens = deepSeekConfig.getMaxContextTokens();
        int estimatedTokens = TokenEstimator.estimateMessages(result);

        // 4a. 如果超过压缩阈值，执行智能压缩
        if (estimatedTokens > maxTokens * deepSeekConfig.getCompaction().getCompactThreshold()) {
            log.info("历史上下文超限（压缩阈值），尝试智能压缩：估算 {} tokens, 上限 {}",
                    estimatedTokens, maxTokens);
            int afterCompactTokens = compactionService.compact(conversationId, result);
            if (afterCompactTokens > 0) {
                estimatedTokens = afterCompactTokens;
                log.info("智能压缩完成，压缩后 {} tokens", estimatedTokens);
            }
        }

        // 4b. 如果压缩后仍超过丢弃阈值，执行滑动窗口丢弃
        if (estimatedTokens > maxTokens * deepSeekConfig.getCompaction().getDropThreshold()) {
            log.warn("智能压缩后仍超限，执行滑动窗口丢弃：估算 {} tokens, 上限 {}",
                    estimatedTokens, maxTokens);
            trimToTokenBudget(result, maxTokens);
        }

        return result;
    }


    /**
     * Token 感知的上下文滑动窗口裁剪（兜底策略）
     * 从最早的历史开始，按完整轮次（user + assistant + tool）裁剪，保留保护带
     * @param messages 消息列表（会直接修改）
     * @param maxTokens token 上限
     */
    private void trimToTokenBudget(List<Map<String, Object>> messages, int maxTokens) {
        if (messages == null || messages.isEmpty()) return;

        int estimatedTokens = TokenEstimator.estimateMessages(messages);
        if (estimatedTokens <= maxTokens) return;

        int beforeSize = messages.size();
        log.warn("上下文滑动窗口丢弃：估算 {} tokens (上限 {}), 开始裁剪（共 {} 条消息）",
                estimatedTokens, maxTokens, beforeSize);

        // 保护带：保留最后 protectRounds 轮 + 当前用户消息
        int protectRounds = deepSeekConfig.getCompaction().getProtectRounds();
        int protectStart = findProtectStart(messages, protectRounds);

        // 从索引 1 开始裁剪（保留 system 消息），到保护带起始位置结束
        int idx = 1;
        int lastIdx = Math.min(protectStart, messages.size() - 1);
        while (idx < lastIdx) {
            // 找到当前轮次的结束：[idx, endIdx)
            int endIdx = idx + 1;
            while (endIdx < lastIdx) {
                String role = (String) messages.get(endIdx).get("role");
                if ("user".equals(role) || "system".equals(role)) break;
                endIdx++;
            }

            // 移除这一轮（从后往前删避免索引错乱）
            for (int i = endIdx - 1; i >= idx; i--) {
                messages.remove(i);
            }
            lastIdx = Math.min(protectStart, messages.size() - 1);

            estimatedTokens = TokenEstimator.estimateMessages(messages);
            if (estimatedTokens <= maxTokens) break;
        }

        int trimmed = beforeSize - messages.size();
        log.info("上下文丢弃完成：移除了 {} 条消息，剩余 {} 条，估算 {} tokens",
                trimmed, messages.size(), estimatedTokens);
    }

    /**
     * 查找保护带的起始位置
     * 从后往前找，找到第 protectRounds 个 user 消息的索引
     */
    private int findProtectStart(List<Map<String, Object>> messages, int protectRounds) {
        if (messages == null || messages.isEmpty()) return 0;
        int roundsFound = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            String role = (String) messages.get(i).get("role");
            if ("user".equals(role)) {
                roundsFound++;
                if (roundsFound > protectRounds) {
                    return i + 1;
                }
            }
        }
        return 1; // 保护带覆盖全部，从索引 1 开始
    }


    /**
     * 从delta节点中提取content和reasoning_content字段
     * @param deltaNode JSON delta节点
     * @return 包含content和reasoning的Map
     */
    private Map<String, String> extractFromDeltaNode(JsonNode deltaNode) {
        Map<String, String> result = new HashMap<>();
        result.put("content", "");
        result.put("reasoning", "");

        if (deltaNode.isMissingNode()) {
            return result;
        }

        // 提取content
        JsonNode contentNode = deltaNode.path("content");
        if (!contentNode.isMissingNode() && !contentNode.isNull()) {
            String contentValue = contentNode.asText("");
            if (!contentValue.isEmpty()) {
                result.put("content", contentValue);
            }
        }

        // 提取reasoning_content
        JsonNode reasoningNode = deltaNode.path("reasoning_content");
        if (!reasoningNode.isMissingNode() && !reasoningNode.isNull()) {
            String reasoningValue = reasoningNode.asText("");
            if (!reasoningValue.isEmpty()) {
                result.put("reasoning", reasoningValue);
            }
        }

        return result;
    }


    /**
     * 从流式响应中提取content和reasoning_content内容
     * @param streamResponse 流式响应字符串（SSE格式）
     * @return 包含content和reasoning的Map，如果解析失败则返回空Map
     */
    private Map<String, String> extractContentAndReasoningFromStreamResponse(String streamResponse) {
        Map<String, String> result = new HashMap<>();
        StringBuilder contentBuilder = new StringBuilder();
        StringBuilder reasoningBuilder = new StringBuilder();

        try {
            // 使用Jackson的JsonParser流式解析多个JSON对象
            JsonParser parser = objectMapper.getFactory().createParser(streamResponse);

            // 尝试解析多个顶级JSON对象
            while (true) {
                try {
                    if (parser.nextToken() == null) {
                        break; // 没有更多tokens
                    }

                    // 读取整个JSON对象
                    JsonNode root = objectMapper.readTree(parser);

                    // 检查是否有choices数组
                    JsonNode choices = root.path("choices");
                    if (choices.isArray() && choices.size() > 0) {
                        JsonNode delta = choices.get(0).path("delta");
                        Map<String, String> extracted = extractFromDeltaNode(delta);
                        String content = extracted.get("content");
                        String reasoning = extracted.get("reasoning");

                        if (!content.isEmpty()) {
                            contentBuilder.append(content);
                        }
                        if (!reasoning.isEmpty()) {
                            reasoningBuilder.append(reasoning);
                        }
                    }
                } catch (Exception e) {
                    // 跳过无法解析的对象（如[DONE]标记或其他非JSON内容）
                    log.debug("解析JSON对象失败，继续下一个对象: {}", e.getMessage());
                    // 尝试跳过无效内容并继续
                    try {
                        // 跳过当前token
                        parser.skipChildren();
                    } catch (Exception skipEx) {
                        // 如果无法跳过，尝试继续
                    }
                }
            }

            parser.close();

            // 将累积的结果放入Map
            result.put("content", contentBuilder.toString());
            result.put("reasoning", reasoningBuilder.toString());

        } catch (Exception e) {
            log.error("解析流式响应失败", e);
            // 返回空Map
            result.put("content", "");
            result.put("reasoning", "");
        }

        return result;
    }

    /**
     * 清洗SSE协议框架垃圾（data: 前缀、[DONE]标记等），提取纯文本内容
     * 当正常解析content失败时作为最后的回退使用
     */
    private String cleanSseContent(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        // 去除 data: 前缀、[DONE] 标记、换行符
        String cleaned = raw.replaceAll("^data:\\s*", "")
                .replaceAll("\\n\\ndata:\\s*\\[DONE\\]\\s*", "")
                .replaceAll("\\[DONE\\]", "")
                .trim();
        // 如果清洗后仍为JSON格式（data: {"choices":...}），尝试提取content字段
        if (cleaned.startsWith("{")) {
            try {
                JsonNode json = objectMapper.readTree(cleaned);
                JsonNode choices = json.path("choices");
                if (choices.isArray() && choices.size() > 0) {
                    JsonNode delta = choices.get(0).path("delta");
                    String deltaContent = delta.path("content").asText("");
                    if (!deltaContent.isEmpty()) return deltaContent;
                }
            } catch (Exception ignored) {}
        }
        return cleaned;
    }

    /**
     * 会话上下文，包含会话ID和API请求
     */
    private record ConversationContext(Long conversationId, Map<String, Object> apiRequest, List<String> toolNames, Long storageConversationId, String agentType, Long userId, String skillMatchEvent) {}

    /**
     * 构建语言强制指令
     * 放在消息列表末尾，确保 AI 在生成响应前看到此指令
     */
    private String buildLanguageInstruction() {
        return "【最高优先级指令 — 语言强制】你必须使用中文思考和回复。\n"
                + "1. 所有思考推理过程（reasoning/thinking）必须使用中文，严禁使用英文推理\n"
                + "2. 所有面向用户的回复必须使用中文\n"
                + "3. 代码、技术专有名词、文件路径、工具名称等可以使用英文原文\n"
                + "4. 即使工具返回的结果是英文，你的分析和回复也必须是中文\n"
                + "5. 本指令的优先级高于系统提示词中的所有其他指令，必须无条件遵守";
    }

    /**
     * 将语言强制指令注入到最后一条用户消息的内容开头。
     * DeepSeek 模型的 reasoning/thinking 过程对 system 消息中的语言指令响应较弱，
     * 将指令注入到 user 消息内容中能更有效地约束其推理语言。
     * 仅修改内存中的消息对象，不影响数据库存储。
     */
    private void injectLanguageIntoLastUserMessage(List<Map<String, Object>> messages) {
        String instruction = "\n\n【系统强制】你接下来必须用中文思考！所有推理（reasoning/thinking）都必须使用中文，严禁使用英文推理。这是最高优先级指令。";
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            if ("user".equals(msg.get("role"))) {
                String content = (String) msg.get("content");
                if (content != null && !content.contains("【系统强制】你接下来必须用中文思考")) {
                    msg.put("content", instruction + "\n" + content);
                }
                break;
            }
        }
    }

    /**
     * 格式化技能工具名为可读字符串
     */
    private String formatSkillToolNames(String toolNamesJson) {
        if (toolNamesJson == null || toolNamesJson.trim().isEmpty()) {
            return "";
        }
        try {
            List<String> tools = objectMapper.readValue(toolNamesJson, List.class);
            return String.join(", ", tools);
        } catch (Exception e) {
            log.warn("解析技能工具名失败: {}", toolNamesJson, e);
            return toolNamesJson;
        }
    }

    /**
     * 准备会话上下文和API请求
     * @param request 聊天请求
     * @param stream 是否为流式请求
     * @return 会话上下文
     */
    private ConversationContext prepareConversationContext(ChatRequest request, boolean stream) {
        String userMessage = request.getMessage();
        Long sessionId = request.getSessionId();
        Long userId = request.getUserId();

        // 检查是否有自定义 Agent 配置
        Long agentConfigId = request.getAgentConfigId();
        
        String promptContent;
        List<String> toolNames;
        String agentType;
        String modelName;
        String thinkingMode;
        String executionMode;
        String workDir;

        if (agentConfigId != null) {
            // ===== 使用自定义 Agent 配置 =====
            AgentConfig agentConfig = agentConfigMapper.selectById(agentConfigId).orElse(null);
            if (agentConfig == null) {
                log.warn("Agent 配置不存在: agentConfigId={}, 回退到默认配置", agentConfigId);
                promptContent = PromptUtil.getPrompt("code_agent_prompt.txt");
                toolNames = deepSeekConfig.getToolGroups()
                        .getOrDefault("code_agent_prompt.txt", Collections.emptyList());
                agentType = "code_assistant";
                modelName = deepSeekConfig.getDefaultModel();
                thinkingMode = deepSeekConfig.getThinkingMode();
                executionMode = request.getExecutionMode() != null ? request.getExecutionMode() : "auto";
                workDir = ProjectRootContext.get();
                agentConfigId = null;
            } else {
                // 1. 系统提示词
                if (agentConfig.getSystemPrompt() != null && !agentConfig.getSystemPrompt().trim().isEmpty()) {
                    promptContent = agentConfig.getSystemPrompt();
                } else {
                    promptContent = PromptUtil.getPrompt("code_agent_prompt.txt");
                }
                
                // 2. 工具列表
                if (agentConfig.getToolNames() != null && !agentConfig.getToolNames().trim().isEmpty()) {
                    try {
                        toolNames = objectMapper.readValue(agentConfig.getToolNames(), List.class);
                    } catch (Exception e) {
                        log.warn("解析 Agent 工具列表失败, 使用全部工具: {}", e.getMessage());
                        toolNames = deepSeekConfig.getToolGroups()
                                .getOrDefault("code_agent_prompt.txt", Collections.emptyList());
                    }
                } else {
                    toolNames = deepSeekConfig.getToolGroups()
                            .getOrDefault("code_agent_prompt.txt", Collections.emptyList());
                }
                
                // 3. Agent 类型
                agentType = "agent_" + agentConfig.getId();
                
                // 4. 模型配置
                modelName = agentConfig.getModelName() != null ? agentConfig.getModelName() : deepSeekConfig.getDefaultModel();
                thinkingMode = agentConfig.getThinkingMode() != null ? agentConfig.getThinkingMode() : deepSeekConfig.getThinkingMode();
                
                // 5. 执行模式
                executionMode = agentConfig.getExecutionMode() != null ? agentConfig.getExecutionMode() : "auto";
                
                // 6. 工作目录
                workDir = agentConfig.getWorkDir();
                if (workDir != null && !workDir.trim().isEmpty()) {
                    ProjectRootContext.set(workDir);
                } else {
                    workDir = ProjectRootContext.get();
                }
            }
        } else {
            // ===== 默认 Agent =====
            String promptFileName = request.getPromptFileName();
            if (promptFileName == null || promptFileName.trim().isEmpty()) {
                promptFileName = "code_agent_prompt.txt";
            }
            promptContent = PromptUtil.getPrompt(promptFileName);
            toolNames = deepSeekConfig.getToolGroups()
                    .getOrDefault(promptFileName, Collections.emptyList());
            agentType = resolveAgentType(promptFileName);
            modelName = deepSeekConfig.getDefaultModel();
            thinkingMode = deepSeekConfig.getThinkingMode();
            executionMode = "auto";
            workDir = ProjectRootContext.get();
        }

        // ===== 运行时配置（前端传入）优先于 Agent 配置 =====
        if (request.getModel() != null && !request.getModel().isEmpty()) {
            modelName = request.getModel();
        }
        if (request.getThinkingMode() != null && !request.getThinkingMode().isEmpty()) {
            thinkingMode = request.getThinkingMode();
        }
        if (request.getExecutionMode() != null && !request.getExecutionMode().isEmpty()) {
            executionMode = request.getExecutionMode();
        }
        if (request.getProjectRoot() != null && !request.getProjectRoot().isEmpty()) {
            workDir = request.getProjectRoot();
            ProjectRootContext.set(workDir);
        }
        request.setModel(modelName);
        request.setThinkingMode(thinkingMode);
        request.setExecutionMode(executionMode);

        Long conversationId;
        Long storageConversationId;
        Conversation conversation = getOrCreateConversation(sessionId, userMessage, userId, agentType, agentConfigId, workDir);
        conversationId = conversation.getId();
        storageConversationId = conversationId;

        // 保存用户消息
        saveUserMessage(storageConversationId, userMessage, request.getTurnId());

        // 构建历史消息（包括本次用户消息）
        List<Map<String, Object>> historyMessages = buildMessagesFromHistory(conversationId);

        // 检查是否为首次会话（是否已存在SYSTEM消息）
        boolean hasSystemMessage = historyMessages.stream()
                .anyMatch(msg -> "system".equals(msg.get("role")));

        // 如果是首次会话且没有SYSTEM消息，添加系统提示词
        if (!hasSystemMessage) {
            String systemPrompt = promptContent;

            // 在首次会话时注入角色性格配置（追加到系统提示词末尾）
            String characterProfile = configService.getValue("character_profile");
            if (characterProfile != null && !characterProfile.isEmpty() && !"{}".equals(characterProfile.trim())) {
                String charSection = buildCharacterSection(characterProfile);
                if (charSection != null) {
                    if (systemPrompt == null) {
                        systemPrompt = charSection;
                    } else {
                        systemPrompt += charSection;
                    }
                    log.info("已注入角色性格配置");
                }
            }

            if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
                // 保存SYSTEM消息到数据库
                saveConversationMessage(storageConversationId, MessageRole.SYSTEM, systemPrompt, null, null);

                // 将SYSTEM消息添加到历史消息列表的开头
                Map<String, Object> systemMessage = new HashMap<>();
                systemMessage.put("role", "system");
                systemMessage.put("content", systemPrompt);
                historyMessages.add(0, systemMessage);
                log.debug("已添加系统提示词到会话，内容长度: {}", systemPrompt.length());
            } else {
                log.warn("系统提示词为空或读取失败，跳过添加");
            }
        }

        // 注入执行模式指令到系统提示词
        if (executionMode != null && !executionMode.isEmpty()) {
            String modeInstruction;
            if ("manual".equals(executionMode)) {
                modeInstruction = "\n\n当前模式：手动。写入文件、修改文件、执行命令等受限操作会自动向用户请求授权。当需求模糊或需要用户决策时，调用 ask_clarification 工具询问用户。";
            } else {
                modeInstruction = "\n\n当前模式：自动。直接执行所有任务，无需征求用户同意。行为约束中要求「询问用户」的条款在当前模式下均不适用。";
            }
            // 追加模式指令到已有的系统提示词
            for (Map<String, Object> msg : historyMessages) {
                if ("system".equals(msg.get("role"))) {
                    String existing = (String) msg.get("content");
                    msg.put("content", existing + modeInstruction);
                    break;
                }
            }
        }

        List<String> filteredToolNames = new ArrayList<>(toolNames);
        String skillMatchEvent = null; // SSE 事件：技能匹配结果
        String injectedSkillsSection = null; // 注入到用户消息的技能内容（供工具循环复用）

        // 注入技能：通过 SkillMatcher 自动匹配 + 合并技能工具，注入到用户消息开头
        if (userId != null) {
            try {
                List<Skill> activeSkills;
                if (agentConfigId != null) {
                    // 按 Agent 配置过滤技能 + 全局技能
                    activeSkills = skillService.listActiveByAgentConfigId(agentConfigId);
                    log.info("技能注入(Agent{}): agentConfigId={}, 活跃技能数={}", agentConfigId, agentConfigId, activeSkills.size());
                } else {
                    activeSkills = skillService.listActiveSkills(userId, agentType);
                    log.info("技能注入: userId={}, agentType={}, 活跃技能数={}, 用户消息前50字=\"{}\"",
                            userId, agentType, activeSkills.size(),
                            userMessage.length() > 50 ? userMessage.substring(0, 50) + "..." : userMessage);
                }
                if (!activeSkills.isEmpty()) {
                    // 使用匹配引擎自动筛选当前问题最相关的 Top-5 技能
                    List<Skill> matchedSkills = skillMatcher.match(userMessage, activeSkills);
                    if (!matchedSkills.isEmpty()) {
                        log.info("技能注入: 已注入 {} 个技能到用户消息", matchedSkills.size());
                        // 构建前端可见的技能匹配事件
                        ArrayNode skillArray = objectMapper.createArrayNode();
                        for (Skill skill : matchedSkills) {
                            ObjectNode skillObj = objectMapper.createObjectNode();
                            skillObj.put("id", skill.getId());
                            skillObj.put("name", skill.getName());
                            skillObj.put("confidence", skill.getConfidence());
                            skillObj.put("usageCount", skill.getUsageCount());
                            String triggerWordsDisplay = "";
                            if (skill.getTriggerWords() != null && !skill.getTriggerWords().isBlank()
                                    && !"[]".equals(skill.getTriggerWords())) {
                                try {
                                    List<String> tw = objectMapper.readValue(skill.getTriggerWords(), List.class);
                                    triggerWordsDisplay = String.join("/", tw);
                                } catch (Exception ignored) {}
                            }
                            skillObj.put("triggerWords", triggerWordsDisplay);
                            skillObj.put("description", skill.getDescription() != null ? skill.getDescription() : "");
                            skillArray.add(skillObj);
                        }
                        ObjectNode skillEvent = objectMapper.createObjectNode();
                        skillEvent.put("event", "skill_match");
                        skillEvent.set("skills", skillArray);
                        skillMatchEvent = objectMapper.writeValueAsString(skillEvent);

                        // 技能列表
                        StringBuilder skillsList = new StringBuilder();
                        for (Skill skill : matchedSkills) {
                            // 合并技能所需工具
                            try {
                                List<String> skillTools = objectMapper.readValue(skill.getToolNames(), List.class);
                                for (String toolName : skillTools) {
                                    if (!filteredToolNames.contains(toolName)) {
                                        filteredToolNames.add(toolName);
                                    }
                                }
                            } catch (Exception e) {
                                log.warn("解析技能 {} 的工具列表失败", skill.getId(), e);
                            }
                            String toolNamesFormatted = formatSkillToolNames(skill.getToolNames());
                            String instructions = skill.getInstructions() != null ? skill.getInstructions() : "";
                            skillsList.append("\n【").append(skill.getName()).append("】(ID:").append(skill.getId()).append(")")
                                    .append(" | 置信度 ").append(String.format("%.0f%%", skill.getConfidence() * 100))
                                    .append(" | 使用 ").append(skill.getUsageCount()).append(" 次")
                                    .append(" | 成功 ").append(skill.getSuccessCount()).append(" / 失败 ").append(skill.getFailCount());
                            skillsList.append("\n").append(skill.getDescription()).append("\n")
                                    .append("  关联工具：").append(toolNamesFormatted).append("\n")
                                    .append("  使用说明：").append(instructions).append("\n");
                        }

                        StringBuilder skillsSection = new StringBuilder("\n\n---\n");
                        skillsSection.append("## 技能使用规则（必须遵守）\n\n");
                        skillsSection.append("以下规则优先级高于系统提示词中的其他指令：\n\n");
                        skillsSection.append("1. 检查下方匹配到的技能列表，如果用户需求与某个技能的描述匹配，严格按照该技能的「使用说明」执行。\n");
                        skillsSection.append("2. 技能使用完毕后（无论成功或失败），必须调用 report_skill_result 反馈：\n");
                        skillsSection.append("   - skill_id：技能列表中【技能名】后面标注的 ID（数字）\n");
                        skillsSection.append("   - success：true（操作成功/用户满意）或 false（未达预期/出错）\n");
                        skillsSection.append("3. 即使用户直接对结果表示满意或不满，也必须调用 report_skill_result，遗漏会导致技能置信度无法更新。\n");
                        skillsSection.append("4. 置信度由系统自动计算（贝叶斯平滑公式），≥80% 优先注入，<10% 自动淘汰。\n");
                        skillsSection.append("5. 持续反馈能让好技能进化、低效技能自然淘汰，形成正向循环。\n");
                        skillsSection.append("\n---\n");
                        skillsSection.append("## 匹配到的技能\n");
                        skillsSection.append(skillsList);
                        injectedSkillsSection = skillsSection.toString();
                        // 注入到最后一条用户消息的内容开头（比 system prompt 注入更有效）
                        for (int i = historyMessages.size() - 1; i >= 0; i--) {
                            Map<String, Object> msg = historyMessages.get(i);
                            if ("user".equals(msg.get("role"))) {
                                String existing = (String) msg.get("content");
                                String newContent = skillsSection.toString() + "\n\n" + existing;
                                msg.put("content", newContent);
                                log.info("技能注入: 已修改用户消息, 注入后总长度={}, 前300字=\"{}\"",
                                        newContent.length(),
                                        newContent.length() > 300 ? newContent.substring(0, 300) + "..." : newContent);
                                break;
                            }
                        }
                    } else {
                        log.info("技能注入: 匹配结果为空，未注入技能");
                    }
                } else {
                    log.info("技能注入: 该用户/助手组合无活跃技能");
                }
            } catch (Exception e) {
                log.warn("加载技能失败", e);
            }
        }

        // 注入语言强制指令到消息列表末尾（靠近响应位置，优先级最高）
        Map<String, Object> langInstruction = new HashMap<>();
        langInstruction.put("role", "system");
        langInstruction.put("content", buildLanguageInstruction());
        historyMessages.add(langInstruction);

        // 同时将语言指令注入到最后一条用户消息内容中
        // （DeepSeek 的 reasoning 对 user 消息中的指令响应更强）
        injectLanguageIntoLastUserMessage(historyMessages);

        // 构建API请求体
        Map<String, Object> apiRequest = new HashMap<>();
        String model = request.getModel();
        apiRequest.put("model", (model != null && !model.isEmpty()) ? model : deepSeekConfig.getDefaultModel());
        apiRequest.put("messages", historyMessages);
        apiRequest.put("stream", stream);
        // 添加温度参数以减少重复
        apiRequest.put(TEMPERATURE_FIELD, DEFAULT_TEMPERATURE);
        // 添加思考模式参数（DeepSeek API 格式）
        // non-thinking -> thinking.type=disabled
        // thinking    -> thinking.type=enabled + reasoning_effort=high
        // thinking_max -> thinking.type=enabled + reasoning_effort=max
        String thinkingModeForApi = request.getThinkingMode();
        if (thinkingModeForApi == null || thinkingModeForApi.isEmpty()) {
            thinkingModeForApi = deepSeekConfig.getThinkingMode();
        }
        if ("non-thinking".equals(thinkingModeForApi)) {
            Map<String, Object> thinking = new HashMap<>();
            thinking.put("type", "disabled");
            apiRequest.put("thinking", thinking);
        } else {
            Map<String, Object> thinking = new HashMap<>();
            thinking.put("type", "enabled");
            apiRequest.put("thinking", thinking);
            apiRequest.put("reasoning_effort", "thinking_max".equals(thinkingModeForApi) ? "max" : "high");
        }

        // 保存项目根目录到 API 请求中（不在发送给 API 的字段中，仅内部使用）
        String projectRoot = request.getProjectRoot();
        if (projectRoot != null && !projectRoot.isEmpty()) {
            apiRequest.put("_projectRoot", projectRoot);
        }

        // 保存执行模式到 API 请求中（内部使用，供工具执行时检查权限）
        if (executionMode != null && !executionMode.isEmpty()) {
            apiRequest.put("_executionMode", executionMode);
        }
        // 保存用户 ID 和助手类型到 API 请求中（内部使用，供技能工具获取上下文）
        if (userId != null) {
            apiRequest.put("_userId", userId);
        }
        if (agentType != null) {
            apiRequest.put("_agentType", agentType);
        }
        if (agentConfigId != null) {
            apiRequest.put("_agentConfigId", agentConfigId);
        }
        // 保存 turnId 到 API 请求中（内部使用，供快照创建时关联消息）
        String turnId = request.getTurnId();
        if (turnId != null && !turnId.isEmpty()) {
            apiRequest.put("_turnId", turnId);
        }

        // 添加工具定义（如果存在），根据提示词中的工具组过滤
        JsonNode toolDefinitions = toolExecutor.buildToolDefinitions(filteredToolNames);
        if (toolDefinitions != null && toolDefinitions.size() > 0) {
            // 将JsonNode转换为List<Map>以便放入请求体
            List<Map<String, Object>> toolsList = objectMapper.convertValue(
                toolDefinitions,
                objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class)
            );
            apiRequest.put("tools", toolsList);
            apiRequest.put("tool_choice", "auto");
            log.debug("添加工具定义到API请求，工具数量: {}", toolDefinitions.size());
        }

        // 将技能注入内容存入隐藏字段，供工具循环路径复用
        if (injectedSkillsSection != null) {
            apiRequest.put("_skillsSection", injectedSkillsSection);
        }

        log.debug("调用DeepSeek API{}接口，会话ID: {}, 消息长度: {}, 用户ID: {}",
                stream ? "流式" : "非流式", conversationId, userMessage.length(), userId);

        return new ConversationContext(conversationId, apiRequest, toolNames, storageConversationId, agentType, userId, skillMatchEvent);
    }

    @Override
    /**
     * 流式聊天接口
     * @param request 聊天请求
     * @return 流式响应Flux
     */
    public Flux<String> streamChat(ChatRequest request) {
        ConversationContext context = prepareConversationContext(request, true);
        Long conversationId = context.conversationId();
        Long storageConversationId = context.storageConversationId();
        Map<String, Object> apiRequest = context.apiRequest();

        // 检查是否有工具定义，使用提示词中指定的工具组
        JsonNode toolDefinitions = toolExecutor.buildToolDefinitions(context.toolNames());
        boolean hasTools = toolDefinitions != null && toolDefinitions.size() > 0;

        // send sessionId event so frontend gets the real conversation ID
        String sessionEventStr = "{\"sessionId\":" + conversationId + "}";
        Flux<String> sessionEvent = Flux.just(sessionEventStr);

        // emit skill match event so frontend can show matched skills in thinking panel
        Flux<String> skillEventFlux = Flux.empty();
        if (context.skillMatchEvent() != null) {
            skillEventFlux = Flux.just(context.skillMatchEvent());
        }

        if (hasTools) {
            // 取消同一会话中正在运行的后台任务
            cancelRunningTask(conversationId);
            // 在后台启动工具循环（断开不销毁），返回事件流
            return Flux.concat(skillEventFlux, sessionEvent, startBackgroundTask(conversationId, storageConversationId, apiRequest));
        } else {
            // 没有工具定义，使用原有流式逻辑
            // 收集响应内容，用于保存助手消息
            StringBuilder responseBuilder = new StringBuilder();

            // 创建主Flux处理API响应（移除内部字段后发送）
            Map<String, Object> requestBody = new HashMap<>(apiRequest);
            requestBody.keySet().removeIf(k -> k.startsWith("_"));
            return Flux.concat(skillEventFlux, sessionEvent, webClient.post()
                    .uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .accept(MediaType.TEXT_EVENT_STREAM) // 接受text/event-stream
                    .retrieve()
                    .bodyToFlux(String.class)
                    .doOnNext(chunk -> {
                        // 收集响应块
                        responseBuilder.append(chunk);
                    })
                    .filter(chunk -> {
                        // 过滤掉空行和[DONE]事件
                        String trimmed = chunk.trim();
                        return !trimmed.isEmpty();
                    })
                    .map(chunk -> {
                        // 直接返回原始JSON数据，去掉"data: "前缀
                        String data = chunk.trim();
                        if (data.startsWith("data: ")) {
                            data = data.substring(6).trim();
                        }
                        return data;
                    })
                    .doOnError(error -> {
                        log.error("DeepSeek API流式调用失败", error);
                    })
                    .doOnCancel(() -> {
                        log.debug("流式调用被取消");
                    })
                    .doOnComplete(() -> {
                        // 流式响应完成，保存助手消息
                        String fullResponse = responseBuilder.toString();
                        Map<String, String> extracted = extractContentAndReasoningFromStreamResponse(fullResponse);
                        String content = extracted.get("content");
                        String reasoning = extracted.get("reasoning");
                        if (content == null || content.isEmpty()) {
                            content = cleanSseContent(fullResponse);
                            log.warn("未能从流式响应解析出content, 使用清洗后的fullResponse: {}", content);
                        }
                        saveAssistantMessage(storageConversationId, content, reasoning);
                        log.debug("流式调用完成，保存助手消息，content长度: {}, reasoning长度: {}",
                                content != null ? content.length() : 0, reasoning != null ? reasoning.length() : 0);
                        // 异步预压缩：提前压缩最早的历史，降低下次请求延迟
                        compactionService.asyncPrecompress(conversationId);
                    }));
        }
    }

    // ===== 后台任务管理 =====

    private void cancelRunningTask(Long conversationId) {
        Disposable sub = taskSubscriptions.remove(conversationId);
        if (sub != null && !sub.isDisposed()) {
            sub.dispose();
            log.info("取消会话 {} 的正在运行的后台任务", conversationId);
        }
        agentEventBus.unregister(conversationId);
        judgeGrantedIterations.remove(conversationId);
        try {
            jdbcTemplate.update("UPDATE agent_task SET status = 'cancelled', updated_at = NOW() WHERE conversation_id = ? AND status = 'running'", conversationId);
        } catch (Exception e) {
            log.debug("取消任务记录失败: {}", e.getMessage());
        }
    }

    /**
     * 在后台启动工具循环，事件通过 Sinks 转发
     * SSE 断开后工具循环继续执行，不销毁
     */
    private Flux<String> startBackgroundTask(Long conversationId, Long storageConversationId, Map<String, Object> apiRequest) {
        // 创建任务记录
        AgentTask task = new AgentTask(conversationId, MAX_TOOL_CALL_ITERATIONS);
        try {
            jdbcTemplate.update("INSERT INTO agent_task (conversation_id, status, iteration, max_iterations, event_count, created_at, updated_at) VALUES (?, 'running', 0, ?, 0, NOW(), NOW())",
                    conversationId, MAX_TOOL_CALL_ITERATIONS);
            Long taskId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            if (taskId != null) task.setId(taskId);
        } catch (Exception e) {
            log.warn("创建任务记录失败: {}", e.getMessage());
        }

        // 创建事件 Sink（replay 模式，新订阅者可获取历史事件，确保页面刷新重连后不丢事件）
        Sinks.Many<String> sink = Sinks.unsafe().many().replay().limit(100000);
        agentEventBus.register(conversationId, sink);

        // 在后台线程订阅工具循环 Flux
        AtomicInteger eventCounter = new AtomicInteger(0);
        Disposable sub = executeSemiStreamingToolCycle(conversationId, storageConversationId, apiRequest)
                .subscribe(
                        event -> {
                            Sinks.EmitResult result = sink.tryEmitNext(event);
                            if (result != Sinks.EmitResult.OK) {
                                log.trace("事件发送结果: {} (SSE 断开时正常)", result);
                            }
                            // 每 50 个事件更新一次任务状态（准确计数而非概率采样）
                            int count = eventCounter.incrementAndGet();
                            if (task.getId() != null && count % 50 == 0) {
                                try {
                                    jdbcTemplate.update("UPDATE agent_task SET event_count = ?, updated_at = NOW() WHERE id = ?", count, task.getId());
                                } catch (Exception ignored) {}
                            }
                        },
                        error -> {
                            log.error("后台任务失败: {}", error.getMessage());
                            sink.tryEmitError(error);
                            if (task.getId() != null) {
                                try {
                                    jdbcTemplate.update("UPDATE agent_task SET status = 'failed', error_message = ?, updated_at = NOW() WHERE id = ?",
                                            error.getMessage() != null ? error.getMessage().substring(0, Math.min(500, error.getMessage().length())) : "未知错误", task.getId());
                                } catch (Exception ignored) {}
                            }
                            agentEventBus.unregister(conversationId);
                            taskSubscriptions.remove(conversationId);
                        },
                        () -> {
                            log.info("后台任务完成: conversationId={}", conversationId);

                            // 自动收集所有子Agent结果（在[DONE]之前发出，确保前端能收到）
                            try {
                                if (agentForkManager != null && agentForkManager.getPendingAgentCount(conversationId) > 0) {
                                    int pendingCount = agentForkManager.getPendingAgentCount(conversationId);
                                    log.info("主Agent任务完成，自动收集 {} 个子Agent结果", pendingCount);
                                    sink.tryEmitNext("\n═══════════════════════════════════════\n");
                                    sink.tryEmitNext("📋 主Agent已完成，正在等待 " + pendingCount + " 个子Agent结果...\n");
                                    List<SubAgentResult> subResults = agentForkManager.collectPendingAgents(conversationId, 300);
                                    for (SubAgentResult subResult : subResults) {
                                        sink.tryEmitNext("\n─────────────────────────────────────\n");
                                        sink.tryEmitNext(subResult.toResultString());
                                    }
                                    sink.tryEmitNext("\n═══════════════════════════════════════\n");
                                    log.info("子Agent结果收集完成，共 {} 个", subResults.size());
                                }
                            } catch (Exception e) {
                                log.warn("自动收集子Agent结果异常: {}", e.getMessage());
                            }

                            // emit [DONE] so frontend gets complete event with sessionId
                            sink.tryEmitNext("[DONE]");
                            sink.tryEmitComplete();
                            if (task.getId() != null) {
                                try {
                                    int finalCount = eventCounter.get();
                                    jdbcTemplate.update("UPDATE agent_task SET event_count = ?, status = 'completed', updated_at = NOW() WHERE id = ?", finalCount, task.getId());
                                } catch (Exception ignored) {}
                            }
                            // 后台异步预压缩：提前压缩最早的历史，降低下次请求延迟
                            compactionService.asyncPrecompress(conversationId);
                            agentEventBus.unregister(conversationId);
                            taskSubscriptions.remove(conversationId);
                        }
                );
        taskSubscriptions.put(conversationId, sub);

        // 返回 Sink 的 Flux（SSE 客户端订阅此 Flux）
        return sink.asFlux()
                .doOnCancel(() -> log.info("SSE 客户端断开，任务 {} 在后台继续执行", conversationId))
                .doOnError(e -> log.error("SSE 错误: {}", e.getMessage()));
    }

    /**
     * 获取会话的活跃任务状态
     */
    public AgentTask getActiveTask(Long conversationId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT * FROM agent_task WHERE conversation_id = ? AND status = 'running' ORDER BY id DESC LIMIT 1", conversationId);
            if (!rows.isEmpty()) {
                Map<String, Object> row = rows.get(0);
                AgentTask task = new AgentTask();
                task.setId(((Number) row.get("id")).longValue());
                task.setConversationId(((Number) row.get("conversation_id")).longValue());
                task.setStatus((String) row.get("status"));
                task.setIteration(row.get("iteration") != null ? ((Number) row.get("iteration")).intValue() : 0);
                task.setMaxIterations(row.get("max_iterations") != null ? ((Number) row.get("max_iterations")).intValue() : MAX_TOOL_CALL_ITERATIONS);
                task.setErrorMessage((String) row.get("error_message"));
                task.setEventCount(row.get("event_count") != null ? ((Number) row.get("event_count")).intValue() : 0);
                task.setPendingQuestionUuid((String) row.get("pending_question_uuid"));
                task.setPendingQuestionText((String) row.get("pending_question_text"));
                return task;
            }
        } catch (Exception e) {
            log.debug("查询任务状态失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 订阅活跃任务的事件流（用于重连）
     */
    public Flux<String> subscribeToTask(Long conversationId) {
        Sinks.Many<String> sink = agentEventBus.getSink(conversationId);
        if (sink != null) {
            return sink.asFlux()
                    .doOnCancel(() -> log.info("重连客户端断开"));
        }
        // 任务在运行但 Sink 已不存在（如服务重启），返回状态事件
        AgentTask task = getActiveTask(conversationId);
        if (task != null) {
            return Flux.just("{\"event\":\"task_status\",\"status\":\"running\",\"taskId\":" + task.getId() + "}");
        }
        return Flux.empty();
    }

    @Override
    public void cancelTask(Long conversationId) {
        cancelRunningTask(conversationId);
    }

    @Override
    public void processConversationAsync(Long conversationId, String message, String agentType, Long agentConfigId) {
        ChatRequest request = new ChatRequest();
        request.setMessage(message);
        request.setSessionId(conversationId);
        request.setExecutionMode("auto");
        request.setAgentConfigId(agentConfigId);
        String promptFile = "code_agent_prompt.txt";
        request.setPromptFileName(promptFile);

        log.info("定时任务触发 AI 处理: conversationId={}, agentType={}", conversationId, agentType);
        // 订阅 streamChat，AI 回复会自动保存到数据库
        streamChat(request).subscribe(
            data -> {},
            error -> log.error("定时任务 AI 处理失败: conversationId={}", conversationId, error),
            () -> log.info("定时任务 AI 处理完成: conversationId={}", conversationId)
        );
    }

    /**
     * 持久化/清除待审批问题到任务记录（支持页面刷新后重连展示审批对话框）
     * @param conversationId 会话ID
     * @param uuid 待审批问题UUID，为null时清除
     * @param text 待审批问题文本，为null时清除
     */
    private void updatePendingQuestion(Long conversationId, String uuid, String text) {
        try {
            if (uuid != null) {
                jdbcTemplate.update("UPDATE agent_task SET pending_question_uuid = ?, pending_question_text = ?, updated_at = NOW() WHERE conversation_id = ? AND status = 'running' ORDER BY id DESC LIMIT 1",
                        uuid, text, conversationId);
            } else {
                jdbcTemplate.update("UPDATE agent_task SET pending_question_uuid = NULL, pending_question_text = NULL, updated_at = NOW() WHERE conversation_id = ? AND status = 'running' ORDER BY id DESC LIMIT 1",
                        conversationId);
            }
        } catch (Exception e) {
            log.debug("更新待审批问题失败: {}", e.getMessage());
        }
    }

    /**
     * 执行半流式工具调用循环
     * 实现"半流式"架构：
     * 第一阶段（可流式）：模型思考/输出普通文本
     * 第二阶段（非流式）：返回 tool_calls（一次性）
     * 第三阶段（可流式）：最终回答（可以流式）
     *
     * @param conversationId 会话ID
     * @param initialApiRequest 初始API请求
     * @return 流式响应Flux
     */
    private Flux<String> executeSemiStreamingToolCycle(Long conversationId, Long storageConversationId, Map<String, Object> initialApiRequest) {
        // ===== 重置本轮对话的自动批准状态 =====
        // 用户每次发送新消息开始工具循环时，清除上一轮的"本轮对话全部同意"状态
        // "本轮对话"定义为：用户发送一次消息 → LLM完成任务（含多轮工具调用）→ 结束
        PermissionContext.removeSessionApproved(conversationId);
        log.debug("已重置会话 {} 的「本轮对话全部同意」状态（新消息开始）", conversationId);

        // 构建初始消息列表
        List<Map<String, Object>> messages = new ArrayList<>();
        List<Map<String, Object>> historyMessages = buildMessagesFromHistory(conversationId);
        for (Map<String, Object> msg : historyMessages) {
            Map<String, Object> mutableMsg = new HashMap<>(msg);
            messages.add(mutableMsg);
        }

        // 注入技能匹配内容到用户消息（工具循环中同样需要）
        String skillsSection = (String) initialApiRequest.get("_skillsSection");
        if (skillsSection != null) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                Map<String, Object> msg = messages.get(i);
                if ("user".equals(msg.get("role"))) {
                    String existing = (String) msg.get("content");
                    if (!existing.startsWith(skillsSection)) {
                        msg.put("content", skillsSection + "\n\n" + existing);
                        log.info("技能注入: 工具循环中已注入技能到用户消息, 总长度={}", skillsSection.length() + 2 + existing.length());
                    }
                    break;
                }
            }
        }

        // 注入语言强制指令（工具循环中同样需要）
        Map<String, Object> langInstruction = new HashMap<>();
        langInstruction.put("role", "system");
        langInstruction.put("content", buildLanguageInstruction());
        messages.add(langInstruction);

        // 同时将语言指令注入到最后一条用户消息内容中
        injectLanguageIntoLastUserMessage(messages);

        // 初始化评委扩展计数器
        judgeGrantedIterations.put(conversationId, 0);

        // 使用递归函数处理工具调用循环
        // 工具循环结束后自动检查并收集待完成的子Agent结果，确保主Agent对子Agent负责到底
        return handleToolCallIteration(conversationId, storageConversationId, initialApiRequest, messages, 0, MAX_TOOL_CALL_ITERATIONS)
                .concatWith(Flux.defer(() -> {
                    // 工具循环结束后，检查是否有待收集的子Agent
                    if (agentForkManager != null && agentForkManager.getPendingAgentCount(conversationId) > 0) {
                        int pendingCount = agentForkManager.getPendingAgentCount(conversationId);
                        log.info("工具循环结束，检测到 {} 个待收集子Agent，自动等待收集后继续", pendingCount);

                        // 等待所有子Agent完成并收集结果（阻塞等待，最多300秒）
                        List<SubAgentResult> subResults;
                        try {
                            subResults = agentForkManager.collectPendingAgents(conversationId, 300);
                        } catch (Exception e) {
                            log.error("收集子Agent结果失败", e);
                            return Flux.just(createReasoningSSEEvent(
                                    "【系统错误】自动收集子Agent结果失败: " + e.getMessage()));
                        }

                        // 将子Agent结果摘要注入为用户消息，让AI综合汇总
                        String summaryInput = buildSubAgentSummaryInput(subResults);
                        Map<String, Object> summaryUserMsg = new HashMap<>();
                        summaryUserMsg.put("role", "user");
                        summaryUserMsg.put("content", "所有子Agent任务已执行完毕。请综合以下各子Agent的结果输出最终汇总报告：\n\n" + summaryInput
                                + "\n\n请输出最终汇总报告，包括：总体完成情况、各子任务结果、所有文件变更清单、编译验证结果、使用说明或注意事项。");
                        messages.add(summaryUserMsg);

                        // 限制最多3次额外迭代用于汇总
                        int summaryMaxIterations = Math.min(3, MAX_TOOL_CALL_ITERATIONS);

                        // 发送系统提示事件告知前端正在汇总
                        Flux<String> notifyEvent = Flux.just(createReasoningSSEEvent(
                                "所有子Agent任务已完成，正在生成最终汇总报告..."));

                        return notifyEvent.concatWith(handleToolCallIteration(
                                conversationId, storageConversationId, initialApiRequest,
                                messages, 0, summaryMaxIterations));
                    }
                    return Flux.empty();
                }));
    }

    /**
     * 构建子Agent结果汇总输入文本
     */
    private String buildSubAgentSummaryInput(List<SubAgentResult> subResults) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < subResults.size(); i++) {
            sb.append("===== 子Agent ").append(i + 1).append(" =====\n");
            sb.append(subResults.get(i).toResultString()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 递归处理工具调用迭代
     *
     * @param conversationId 会话ID
     * @param initialApiRequest 初始API请求
     * @param messages 当前消息列表
     * @param iteration 当前迭代次数
     * @param maxIterations 最大迭代次数
     * @return 流式响应Flux
     */
    private Flux<String> handleToolCallIteration(Long conversationId, Long storageConversationId, Map<String, Object> initialApiRequest,
                                                 List<Map<String, Object>> messages, int iteration, int maxIterations) {
        if (iteration >= maxIterations) {
            int totalGranted = judgeGrantedIterations.getOrDefault(conversationId, 0);
            if (totalGranted >= MAX_JUDGE_GRANTED_ITERATIONS) {
                log.warn("评委已累计增加 {} 次迭代，达到上限，强制结束", totalGranted);
                return Flux.just(createReasoningSSEEvent(
                        "任务迭代次数已超出评委允许的最大扩展额度（" + MAX_JUDGE_GRANTED_ITERATIONS + " 次），自动终止。"));
            }
            log.warn("达到最大工具调用迭代次数 {}，调用评委评估", maxIterations);
            return evaluateWithJudge(conversationId, storageConversationId, initialApiRequest,
                    messages, iteration, maxIterations);
        }

        log.debug("半流式工具调用循环迭代 {}，消息数量: {}", iteration + 1, messages.size());

        // 构建当前迭代的API请求
        Map<String, Object> apiRequest = new HashMap<>(initialApiRequest);
        apiRequest.put("messages", messages);

        // 始终使用流式请求
        return handleStreamingPhase(conversationId, storageConversationId, apiRequest, messages, iteration, maxIterations);
    }

    // ===== 循环检测（防原地打转） =====

    /**
     * 检测最近连续的工具调用是否重复（相同工具+相同关键参数）
     */
    private boolean hasRepeatedCalls(List<Map<String, Object>> messages, int threshold) {
        int repeatCount = 0;
        String lastPattern = null;

        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            if (!"assistant".equals(msg.get("role")) || !msg.containsKey("tool_calls")) {
                continue;
            }

            JsonNode toolCalls = (JsonNode) msg.get("tool_calls");
            String pattern = buildToolCallPattern(toolCalls);
            if (pattern == null || pattern.isEmpty()) continue;

            if (lastPattern == null) {
                lastPattern = pattern;
                repeatCount = 1;
            } else if (pattern.equals(lastPattern)) {
                repeatCount++;
                if (repeatCount >= threshold) return true;
            } else {
                return false;
            }
        }
        return false;
    }

    /**
     * 构建工具调用的特征签名，用于比较是否重复
     */
    private String buildToolCallPattern(JsonNode toolCalls) {
        if (toolCalls == null || !toolCalls.isArray() || toolCalls.isEmpty()) return "";
        List<String> signatures = new ArrayList<>();
        for (JsonNode call : toolCalls) {
            String name = call.path("function").path("name").asText("");
            String args = call.path("function").path("arguments").asText("");
            signatures.add(extractToolKey(name, args));
        }
        Collections.sort(signatures);
        return String.join("|", signatures);
    }

    /**
     * 提取工具调用的关键参数标识，忽略不影响结果的参数差异
     */
    private String extractToolKey(String toolName, String argsJson) {
        if (argsJson == null || argsJson.isEmpty() || "null".equals(argsJson)) {
            return toolName;
        }
        try {
            JsonNode args = objectMapper.readTree(argsJson);
            return switch (toolName) {
                case "read_file", "write_file", "edit_file", "delete_file" -> {
                    String path = args.path("file_path").asText("");
                    if (path.isEmpty()) path = args.path("path").asText("");
                    yield toolName + ":" + (path.isEmpty() ? "*" : path);
                }
                case "run_command", "run_server" -> {
                    String cmd = args.path("command").asText("");
                    yield toolName + ":" + (cmd.isEmpty() ? "*" : cmd);
                }
                case "execute_sql" -> {
                    String sql = args.path("sql").asText("");
                    yield toolName + ":" + (sql.isEmpty() ? "*" : sql);
                }
                case "git_add" -> {
                    String files = args.path("files").asText("");
                    yield toolName + ":" + (files.isEmpty() ? "*" : files);
                }
                case "git_commit" -> {
                    String msg = args.path("message").asText("");
                    yield toolName + ":" + (msg.isEmpty() ? "*" : msg);
                }
                case "git_push", "service_control" -> {
                    String action = args.path("action").asText("");
                    yield toolName + ":" + (action.isEmpty() ? "*" : action);
                }
                default -> toolName;
            };
        } catch (Exception e) {
            return toolName;
        }
    }

    // ===== 评委评估（迭代超限处理） =====

    /**
     * 评委判断结果
     */
    private static class JudgeResult {
        String judgment; // "extend" or "reject"
        String reason;
        int additionalIterations;
        String summary;
    }

    /**
     * 解析评委 JSON 响应
     * 支持处理被 ```json 代码块包裹的情况
     */
    private JudgeResult parseJudgeResult(String response) {
        try {
            // 检测 API 调用失败的错误文本，避免无效 JSON 解析
            if (response.startsWith("错误：")) {
                log.warn("评委 API 调用返回错误: {}", response);
                return null;
            }
            String jsonStr = response;
            // 提取 ```json ... ``` 包裹的内容
            if (jsonStr.contains("```json")) {
                jsonStr = jsonStr.substring(jsonStr.indexOf("```json") + 7);
                jsonStr = jsonStr.substring(0, jsonStr.indexOf("```"));
            } else if (jsonStr.contains("```")) {
                jsonStr = jsonStr.substring(jsonStr.indexOf("```") + 3);
                jsonStr = jsonStr.substring(0, jsonStr.indexOf("```"));
            }
            jsonStr = jsonStr.trim();

            JsonNode root = objectMapper.readTree(jsonStr);
            JudgeResult result = new JudgeResult();
            result.judgment = root.path("judgment").asText("");
            result.reason = root.path("reason").asText("");
            result.additionalIterations = root.path("additional_iterations").asInt(10);
            result.summary = root.path("summary").asText("");

            if (!"extend".equals(result.judgment) && !"reject".equals(result.judgment)) {
                log.warn("评委返回未知判断: {}", result.judgment);
                return null;
            }
            if (result.judgment.isEmpty()) return null;

            return result;
        } catch (Exception e) {
            log.warn("解析评委响应失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 构建评委评估上下文
     * 将消息按轮次拆分（每轮以 user/system 消息开始），
     * 前序轮次仅作简要摘要，当前轮次的工具调用和结果详细展示，
     * 确保评委聚焦于当前伦次的对话和任务。
     */
    private String buildJudgeContext(List<Map<String, Object>> messages) {
        StringBuilder sb = new StringBuilder();

        // ===== 1. 提取多轮对话历史（所有用户消息，标注轮次） =====
        sb.append("## 对话历史（多轮）\n");
        int round = 0;
        for (Map<String, Object> msg : messages) {
            if ("user".equals(msg.get("role"))) {
                round++;
                String uc = (String) msg.get("content");
                if (uc != null && !uc.isEmpty()) {
                    String truncated = uc.length() > 300 ? uc.substring(0, 300) + "..." : uc;
                    sb.append("[轮次 ").append(round).append("] ").append(truncated).append("\n");
                }
            }
        }
        if (round == 0) {
            sb.append("(无用户消息)\n");
        }

        // ===== 2. 提取工具调用历史摘要（含轮次标记） =====
        sb.append("\n## 工具调用历史\n");
        int iter = 0;
        int currentRound = 0;

        // 先找到所有用户消息的位置，用于标记轮次边界
        java.util.List<Integer> userMsgPositions = new java.util.ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if ("user".equals(messages.get(i).get("role"))) {
                userMsgPositions.add(i);
            }
        }

        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> msg = messages.get(i);
            // 检查是否跨越了用户消息边界（进入新轮次）
            for (int pos : userMsgPositions) {
                if (i == pos) {
                    currentRound++;
                    break;
                }
            }
            if ("assistant".equals(msg.get("role")) && msg.containsKey("tool_calls")) {
                JsonNode toolCalls = (JsonNode) msg.get("tool_calls");
                if (toolCalls != null && toolCalls.isArray()) {
                    for (JsonNode tc : toolCalls) {
                        String name = tc.path("function").path("name").asText("");
                        String args = tc.path("function").path("arguments").asText("");
                        if (args.length() > 200) args = args.substring(0, 200) + "...";
                        sb.append("  [R").append(currentRound).append("][I").append(iter).append("] ")
                                .append(name).append("(").append(args).append(")\n");
                    }
                }
                iter++;
            }
        }

        // ===== 3. 重复检测信息 =====
        boolean hasRepeat = hasRepeatedCalls(messages, 3);
        sb.append("\n## 重复检测\n");
        sb.append("连续重复调用: ").append(hasRepeat ? "是（可能存在死循环）" : "否（调用模式正常）").append("\n");
        sb.append("总迭代次数: ").append(iter).append("\n");
        sb.append("对话轮次: ").append(round).append("\n");

        // 最近 3 次工具结果（截取末尾部分）
        sb.append("\n## 最近工具结果\n");
        int resultCount = 0;
        for (int i = messages.size() - 1; i >= 0 && resultCount < 3; i--) {
            Map<String, Object> msg = messages.get(i);
            if ("tool".equals(msg.get("role"))) {
                String content = (String) msg.get("content");
                if (content != null && !content.isEmpty()) {
                    String truncated = content.length() > 500 ? content.substring(0, 500) + "..." : content;
                    sb.append(truncated).append("\n---\n");
                    resultCount++;
                }
            }
        }

        return sb.toString();
    }

    /**
     * 调用子 agent 评委评估任务进展
     * 迭代超限时，不直接报错退出，而是让评委分析是否应该继续
     */
    private Flux<String> evaluateWithJudge(Long conversationId, Long storageConversationId,
                                           Map<String, Object> initialApiRequest,
                                           List<Map<String, Object>> messages, int iteration, int maxIterations) {
        // 1. 构建评委上下文
        String judgeContext = buildJudgeContext(messages);

        // 2. 加载评委提示词
        String judgePrompt = PromptUtil.getPrompt("judge_prompt.txt");
        if (judgePrompt == null || judgePrompt.isEmpty()) {
            log.warn("评委提示词加载失败，使用默认拒绝策略");
            return Flux.just(createReasoningSSEEvent("评委提示词加载失败，任务自动终止。"));
        }

        // 3. 调用评委 API（非流式，超时 120s，因为 thinking 模式可能耗时较长）
        return Mono.fromCallable(() -> deepSeekAnalyzer.analyze(judgePrompt, judgeContext, 120))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(response -> {
                    // 4. 解析 JSON 响应
                    JudgeResult result = parseJudgeResult(response);
                    if (result == null) {
                        log.warn("评委响应解析失败，使用默认拒绝策略: {}", response);
                        return Flux.just(createReasoningSSEEvent(
                                "评委评估解析失败，任务自动终止。"));
                    }

                    if ("extend".equals(result.judgment)) {
                        int additional = Math.min(result.additionalIterations, 30); // 单次最多 30
                        int newMaxIterations = maxIterations + additional;
                        // 累计已批准的扩展次数
                        int newTotalGranted = judgeGrantedIterations.merge(conversationId, additional, Integer::sum);

                        log.info("评委评估：extend +{}，当前累计扩展 {}，新上限 {}",
                                additional, newTotalGranted, newMaxIterations);

                        String extendEvent = createReasoningSSEEvent(
                                "**🤖 评委评估：任务进展正常，继续执行**\n\n" +
                                        "**理由：** " + result.reason + "\n\n" +
                                        "**当前进度：** " + result.summary + "\n\n" +
                                        "**增加 " + additional + " 次迭代**（已累计扩展 " + newTotalGranted + "/" + MAX_JUDGE_GRANTED_ITERATIONS + " 次）");

                        return Flux.just(extendEvent)
                                .concatWith(handleToolCallIteration(
                                        conversationId, storageConversationId, initialApiRequest,
                                        messages, iteration, newMaxIterations));
                    } else {
                        log.info("评委评估：reject，任务终止");

                        String rejectEvent = createReasoningSSEEvent(
                                "**🤖 评委评估：任务已终止**\n\n" +
                                        "**原因：** " + result.reason + "\n\n" +
                                        "**经验总结：** " + result.summary);

                        return Flux.just(rejectEvent);
                    }
                })
                .onErrorResume(e -> {
                    log.error("评委评估调用失败", e);
                    return Flux.just(createReasoningSSEEvent(
                            "评委评估调用失败，任务自动终止。错误: " + e.getMessage()));
                });
    }

    /**
     * 合并工具调用delta数据
     * @param accumulatedToolCalls 已累积的工具调用映射（index -> toolCall节点）
     * @param deltaToolCalls 当前delta中的tool_calls数组
     * @return 更新后的累积映射
     */
    private Map<Integer, ObjectNode> mergeToolCalls(Map<Integer, ObjectNode> accumulatedToolCalls, JsonNode deltaToolCalls) {
        if (!deltaToolCalls.isArray()) {
            return accumulatedToolCalls;
        }

        for (int i = 0; i < deltaToolCalls.size(); i++) {
            JsonNode toolCallDelta = deltaToolCalls.get(i);
            int index = toolCallDelta.path("index").asInt(-1);
            if (index < 0) {
                // 没有index，使用数组位置作为索引（假设与初始delta顺序一致）
                index = i;
            }

            ObjectNode existing = accumulatedToolCalls.get(index);
            if (existing == null) {
                existing = objectMapper.createObjectNode();
                accumulatedToolCalls.put(index, existing);
            }

            // 合并字段：id、type、function
            JsonNode id = toolCallDelta.path("id");
            if (!id.isMissingNode()) {
                existing.set("id", id);
            }
            JsonNode type = toolCallDelta.path("type");
            if (!type.isMissingNode()) {
                existing.set("type", type);
            }
            JsonNode functionDelta = toolCallDelta.path("function");
            if (!functionDelta.isMissingNode()) {
                JsonNode existingFunction = existing.path("function");
                ObjectNode functionNode;
                if (existingFunction.isMissingNode()) {
                    functionNode = objectMapper.createObjectNode();
                    existing.set("function", functionNode);
                } else {
                    functionNode = (ObjectNode) existingFunction;
                }
                JsonNode name = functionDelta.path("name");
                if (!name.isMissingNode()) {
                    functionNode.set("name", name);
                }
                JsonNode arguments = functionDelta.path("arguments");
                if (!arguments.isMissingNode()) {
                    // 追加arguments字符串
                    String currentArgs = functionNode.path("arguments").asText("");
                    String newArgs = arguments.asText("");
                    functionNode.put("arguments", currentArgs + newArgs);
                }
            }
        }
        return accumulatedToolCalls;
    }

    /**
     * 检查累积的工具调用是否完整
     * @param accumulatedToolCalls 累积的工具调用映射
     * @return 是否完整
     */
    private boolean isToolCallsComplete(Map<Integer, ObjectNode> accumulatedToolCalls) {
        if (accumulatedToolCalls.isEmpty()) {
            return false;
        }
        for (ObjectNode toolCall : accumulatedToolCalls.values()) {
            JsonNode function = toolCall.path("function");
            if (function.isMissingNode()) {
                return false;
            }
            JsonNode arguments = function.path("arguments");
            if (arguments.isMissingNode() || arguments.asText("").isEmpty()) {
                return false;
            }
            // 尝试解析arguments是否为有效JSON
            String argsText = arguments.asText();
            try {
                objectMapper.readTree(argsText);
            } catch (Exception e) {
                // 解析失败，可能还不是完整JSON
                return false;
            }
        }
        return true;
    }

    /**
     * 将累积的工具调用映射转换为JsonNode数组
     * @param accumulatedToolCalls 累积的工具调用映射
     * @return JsonNode数组
     */
    private JsonNode convertAccumulatedToolCallsToArray(Map<Integer, ObjectNode> accumulatedToolCalls) {
        ArrayNode arrayNode = objectMapper.createArrayNode();
        // 按index排序
        accumulatedToolCalls.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> arrayNode.add(entry.getValue()));
        return arrayNode;
    }

    /**
     * 创建包含reasoning_content的SSE事件
     * @param reasoningContent reasoning_content字段的内容
     * @return SSE格式字符串 "data: {...}"
     */
    private String createReasoningSSEEvent(String reasoningContent) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("id", UUID.randomUUID().toString());
            root.put("object", "chat.completion.chunk");
            root.put("created", Instant.now().getEpochSecond());
            root.put("model", deepSeekConfig.getDefaultModel());
            root.put("system_fingerprint", "fp_" + UUID.randomUUID().toString().substring(0, 8));

            ObjectNode choices = objectMapper.createObjectNode();
            choices.put("index", 0);

            ObjectNode delta = objectMapper.createObjectNode();
            delta.putNull("content");
            // 处理null值，转换为空字符串
            delta.put("reasoning_content", reasoningContent != null ? reasoningContent : "");

            choices.set("delta", delta);
            choices.putNull("logprobs");
            choices.putNull("finish_reason");

            ArrayNode choicesArray = objectMapper.createArrayNode();
            choicesArray.add(choices);
            root.set("choices", choicesArray);

            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.error("创建SSE事件失败", e);
            // 返回一个简单的事件作为备选
            return "{\"error\": \"无法生成工具调用事件\"}";
        }
    }

    /**
     * 创建工具调用开始的SSE事件
     * @param toolNames 工具名称列表
     * @return SSE格式字符串
     */
    private String createToolCallStartEvent(List<String> toolNames) {
        String msg;
        if (toolNames.isEmpty()) {
            msg = "正在调用工具...";
        } else {
            msg = "调用 " + String.join(", ", toolNames);
        }
        return createReasoningSSEEvent(msg);
    }

    /**
     * 从 tool_calls JSON 数组中提取工具名称
     */
    private List<String> extractToolNames(JsonNode toolCalls) {
        List<String> names = new java.util.ArrayList<>();
        if (toolCalls != null && toolCalls.isArray()) {
            for (JsonNode call : toolCalls) {
                JsonNode func = call.path("function");
                String name = func.path("name").asText("");
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /**
     * 格式化工具调用结果
     * 格式：前后各加两个换行符，前面拼接虚线包裹的"工具调用:"字符串
     * 虚线长度自适应：每边54个'-'，配合12px字体和800px框体宽度
     * @param toolResult 原始工具调用结果
     * @return 格式化后的工具调用结果
     */
    private String formatToolResult(String toolResult, String toolName) {
        String dashLine = "-".repeat(48);
        String header = dashLine + "工具调用:" + dashLine;
        String toolNameLine = (toolName != null && !toolName.isEmpty()) ? "> **" + toolName + "**\n" : "";

        if (toolResult == null || toolResult.isEmpty()) {
            return "\n\n" + header + "\n" + toolNameLine + "\n\n";
        }
        return "\n\n" + header + "\n" + toolNameLine + toolResult + "\n\n";
    }

    /**
     * 创建工具调用结果的SSE事件（以 thinking 事件发送，前端按标记折叠展示）
     * 在结果内容前插入工具名称行，供前端提取并在缩放条/卡片上展示
     */
    private String createToolResultEvent(String toolName, String toolResult) {
        String dashLine = "-".repeat(48);
        String header = dashLine + "工具调用:" + dashLine;
        String toolNameLine = "> **" + toolName + "**";
        if (toolResult == null || toolResult.isEmpty()) {
            return createReasoningSSEEvent("\n\n" + header + "\n" + toolNameLine + "\n\n");
        }
        return createReasoningSSEEvent("\n\n" + header + "\n" + toolNameLine + "\n" + toolResult + "\n\n");
    }

    /**
     * 处理流式阶段（第一阶段和第三阶段）
     */
    private Flux<String> handleStreamingPhase(Long conversationId, Long storageConversationId, Map<String, Object> apiRequest,
                                              List<Map<String, Object>> messages, int iteration, int maxIterations) {
        apiRequest.put("stream", true);

        // 收集原始响应块，用于调试和保存
        StringBuilder responseBuilder = new StringBuilder();
        // 收集文本内容，用于工具调用前的部分消息
        StringBuilder contentCollector = new StringBuilder();
        // 标志是否应该继续处理事件（检测到工具调用时设为false）
        AtomicBoolean shouldContinue = new AtomicBoolean(true);
        // 工具调用累积状态
        AtomicBoolean accumulatingToolCalls = new AtomicBoolean(false);
        Map<Integer, ObjectNode> accumulatedToolCalls = new HashMap<>();

        // 移除内部 _ 前缀字段后发送
        Map<String, Object> requestBody = new HashMap<>(apiRequest);
        requestBody.keySet().removeIf(k -> k.startsWith("_"));
        return webClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(String.class)
                .doOnNext(chunk -> {
                    // 收集响应块
                    responseBuilder.append(chunk);
                })
                .filter(chunk -> {
                    // 过滤掉空行和[DONE]事件
                    String trimmed = chunk.trim();
                    return !trimmed.isEmpty();
                })
                .takeWhile(chunk -> shouldContinue.get())  // 检测到工具调用后停止处理后续事件
                .flatMap(chunk -> {
                    String trimmed = chunk.trim();
                    String data = trimmed.startsWith("data: ") ? trimmed.substring(6).trim() : trimmed;

                    try {
                        JsonNode eventNode = objectMapper.readTree(data);
                        JsonNode choices = eventNode.path("choices");
                        if (choices.isArray() && choices.size() > 0) {
                            JsonNode delta = choices.get(0).path("delta");

                            // 检查是否有tool_calls
                            JsonNode toolCalls = delta.path("tool_calls");
                            if (!toolCalls.isMissingNode() && toolCalls.isArray() && toolCalls.size() > 0) {
                                log.debug("检测到工具调用，tool_calls内容: {}", toolCalls.toString());
                                // 开始累积工具调用数据
                                accumulatingToolCalls.set(true);
                                mergeToolCalls(accumulatedToolCalls, toolCalls);
                                log.debug("累积工具调用数据，当前累积数量: {}", accumulatedToolCalls.size());

                                // 检查是否完整
                                if (isToolCallsComplete(accumulatedToolCalls)) {
                                    log.debug("工具调用数据接收完整，执行工具调用");
                                    shouldContinue.set(false);  // 停止处理后续事件
                                    accumulatingToolCalls.set(false);

                                    // 将累积的工具调用转换为JsonNode数组
                                    JsonNode completeToolCalls = convertAccumulatedToolCallsToArray(accumulatedToolCalls);
                                    log.debug("完整tool_calls内容: {}", completeToolCalls.toString());

                                    // 保存当前的assistant消息（包含思考过程和内容）
                                    String fullResponseSoFar = responseBuilder.toString();
                                    Map<String, String> extracted = extractContentAndReasoningFromStreamResponse(fullResponseSoFar);
                                    String content = extracted.get("content");
                                    String reasoning = extracted.get("reasoning");
                                    if (content == null || content.isEmpty()) {
                                        content = contentCollector.toString();
                                        if (content.isEmpty()) {
                                            content = fullResponseSoFar;
                                        }
                                    }
                                    String toolCallsJson = completeToolCalls.toString();
                                    // 保存工具调用前的助手思考过程和收集到的文本内容
                                    String collectedContent = contentCollector.toString();
                                    if (collectedContent != null && !collectedContent.isEmpty()) {
                                        saveConversationMessage(storageConversationId, MessageRole.ASSISTANT, collectedContent, reasoning, toolCallsJson);
                                        log.debug("保存工具调用前的助手消息（含文本内容），content长度: {}",
                                                collectedContent.length());
                                    } else {
                                        saveAssistantReasoning(storageConversationId, reasoning, toolCallsJson);
                                        log.debug("保存工具调用前的助手思考过程，reasoning长度: {}",
                                                reasoning != null ? reasoning.length() : 0);
                                    }

                                    // 发送工具调用开始事件
                                    List<String> invokingToolNames = extractToolNames(completeToolCalls);
                                    String toolCallStartEvent = createToolCallStartEvent(invokingToolNames);
                                    log.debug("发送工具调用开始事件: {}", invokingToolNames);

                                    // 提取执行上下文
                                    String currentProjectRoot = (String) apiRequest.get("_projectRoot");
                                    String currentExecutionMode = (String) apiRequest.get("_executionMode");
                                    String agentType = (String) apiRequest.get("_agentType");
                                    Long userId = (Long) apiRequest.get("_userId");
                                    String currentTurnId = (String) apiRequest.get("_turnId");
                                    Long currentAgentConfigId = (Long) apiRequest.get("_agentConfigId");
                                    String collectedContentStr = contentCollector != null ? contentCollector.toString() : "";

                                    // 从工具调用参数生成操作摘要（在所有模式下都注入 thinking 流）
                                    List<String> operationSummaryEvents = new ArrayList<>();
                                    for (int i = 0; i < completeToolCalls.size(); i++) {
                                        JsonNode tc = completeToolCalls.get(i);
                                        String tcToolName = tc.path("function").path("name").asText();
                                        String argsStr = tc.path("function").path("arguments").asText();
                                        if (!argsStr.isEmpty() && !"null".equals(argsStr)) {
                                            try {
                                                JsonNode args = objectMapper.readTree(argsStr);
                                                String summary = detailGenerator.generate(tcToolName, args);
                                                if (summary != null) {
                                                    operationSummaryEvents.add(createReasoningSSEEvent(summary));
                                                }
                                            } catch (Exception e) {
                                                log.debug("生成操作摘要失败: {}", e.getMessage());
                                            }
                                        }
                                    }

                                    // ===== 权限预检：判断是否需要弹窗审批 =====
                                    // 层面一（manual）：affectsData=true
                                    // 层面二（manual）：isPathSensitive + 路径越界
                                    // 层面三（auto）：highRisk=true
                                    boolean hasRestrictedTools = false;
                                    boolean hasHighRiskTools = false;
                                    for (int i = 0; i < completeToolCalls.size(); i++) {
                                        JsonNode tc = completeToolCalls.get(i);
                                        String tcName = tc.path("function").path("name").asText();
                                        if (permissionRegistry.requiresDataApproval(tcName)) {
                                            hasRestrictedTools = true;
                                        }
                                        if (permissionRegistry.isHighRisk(tcName)) {
                                            hasHighRiskTools = true;
                                        }
                                        if ("manual".equals(currentExecutionMode)
                                                && permissionRegistry.isPathSensitive(tcName)) {
                                            String argsStr = tc.path("function").path("arguments").asText();
                                            if (!argsStr.isEmpty() && !"null".equals(argsStr)) {
                                                try {
                                                    JsonNode tcArgs = objectMapper.readTree(argsStr);
                                                    if (pathSecurityChecker.hasCrossPathViolation(tcName, tcArgs)) {
                                                        hasRestrictedTools = true;
                                                    }
                                                } catch (Exception e) {
                                                    // ignore parse errors
                                                }
                                            }
                                        }
                                    }

                                    // manual 模式需审批 = 有受限制工具；auto 模式需审批 = 有高危工具
                                    boolean needsApproval = ("manual".equals(currentExecutionMode) && hasRestrictedTools)
                                            || ("auto".equals(currentExecutionMode) && hasHighRiskTools);

                                    // 检查会话级别自动批准（用户选择了「本轮对话全部同意」）
                                    boolean isSessionApproved = PermissionContext.isSessionApproved(conversationId);
                                    if (isSessionApproved) {
                                        // 已获本轮对话全部批准，跳过所有权限弹窗
                                        needsApproval = false;
                                        log.debug("会话 {} 已获本轮对话全部同意，跳过权限审批", conversationId);
                                    }

                                    if (needsApproval) {
                                        // 构建授权审批事件流
                                        List<String> approvalEvents = new ArrayList<>();
                                        approvalEvents.add(toolCallStartEvent);
                                        approvalEvents.addAll(operationSummaryEvents);

                                        // 从工具调用参数生成审批摘要文本
                                        StringBuilder summarySb = new StringBuilder();
                                        boolean isAuto = "auto".equals(currentExecutionMode);
                                        for (int i = 0; i < completeToolCalls.size(); i++) {
                                            JsonNode tc = completeToolCalls.get(i);
                                            String tcToolName = tc.path("function").path("name").asText();
                                            // manual 模式：汇总所有受限工具；auto 模式：只汇总高危工具
                                            if (isAuto
                                                    ? !permissionRegistry.isHighRisk(tcToolName)
                                                    : !detailGenerator.isRestricted(tcToolName)) {
                                                continue;
                                            }
                                            String argsStr = tc.path("function").path("arguments").asText();
                                            if (argsStr.isEmpty() || "null".equals(argsStr)) continue;
                                            try {
                                                JsonNode args = objectMapper.readTree(argsStr);
                                                String detail = detailGenerator.generate(tcToolName, args);
                                                if (detail != null) {
                                                    // 取第一行实质内容（去掉 markdown 区块引用标记和首尾空白）
                                                    String firstLine = detail.lines()
                                                            .map(String::trim)
                                                            .filter(l -> !l.isEmpty() && !l.equals(">"))
                                                            .findFirst().orElse("");
                                                    String plain = firstLine.replaceAll("^>\\s*", "").replaceAll("[*`]", "");
                                                    summarySb.append("• ").append(plain).append("\n");
                                                }
                                            } catch (Exception e) {
                                                // ignore
                                            }
                                        }

                                        String uuid = UUID.randomUUID().toString();
                                        String prefix = isAuto ? "【自动模式 - 高危操作】\n" : "";
                                        String question = prefix + summarySb.toString()
                                                + "需要您的批准，回复「批准」继续或输入其他内容拒绝";
                                        com.example.agentdeepseek.model.dto.PendingQuestion pq =
                                                new com.example.agentdeepseek.model.dto.PendingQuestion(uuid, question);
                                        pendingQuestionStore.put(uuid, pq);
                                        // 持久化待审批问题到任务记录（支持页面刷新后重连展示）
                                        updatePendingQuestion(conversationId, uuid, question);
                                        approvalEvents.add(createAskUserEvent(uuid, question, "permission"));

                                        return Flux.fromIterable(approvalEvents)
                                                .concatWith(Mono.fromFuture(pq.getFuture())
                                                        .flatMapMany(answer -> {
                                                            pendingQuestionStore.remove(uuid);
                                                            updatePendingQuestion(conversationId, null, null);

                                                            // 解析 action 和用户实际回答
                                                            // answer 格式: "__ACTION__:actionType:userMessage"
                                                            String action = "approve";
                                                            String userAnswer = answer;
                                                            if (answer != null && answer.startsWith("__ACTION__:")) {
                                                                String[] parts = answer.split(":", 3);
                                                                if (parts.length >= 3) {
                                                                    action = parts[1];
                                                                    userAnswer = parts[2];
                                                                }
                                                            }
                                                            log.info("用户授权结果: action={}, userAnswer={}", action, userAnswer);

                                                            // 对于 reject 和 custom，不执行工具，直接继续工具循环
                                                            if ("reject".equals(action)) {
                                                                log.info("用户拒绝了操作，继续工具循环");
                                                                // 先构建 assistant(tool_calls) 消息，确保消息列表格式完整
                                                                Map<String, Object> rejectTcMsg = new HashMap<>();
                                                                rejectTcMsg.put("role", "assistant");
                                                                if (!collectedContentStr.isEmpty()) {
                                                                    rejectTcMsg.put("content", collectedContentStr);
                                                                }
                                                                rejectTcMsg.put("tool_calls", completeToolCalls);
                                                                if (reasoning != null && !reasoning.isEmpty()) {
                                                                    rejectTcMsg.put("reasoning_content", reasoning);
                                                                }
                                                                messages.add(rejectTcMsg);

                                                                // 为每个工具调用添加 tool 结果消息（内容均为拒绝提示）
                                                                String rejectMsg = "用户拒绝了该操作";
                                                                if (completeToolCalls.isArray()) {
                                                                    for (int i = 0; i < completeToolCalls.size(); i++) {
                                                                        Map<String, Object> toolMsg = new HashMap<>();
                                                                        toolMsg.put("role", "tool");
                                                                        toolMsg.put("content", rejectMsg);
                                                                        toolMsg.put("tool_call_id",
                                                                                completeToolCalls.get(i).path("id").asText("call_rejected_" + i));
                                                                        messages.add(toolMsg);
                                                                    }
                                                                }

                                                                String rejectEvent = createReasoningSSEEvent(
                                                                        "用户拒绝了该操作，请重新规划任务。");
                                                                return Flux.just(rejectEvent)
                                                                        .concatWith(handleToolCallIteration(
                                                                                conversationId, storageConversationId, apiRequest,
                                                                                messages, iteration + 1, maxIterations));
                                                            }

                                                            if ("custom".equals(action)) {
                                                                log.info("用户输入自定义消息: {}", userAnswer);
                                                                // 将用户输入的自定义消息作为 tool 结果注入到消息列表
                                                                String customMsg = "用户指示: " + userAnswer;
                                                                Map<String, Object> tcMsg = new HashMap<>();
                                                                tcMsg.put("role", "assistant");
                                                                if (!collectedContentStr.isEmpty()) {
                                                                    tcMsg.put("content", collectedContentStr);
                                                                }
                                                                tcMsg.put("tool_calls", completeToolCalls);
                                                                if (reasoning != null && !reasoning.isEmpty()) {
                                                                    tcMsg.put("reasoning_content", reasoning);
                                                                }
                                                                messages.add(tcMsg);

                                                                // 为每个工具调用添加 tool 结果消息（内容均为用户自定义指示）
                                                                if (completeToolCalls.isArray()) {
                                                                    for (int i = 0; i < completeToolCalls.size(); i++) {
                                                                        Map<String, Object> toolMsg = new HashMap<>();
                                                                        toolMsg.put("role", "tool");
                                                                        toolMsg.put("content", customMsg);
                                                                        toolMsg.put("tool_call_id",
                                                                                completeToolCalls.get(i).path("id").asText("call_custom_" + i));
                                                                        messages.add(toolMsg);
                                                                    }
                                                                }

                                                                String customEvent = createReasoningSSEEvent(
                                                                        "用户消息: " + userAnswer);
                                                                return Flux.just(customEvent)
                                                                        .concatWith(handleToolCallIteration(
                                                                                conversationId, storageConversationId, apiRequest,
                                                                                messages, iteration + 1, maxIterations));
                                                            }

                                                            // approve 或 approve_all：执行工具
                                                            boolean approveAll = "approve_all".equals(action);

                                                            // 设置工具执行上下文
                                                            if (currentProjectRoot != null && !currentProjectRoot.isEmpty()) {
                                                                ProjectRootContext.set(currentProjectRoot);
                                                            }
                                                            ToolContext.set(currentExecutionMode, conversationId,
                                                                    agentType, userId);
                                                            if (currentTurnId != null) {
                                                                ToolContext.setTurnId(currentTurnId);
                                                            }
                                                            if (currentAgentConfigId != null) {
                                                                ToolContext.setAgentConfigId(currentAgentConfigId);
                                                            }
                                                            PermissionContext.set(pendingQuestionStore, objectMapper);
                                                            PermissionContext.setApproved();

                                                            // 如果是「本轮对话全部同意」，设置会话级别自动批准
                                                            if (approveAll) {
                                                                PermissionContext.setSessionApproved(conversationId);
                                                            }

                                                            List<ToolExecutor.ToolCallResult> innerResults;
                                                            try {
                                                                innerResults = toolExecutor.executeToolCalls(completeToolCalls);
                                                            } finally {
                                                                PermissionContext.clear();
                                                                ProjectRootContext.clear();
                                                                ToolContext.clear();
                                                            }

                                                            // 将工具调用消息添加到消息列表
                                                            Map<String, Object> tcMsg = new HashMap<>();
                                                            tcMsg.put("role", "assistant");
                                                            if (!collectedContentStr.isEmpty()) {
                                                                tcMsg.put("content", collectedContentStr);
                                                            }
                                                            tcMsg.put("tool_calls", completeToolCalls);
                                                            if (reasoning != null && !reasoning.isEmpty()) {
                                                                tcMsg.put("reasoning_content", reasoning);
                                                            }
                                                            messages.add(tcMsg);

                                                            // 构建工具结果消息并保存到数据库
                                                            List<ObjectNode> tcMessages = toolExecutor.buildToolMessages(innerResults);
                                                            for (ObjectNode tcMessage : tcMessages) {
                                                                messages.add(objectMapper.convertValue(tcMessage, Map.class));
                                                                saveToolMessage(storageConversationId,
                                                                        formatToolResult(tcMessage.path("content").asText(""),
                                                                                tcMessage.path("tool_name").asText("")));
                                                            }

                                                            // 创建工具结果事件流（先发 resume 恢复前端流）
                                                            List<String> resultEvents = new ArrayList<>();
                                                            resultEvents.add(createResumeEvent());
                                                            for (ToolExecutor.ToolCallResult tr : innerResults) {
                                                                resultEvents.add(createToolResultEvent(tr.getToolName(), tr.getContent()));
                                                            }

                                                            return Flux.fromIterable(resultEvents)
                                                                    .concatWith(handleToolCallIteration(
                                                                            conversationId, storageConversationId, apiRequest,
                                                                            messages, iteration + 1, maxIterations));
                                                        })
                                                        .timeout(java.time.Duration.ofMinutes(5))
                                                        .onErrorResume(e -> {
                                                            pendingQuestionStore.remove(uuid);
                                                            updatePendingQuestion(conversationId, null, null);
                                                            if (e instanceof java.util.concurrent.TimeoutException) {
                                                                log.warn("等待用户授权超时: uuid={}", uuid);
                                                                return Flux.just(createReasoningSSEEvent("等待用户授权超时，请重新发送消息。"));
                                                            }
                                                            log.error("用户授权处理失败: uuid={}", uuid, e);
                                                            return Flux.just(createReasoningSSEEvent("处理用户授权时出错，请重新发送消息。"));
                                                        })
                                                );
                                    }

                                    // ---- 自动模式：直接执行工具 ----
                                    if (currentProjectRoot != null && !currentProjectRoot.isEmpty()) {
                                        ProjectRootContext.set(currentProjectRoot);
                                    }
                                    ToolContext.set(currentExecutionMode, conversationId,
                                            agentType, userId);
                                    if (currentTurnId != null) {
                                        ToolContext.setTurnId(currentTurnId);
                                    }
                                    if (currentAgentConfigId != null) {
                                        ToolContext.setAgentConfigId(currentAgentConfigId);
                                    }
                                    PermissionContext.set(pendingQuestionStore, objectMapper);

                                    List<ToolExecutor.ToolCallResult> toolResults;
                                    try {
                                        toolResults = toolExecutor.executeToolCalls(completeToolCalls);
                                    } finally {
                                        PermissionContext.clear();
                                        ProjectRootContext.clear();
                                        ToolContext.clear();
                                    }
                                    log.debug("工具调用执行完成，结果数量: {}", toolResults.size());

                                    // 检查是否有 ask_user 问题
                                    ToolExecutor.ToolCallResult askUserResult = findAskUserResult(toolResults);
                                    if (askUserResult != null) {
                                        Flux<String> preEvents = Flux.empty();
                                        Flux<String> summaryFlux = Flux.just(toolCallStartEvent);
                                        for (String se : operationSummaryEvents) {
                                            summaryFlux = summaryFlux.concatWith(Flux.just(se));
                                        }
                                        return Flux.concat(preEvents, summaryFlux)
                                                .concatWith(
                                                        handleAskUserFlow(askUserResult, toolResults, completeToolCalls,
                                                                messages, toolCallStartEvent, collectedContentStr, reasoning,
                                                                conversationId, storageConversationId, apiRequest, iteration, maxIterations));
                                    }

                                    // 将工具调用消息添加到消息列表，包含之前收集的文本内容
                                    Map<String, Object> toolCallMessage = new HashMap<>();
                                    toolCallMessage.put("role", "assistant");
                                    if (!collectedContentStr.isEmpty()) {
                                        toolCallMessage.put("content", collectedContentStr);
                                    }
                                    toolCallMessage.put("tool_calls", completeToolCalls);
                                    if (reasoning != null && !reasoning.isEmpty()) {
                                        toolCallMessage.put("reasoning_content", reasoning);
                                    }
                                    messages.add(toolCallMessage);

                                    // 创建工具结果事件流
                                    List<String> toolResultEvents = new ArrayList<>();
                                    for (ToolExecutor.ToolCallResult toolResult : toolResults) {
                                        String toolResultEvent = createToolResultEvent(toolResult.getToolName(), toolResult.getContent());
                                        toolResultEvents.add(toolResultEvent);
                                        log.debug("创建工具结果事件，内容长度: {}", toolResult.getContent() != null ? toolResult.getContent().length() : 0);
                                    }

                                    // 将工具结果消息添加到消息列表，并保存工具消息到数据库
                                    List<ObjectNode> toolMessages = toolExecutor.buildToolMessages(toolResults);
                                    for (ObjectNode toolMessage : toolMessages) {
                                        messages.add(objectMapper.convertValue(toolMessage, Map.class));
                                        String toolContent = toolMessage.path("content").asText("");
                                        String toolName = toolMessage.path("tool_name").asText("");
                                        String formattedToolContent = formatToolResult(toolContent, toolName);
                                        saveToolMessage(storageConversationId, formattedToolContent);
                                    }

                                    // 创建事件流：刷新缓冲+工具调用开始事件+操作摘要+工具结果事件+下一轮迭代
                                    Flux<String> toolCallEvents = Flux.just(toolCallStartEvent);
                                    for (String summaryEvent : operationSummaryEvents) {
                                        toolCallEvents = toolCallEvents.concatWith(Flux.just(summaryEvent));
                                    }
                                    for (String toolResultEvent : toolResultEvents) {
                                        toolCallEvents = toolCallEvents.concatWith(Flux.just(toolResultEvent));
                                    }

                                    // 继续下一轮迭代（使用非流式）
                                    Flux<String> nextIteration = handleToolCallIteration(conversationId, storageConversationId, apiRequest, messages, iteration + 1, maxIterations);
                                    return Flux.concat(toolCallEvents, nextIteration);
                                } else {
                                    // 数据还不完整，继续累积，不转发事件
                                    log.debug("工具调用数据还不完整，继续累积");
                                    return Flux.empty();
                                }
                            }

                            // 提取文本内容并收集
                            JsonNode contentNode = delta.path("content");
                            if (!contentNode.isMissingNode() && !contentNode.isNull()) {
                                String contentValue = contentNode.asText("");
                                if (!contentValue.isEmpty()) {
                                    contentCollector.append(contentValue);
                                }
                            }
                            // 检查 reasoning_content（始终实时流式输出，不过审查）
                            JsonNode reasoningNode = delta.path("reasoning_content");
                            if (!reasoningNode.isMissingNode() && !reasoningNode.isNull() && !reasoningNode.asText("").isEmpty()) {
                                log.debug("reasoning_content 实时转发: len={}", reasoningNode.asText("").length());
                                return Flux.just(data);
                            }
                        }
                    } catch (Exception e) {
                        log.debug("解析SSE事件失败，可能是不完整的JSON: {}", e.getMessage());
                        // 忽略解析错误，继续转发原始数据
                    }

                    // 如果正在累积工具调用但不完整，不转发事件
                    if (accumulatingToolCalls.get()) {
                        return Flux.empty();
                    }

                    // 过滤 [DONE] 标记，由链路的完成信号（concatWith / onComplete）负责结束
                    if (data.trim().equals("[DONE]")) {
                        return Flux.empty();
                    }
                    // 其他事件实时转发
                    return Flux.just(data);
                })
                .doOnComplete(() -> {
                    // 检查是否有未完成的工具调用累积
                    if (accumulatingToolCalls.get()) {
                        log.warn("流式调用在工具调用参数累积过程中结束，参数可能不完整。累积数据: {}", accumulatedToolCalls);
                        // 可以选择尝试执行不完整的工具调用，但这里选择丢弃并记录警告
                    }

                    // 只有在没有检测到工具调用（shouldContinue为true）且流正常完成时才保存助手消息
                    if (shouldContinue.get()) {
                        String fullResponse = responseBuilder.toString();
                        Map<String, String> extracted = extractContentAndReasoningFromStreamResponse(fullResponse);
                        String content = extracted.get("content");
                        String reasoning = extracted.get("reasoning");
                        if (content == null || content.isEmpty()) {
                            content = contentCollector.toString();
                            if (content.isEmpty()) {
                                content = cleanSseContent(fullResponse);
                                log.warn("工具循环中未能从流式响应解析出content, 使用清洗后的fullResponse: {}", content);
                            }
                        }
                        saveAssistantMessage(storageConversationId, content, reasoning);
                        log.debug("流式调用完成，保存助手消息，content长度: {}, reasoning长度: {}",
                                content != null ? content.length() : 0, reasoning != null ? reasoning.length() : 0);
                        // 将助手消息添加到消息列表，以便后续迭代使用（虽然此时应该没有后续迭代）
                        Map<String, Object> assistantMessage = new HashMap<>();
                        assistantMessage.put("role", "assistant");
                        // 只有当有内容时才添加content字段
                        if (content != null && !content.isEmpty()) {
                            assistantMessage.put("content", content);
                        }
                        // DeepSeek 思考模式要求将 reasoning_content 传回
                        if (reasoning != null && !reasoning.isEmpty()) {
                            assistantMessage.put("reasoning_content", reasoning);
                        }
                        messages.add(assistantMessage);
                    }
                })
                .doOnError(error -> {
                    if (error instanceof WebClientResponseException wcre) {
                        log.error("DeepSeek API {} 调用失败，状态码: {}, 响应体: {}",
                                apiRequest.get("model"), wcre.getStatusCode(), wcre.getResponseBodyAsString());
                    } else {
                        log.error("DeepSeek API 调用失败: {}", error.getMessage());
                    }
                });
    }


    private String[] parseAskResult(String content) {
        if (content == null) {
            return null;
        }
        String prefix;
        if (content.startsWith("__ASK_CLARIFICATION__:")) {
            prefix = "__ASK_CLARIFICATION__:";
        } else if (content.startsWith("__ASK_EXECUTION__:")) {
            // 兼容旧版本 ask_execution 结果
            prefix = "__ASK_EXECUTION__:";
        } else {
            return null;
        }
        int uuidStart = prefix.length();
        int uuidEnd = uuidStart + 36;
        if (content.length() < uuidEnd + 1) {
            log.warn("ask 工具返回格式不完整: {}", content);
            return null;
        }
        String uuid = content.substring(uuidStart, uuidEnd);
        String question = content.substring(uuidEnd + 1);
        return new String[] { uuid, question };
    }
    // ===== ask_user 相关方法 =====

    /**
     * 检查工具结果中是否有 ask_user 问题
     */
    private ToolExecutor.ToolCallResult findAskUserResult(List<ToolExecutor.ToolCallResult> toolResults) {
        for (ToolExecutor.ToolCallResult result : toolResults) {
            if (parseAskResult(result.getContent()) != null) {
                return result;
            }
        }
        return null;
    }

    /**
     * 创建 ask_user SSE 事件
     *
     * @param askType 事件类型：permission（权限授权）或 clarification（询问用户需求）
     */
    private String createAskUserEvent(String uuid, String question, String askType) {
        try {
            ObjectNode event = objectMapper.createObjectNode();
            event.put("event", "ask_user");
            event.put("uuid", uuid);
            event.put("question", question);
            event.put("askType", askType);
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            log.error("创建 ask_user 事件失败", e);
            return "{\"event\":\"ask_user\",\"uuid\":\"error\",\"question\":\"创建事件失败\"}";
        }
    }

    /**
     * 创建恢复事件的 SSE 事件（通知前端继续接收流）
     */
    private String createResumeEvent() {
        return "{\"event\":\"resume\"}";
    }

    /**
     * 处理 ask_user 流程：暂停工具循环，等待用户回答，然后继续
     */
    private Flux<String> handleAskUserFlow(
            ToolExecutor.ToolCallResult askUserResult,
            List<ToolExecutor.ToolCallResult> toolResults,
            JsonNode completeToolCalls,
            List<Map<String, Object>> messages,
            String toolCallStartEvent,
            String collectedContent,
            String reasoning,
            Long conversationId,
            Long storageConversationId,
            Map<String, Object> apiRequest,
            int iteration,
            int maxIterations) {

        // 解析 ask_clarification 结果
        String content = askUserResult.getContent();
        String[] parsed = parseAskResult(content);
        if (parsed == null) {
            log.error("无法解析 ask 工具返回结果: {}", content);
            return Flux.just("{\"event\":\"error\",\"message\":\"解析 ask 结果失败\"}");
        }
        String uuid = parsed[0];
        String question = parsed[1];

        // 保存待处理问题
        com.example.agentdeepseek.model.dto.PendingQuestion pq =
                new com.example.agentdeepseek.model.dto.PendingQuestion(uuid, question);
        pendingQuestionStore.put(uuid, pq);
        // 持久化待审批问题到任务记录（支持页面刷新后重连展示）
        updatePendingQuestion(conversationId, uuid, question);

        log.info("ask_user 等待回答: uuid={}, question={}", uuid, question);

        // 创建 ask_user SSE 事件
        String questionEvent = createAskUserEvent(uuid, question, "clarification");

        // 返回 Flux：发出问题事件 → 等待回答 → 用答案替换工具结果 → 继续工具循环
        return Flux.just(questionEvent)
                .concatWith(Mono.fromFuture(pq.getFuture())
                        .flatMapMany(answer -> {
                            log.info("收到用户回答: uuid={}, answer={}", uuid, answer);

                            // 创建替换后的工具结果
                            ToolExecutor.ToolCallResult answeredResult = new ToolExecutor.ToolCallResult(
                                    askUserResult.getToolCallId(),
                                    askUserResult.getToolName(),
                                    answer
                            );

                            // 在 toolResults 中替换 ask_user 的结果
                            List<ToolExecutor.ToolCallResult> modifiedResults = new ArrayList<>(toolResults);
                            int idx = -1;
                            for (int i = 0; i < modifiedResults.size(); i++) {
                                if (modifiedResults.get(i) == askUserResult) {
                                    idx = i;
                                    break;
                                }
                            }
                            if (idx >= 0) {
                                modifiedResults.set(idx, answeredResult);
                            }

                            // 将工具调用消息添加到消息列表
                            Map<String, Object> toolCallMessage = new HashMap<>();
                            toolCallMessage.put("role", "assistant");
                            if (collectedContent != null && !collectedContent.isEmpty()) {
                                toolCallMessage.put("content", collectedContent);
                            }
                            toolCallMessage.put("tool_calls", completeToolCalls);
                            if (reasoning != null && !reasoning.isEmpty()) {
                                toolCallMessage.put("reasoning_content", reasoning);
                            }
                            messages.add(toolCallMessage);

                            // 将包含回答的工具结果添加到消息列表并保存到数据库
                            List<ObjectNode> toolMsgNodes = toolExecutor.buildToolMessages(modifiedResults);
                            for (ObjectNode toolMsgNode : toolMsgNodes) {
                                messages.add(objectMapper.convertValue(toolMsgNode, Map.class));
                                String toolContent = toolMsgNode.path("content").asText("");
                                String toolName = toolMsgNode.path("tool_name").asText("");
                                String formattedToolContent = formatToolResult(toolContent, toolName);
                                saveToolMessage(storageConversationId, formattedToolContent);
                            }

                            // 发送恢复事件，然后继续工具循环
                            return Flux.just(createResumeEvent())
                                    .concatWith(handleToolCallIteration(
                                            conversationId, storageConversationId, apiRequest,
                                            messages, iteration + 1, maxIterations));
                        })
                        .timeout(java.time.Duration.ofMinutes(5))
                        .onErrorResume(e -> {
                            pendingQuestionStore.remove(uuid);
                            updatePendingQuestion(conversationId, null, null);
                            if (e instanceof java.util.concurrent.TimeoutException) {
                                log.warn("等待用户回答超时: uuid={}", uuid);
                                String errorMsg = "等待用户回答超时，请重新发送消息。";
                                return Flux.just(createReasoningSSEEvent(errorMsg));
                            }
                            log.error("用户回答后处理失败: uuid={}", uuid, e);
                            String errorMsg = "处理用户回答时出错，请重新发送消息。";
                            return Flux.just(createReasoningSSEEvent(errorMsg));
                        })
                );
    }

    /**
     * 从角色性格配置 JSON 构建提示词片段（简洁格式，无特殊符号）
     */
    private String buildCharacterSection(String characterJson) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(characterJson);
            StringBuilder sb = new StringBuilder("\n\n请扮演以下角色设定：\n");
            appendCharField(sb, root, "name", "姓名");
            appendCharField(sb, root, "species", "物种");
            appendCharField(sb, root, "gender", "性别");
            appendCharField(sb, root, "age", "年龄");
            appendCharField(sb, root, "personality", "性格");
            appendCharField(sb, root, "greeting", "对你的称呼");
            appendCharField(sb, root, "background", "背景");
            appendCharField(sb, root, "likes", "喜好");
            appendCharField(sb, root, "style", "说话风格");
            if (sb.length() <= "请扮演以下角色设定：\n".length()) {
                return null;
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("解析角色性格配置失败", e);
            return null;
        }
    }

    private void appendCharField(StringBuilder sb, com.fasterxml.jackson.databind.JsonNode node, String field, String label) {
        com.fasterxml.jackson.databind.JsonNode value = node.get(field);
        if (value != null && !value.asText().trim().isEmpty()) {
            sb.append("  ").append(label).append(": ").append(value.asText().trim()).append("\n");
        }
    }

}
