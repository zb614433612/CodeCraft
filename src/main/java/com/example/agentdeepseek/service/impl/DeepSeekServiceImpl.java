package com.example.agentdeepseek.service.impl;

import com.example.agentdeepseek.config.DeepSeekConfig;
import com.example.agentdeepseek.mapper.ConversationMapper;
import com.example.agentdeepseek.mapper.ConversationMessageMapper;
import com.example.agentdeepseek.model.dto.ChatRequest;
import com.example.agentdeepseek.model.entity.Conversation;
import com.example.agentdeepseek.model.entity.ConversationMessage;
import com.example.agentdeepseek.model.entity.MessageRole;
import com.example.agentdeepseek.service.DeepSeekService;
import com.example.agentdeepseek.service.PendingQuestionStore;
import com.example.agentdeepseek.tool.ExecutionTokenManager;
import com.example.agentdeepseek.tool.ToolExecutor;
import com.example.agentdeepseek.util.ProjectRootContext;
import com.example.agentdeepseek.util.PromptUtil;
import com.example.agentdeepseek.util.ToolContext;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DeepSeek API服务实现类
 * 处理与DeepSeek API的通信
 */
@Slf4j
@Service
public class DeepSeekServiceImpl implements DeepSeekService, InitializingBean {

    private final WebClient webClient;
    private final DeepSeekConfig deepSeekConfig;
    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper conversationMessageMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolExecutor toolExecutor;
    private final PendingQuestionStore pendingQuestionStore;
    private final ExecutionTokenManager executionTokenManager;

    // 常量定义
    private static final String DATA_PREFIX = "data: ";
    private static final String TEMPERATURE_FIELD = "temperature";
    private static final int MAX_TOOL_CALL_ITERATIONS = 50;
    private static final double DEFAULT_TEMPERATURE = 1.0;
    private static final int SESSION_NAME_TRUNCATE_LENGTH = 6;

    public DeepSeekServiceImpl(WebClient deepSeekWebClient,
                               DeepSeekConfig deepSeekConfig,
                               ConversationMapper conversationMapper,
                               ConversationMessageMapper conversationMessageMapper,
                               JdbcTemplate jdbcTemplate,
                               ToolExecutor toolExecutor,
                               PendingQuestionStore pendingQuestionStore,
                               ExecutionTokenManager executionTokenManager) {
        this.webClient = deepSeekWebClient;
        this.deepSeekConfig = deepSeekConfig;
        this.conversationMapper = conversationMapper;
        this.conversationMessageMapper = conversationMessageMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.toolExecutor = toolExecutor;
        this.pendingQuestionStore = pendingQuestionStore;
        this.executionTokenManager = executionTokenManager;
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

            // 添加user_id列到user_profile表（如果不存在）
            try {
                jdbcTemplate.execute("ALTER TABLE user_profile ADD COLUMN user_id BIGINT COMMENT '用户ID，关联sys_user.id'");
                log.debug("添加user_profile.user_id列成功");
            } catch (Exception e) {
                log.debug("添加user_profile.user_id列失败，可能已经存在: {}", e.getMessage());
            }

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
            case "romantic_chat_agent_prompt.txt" -> "chat_assistant";
            case "code_agent_prompt.txt" -> "code_assistant";
            default -> "ai_assistant";
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
    private Conversation getOrCreateConversation(Long sessionId, String userMessage, Long userId, String agentType) {
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
        Conversation conversation = new Conversation(sessionName, userId, agentType);
        conversationMapper.insert(conversation);
        log.debug("创建新会话: ID={}, Name={}, UserId={}, AgentType={}", conversation.getId(), conversation.getName(), userId, agentType);
        return conversation;
    }

    /**
     * 保存用户消息
     * @param conversationId 会话ID
     * @param content 消息内容
     */
    private void saveUserMessage(Long conversationId, String content) {
        saveConversationMessage(conversationId, MessageRole.USER, content, null, null);
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
     * @param conversationId 会话ID
     * @return 消息列表，每个元素包含role、content和可选的tool_calls
     */
    private List<Map<String, Object>> buildMessagesFromHistory(Long conversationId) {
        List<ConversationMessage> messages = conversationMessageMapper.selectByConversationId(conversationId);
        List<Map<String, Object>> result = new ArrayList<>();
        // 存储当前assistant消息的tool call IDs，用于后续tool消息的tool_call_id
        List<String> pendingToolCallIds = new ArrayList<>();

        for (ConversationMessage msg : messages) {
            Map<String, Object> messageMap = new HashMap<>();
            MessageRole role = msg.getRole();

            // 根据角色处理消息
            if (role == MessageRole.USER || role == MessageRole.SYSTEM) {
                // USER和SYSTEM消息：直接使用content
                messageMap.put("role", role.getValue());
                String content = msg.getContent() != null ? msg.getContent() : "";
                messageMap.put("content", content);
                result.add(messageMap);
            } else if (role == MessageRole.ASSISTANT) {
                // ASSISTANT消息：可能有content、tool_calls或两者都有
                messageMap.put("role", "assistant");

                // 在处理当前assistant消息前，检查是否有未消耗的tool call IDs
                // 正常情况下应该为空，如果有残留说明数据不一致
                if (!pendingToolCallIds.isEmpty()) {
                    log.warn("发现未消耗的tool call IDs，可能数据不一致: {}", pendingToolCallIds);
                    pendingToolCallIds.clear();
                }

                // 处理tool_calls
                if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                    try {
                        JsonNode toolCallsNode = objectMapper.readTree(msg.getToolCalls());
                        messageMap.put("tool_calls", toolCallsNode);

                        // 提取tool call IDs，用于后续tool消息
                        if (toolCallsNode.isArray()) {
                            for (JsonNode toolCall : toolCallsNode) {
                                JsonNode idNode = toolCall.path("id");
                                if (!idNode.isMissingNode() && !idNode.isNull()) {
                                    pendingToolCallIds.add(idNode.asText());
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.warn("解析tool_calls JSON失败: {}", e.getMessage());
                        // 忽略，不添加tool_calls字段
                    }
                }

                // 处理content：只有当content不为空时才添加content字段
                // 如果存在tool_calls，根据DeepSeek API规范，content字段可选
                String content = msg.getContent();
                if (content != null && !content.isEmpty()) {
                    messageMap.put("content", content);
                }

                // DeepSeek 思考模式要求将 reasoning_content 传回 API
                String reasoning = msg.getReasoning();
                if (reasoning != null && !reasoning.isEmpty()) {
                    messageMap.put("reasoning_content", reasoning);
                }

                result.add(messageMap);
            } else if (role == MessageRole.TOOL) {
                // TOOL消息：需要tool_call_id和content
                // tool_call_id来自前一个assistant消息的tool_calls中的对应ID
                if (!pendingToolCallIds.isEmpty()) {
                    // 按顺序取第一个tool call ID
                    String toolCallId = pendingToolCallIds.remove(0);
                    messageMap.put("role", "tool");
                    messageMap.put("tool_call_id", toolCallId);

                    // 使用reasoning作为content，如果没有reasoning则使用content
                    String content = msg.getReasoning() != null ? msg.getReasoning() :
                                    (msg.getContent() != null ? msg.getContent() : "");
                    messageMap.put("content", content);

                    result.add(messageMap);
                } else {
                    // 没有pending tool call IDs，可能是数据不一致，跳过或记录警告
                    log.warn("TOOL消息没有对应的tool call ID，跳过该消息: {}", msg.getId());
                }
            }
        }
        return result;
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
     * 会话上下文，包含会话ID和API请求
     */
    private record ConversationContext(Long conversationId, Map<String, Object> apiRequest, List<String> toolNames, Long storageConversationId) {}

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
        String promptFileName = request.getPromptFileName();
        if (promptFileName == null || promptFileName.trim().isEmpty()) {
            promptFileName = "system_prompt.txt";
        }
        // 加载提示词内容
        String promptContent = PromptUtil.getPrompt(promptFileName);
        // 从配置中获取该 prompt 文件对应的工具组
        List<String> toolNames = deepSeekConfig.getToolGroups()
                .getOrDefault(promptFileName, Collections.emptyList());

        // 当promptFileName为romantic_chat_agent_prompt.txt且userProfileId不为空时，直接用userProfileId作为conversation_id
        Long userProfileId = request.getUserProfileId();
        boolean useProfileAsConversation = userProfileId != null && "romantic_chat_agent_prompt.txt".equals(promptFileName);

        Long conversationId;
        Long storageConversationId;
        if (useProfileAsConversation) {
            conversationId = userProfileId;
            storageConversationId = userProfileId;
            log.debug("使用userProfileId作为会话ID: {}", conversationId);
        } else {
            String agentType = resolveAgentType(promptFileName);
            Conversation conversation = getOrCreateConversation(sessionId, userMessage, userId, agentType);
            conversationId = conversation.getId();
            storageConversationId = conversationId;
        }

        // 保存用户消息
        saveUserMessage(storageConversationId, userMessage);

        // 构建历史消息（包括本次用户消息）
        List<Map<String, Object>> historyMessages = buildMessagesFromHistory(conversationId);

        // 检查是否为首次会话（是否已存在SYSTEM消息）
        boolean hasSystemMessage = historyMessages.stream()
                .anyMatch(msg -> "system".equals(msg.get("role")));

        // 如果是首次会话且没有SYSTEM消息，添加系统提示词
        if (!hasSystemMessage) {
            String systemPrompt = promptContent;
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
        String executionMode = request.getExecutionMode();
        if (executionMode != null && !executionMode.isEmpty()) {
            String modeInstruction;
            if ("manual".equals(executionMode)) {
                modeInstruction = "\n\n当前模式：手动。写入文件、修改文件或执行系统命令前，必须调用 ask_execution 工具询问用户，获得确认后才能执行。注意：每次 ask_execution 只授予一次执行权限，用完一个受限工具后需要再次调用 ask_execution 才能执行下一个。不要描述你要调用 ask_execution 的意图——直接调用它。当需求模糊或需要用户决策时，调用 ask_clarification 工具询问用户。";
            } else {
                modeInstruction = "\n\n当前模式：自动。直接执行所有任务，无需征求用户同意。注意：ask_execution 工具在当前模式下不可用，请自行做出决策并直接执行。行为约束中要求「询问用户」的条款在当前模式下均不适用。";
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

        // 根据执行模式过滤工具列表：auto 模式移除 ask_execution（ask_clarification 始终可用）
        List<String> filteredToolNames = new ArrayList<>(toolNames);
        if ("auto".equals(executionMode) || executionMode == null || executionMode.isEmpty()) {
            filteredToolNames.remove("ask_execution");
        }

        // 构建API请求体
        Map<String, Object> apiRequest = new HashMap<>();
        String model = request.getModel();
        apiRequest.put("model", (model != null && !model.isEmpty()) ? model : deepSeekConfig.getDefaultModel());
        apiRequest.put("messages", historyMessages);
        apiRequest.put("stream", stream);
        // 添加温度参数以减少重复
        apiRequest.put(TEMPERATURE_FIELD, DEFAULT_TEMPERATURE);
        // 添加思考模式参数
        String thinkingMode = request.getThinkingMode();
        apiRequest.put("thinking_mode", (thinkingMode != null && !thinkingMode.isEmpty()) ? thinkingMode : deepSeekConfig.getThinkingMode());

        // 保存项目根目录到 API 请求中（不在发送给 API 的字段中，仅内部使用）
        String projectRoot = request.getProjectRoot();
        if (projectRoot != null && !projectRoot.isEmpty()) {
            apiRequest.put("_projectRoot", projectRoot);
        }

        // 保存执行模式到 API 请求中（内部使用，供工具执行时检查权限）
        if (executionMode != null && !executionMode.isEmpty()) {
            apiRequest.put("_executionMode", executionMode);
        }

        // 保存 Git 提交模式到 API 请求中
        String gitCommitMode = request.getGitCommitMode();
        if (gitCommitMode != null && !gitCommitMode.isEmpty()) {
            apiRequest.put("_gitCommitMode", gitCommitMode);
            // 注入 Git 提交模式指令到系统提示词
            String gitInstruction;
            if ("manual".equals(gitCommitMode)) {
                gitInstruction = "\n\nGit 提交模式：手动。调用 git_commit 工具时不会直接提交，而是将待提交信息发送到前端由用户确认后执行";
            } else if ("none".equals(gitCommitMode)) {
                gitInstruction = "\n\nGit 提交模式：无。当前模式已禁用 Git 提交功能，请勿调用 git_commit 工具";
            } else {
                gitInstruction = "\n\nGit 提交模式：自动。调用 git_commit 工具时直接执行提交操作";
            }
            for (Map<String, Object> msg : historyMessages) {
                if ("system".equals(msg.get("role"))) {
                    String existing = (String) msg.get("content");
                    msg.put("content", existing + gitInstruction);
                    break;
                }
            }
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

        log.debug("调用DeepSeek API{}接口，会话ID: {}, 消息长度: {}, 用户ID: {}",
                stream ? "流式" : "非流式", conversationId, userMessage.length(), userId);

        return new ConversationContext(conversationId, apiRequest, toolNames, storageConversationId);
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

        if (hasTools) {
            // 有工具定义，执行半流式工具调用循环
            return executeSemiStreamingToolCycle(conversationId, storageConversationId, apiRequest);
        } else {
            // 没有工具定义，使用原有流式逻辑
            // 收集响应内容，用于保存助手消息
            StringBuilder responseBuilder = new StringBuilder();

            // 创建主Flux处理API响应
            return webClient.post()
                    .uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(apiRequest)
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
                        return !trimmed.isEmpty() && !trimmed.equals("[DONE]");
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
                        // 尝试从流式响应中解析content和reasoning
                        Map<String, String> extracted = extractContentAndReasoningFromStreamResponse(fullResponse);
                        String content = extracted.get("content");
                        String reasoning = extracted.get("reasoning");
                        if (content == null || content.isEmpty()) {
                            content = fullResponse;
                        }
                        saveAssistantMessage(storageConversationId, content, reasoning);
                        log.debug("流式调用完成，保存助手消息，content长度: {}, reasoning长度: {}",
                                content != null ? content.length() : 0, reasoning != null ? reasoning.length() : 0);
                    });
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
        // 构建初始消息列表
        List<Map<String, Object>> messages = new ArrayList<>();
        List<Map<String, Object>> historyMessages = buildMessagesFromHistory(conversationId);
        for (Map<String, Object> msg : historyMessages) {
            Map<String, Object> mutableMsg = new HashMap<>(msg);
            messages.add(mutableMsg);
        }

        // 使用递归函数处理工具调用循环
        return handleToolCallIteration(conversationId, storageConversationId, initialApiRequest, messages, 0, MAX_TOOL_CALL_ITERATIONS);
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
            log.warn("达到最大工具调用迭代次数 {}", maxIterations);
            return Flux.error(new RuntimeException("工具调用达到最大迭代次数限制，请简化请求或分批执行"));
        }

        log.debug("半流式工具调用循环迭代 {}，消息数量: {}", iteration + 1, messages.size());

        // 构建当前迭代的API请求
        Map<String, Object> apiRequest = new HashMap<>(initialApiRequest);
        apiRequest.put("messages", messages);

        // 始终使用流式请求
        return handleStreamingPhase(conversationId, storageConversationId, apiRequest, messages, iteration, maxIterations);
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
     * @return SSE格式字符串
     */
    private String createToolCallStartEvent() {
        return createReasoningSSEEvent("正在调用工具...");
    }

    /**
     * 格式化工具调用结果
     * 格式：前后各加两个换行符，前面拼接虚线包裹的"工具调用:"字符串
     * 虚线长度自适应：每边54个'-'，配合12px字体和800px框体宽度
     * @param toolResult 原始工具调用结果
     * @return 格式化后的工具调用结果
     */
    private String formatToolResult(String toolResult) {
        // 每边54个'-'，配合12px字体和800px框体宽度
        String dashLine = "-".repeat(48);
        String header = dashLine + "工具调用:" + dashLine;

        if (toolResult == null || toolResult.isEmpty()) {
            return "\n\n" + header + "\n\n\n";
        }
        return "\n\n" + header + "\n" + toolResult + "\n\n";
    }

    /**
     * 创建工具调用结果的SSE事件（以 thinking 事件发送，前端按标记折叠展示）
     */
    private String createToolResultEvent(String toolResult) {
        return createReasoningSSEEvent(formatToolResult(toolResult));
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

        return webClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(apiRequest)
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
                    return !trimmed.isEmpty() && !trimmed.equals("[DONE]");
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
                                    String toolCallStartEvent = createToolCallStartEvent();
                                    log.debug("发送工具调用开始事件");

                                    // 执行工具调用（先设置项目根目录和工具执行上下文）
                                    String currentProjectRoot = (String) apiRequest.get("_projectRoot");
                                    String currentExecutionMode = (String) apiRequest.get("_executionMode");
                                    String currentGitCommitMode = (String) apiRequest.get("_gitCommitMode");
                                    if (currentProjectRoot != null && !currentProjectRoot.isEmpty()) {
                                        ProjectRootContext.set(currentProjectRoot);
                                    }
                                    ToolContext.set(currentExecutionMode, conversationId);
                                    if (currentGitCommitMode != null && !currentGitCommitMode.isEmpty()) {
                                        ToolContext.setGitCommitMode(currentGitCommitMode);
                                    }
                                    List<ToolExecutor.ToolCallResult> toolResults;
                                    try {
                                        toolResults = toolExecutor.executeToolCalls(completeToolCalls);
                                    } finally {
                                        ProjectRootContext.clear();
                                        ToolContext.clear();
                                    }
                                    log.debug("工具调用执行完成，结果数量: {}", toolResults.size());

                                    // 检查是否有 ask_user 问题
                                    ToolExecutor.ToolCallResult askUserResult = findAskUserResult(toolResults);
                                    if (askUserResult != null) {
                                        // 处理 ask_user 流程：暂停等待用户回答
                                        return handleAskUserFlow(askUserResult, toolResults, completeToolCalls,
                                                messages, toolCallStartEvent, contentCollector.toString(), reasoning,
                                                conversationId, storageConversationId, apiRequest, iteration, maxIterations);
                                    }

                                    // 检查是否有 pending_commit
                                    ToolExecutor.ToolCallResult pendingCommitResult = findPendingCommitResult(toolResults);
                                    boolean hasPendingCommit = false;
                                    String pendingCommitEvent = null;
                                    if (pendingCommitResult != null) {
                                        hasPendingCommit = true;
                                        pendingCommitEvent = createPendingCommitEvent(pendingCommitResult.getContent());
                                        log.debug("检测到 pending_commit 事件");
                                    }

                                    // 将工具调用消息添加到消息列表，包含之前收集的文本内容
                                    Map<String, Object> toolCallMessage = new HashMap<>();
                                    toolCallMessage.put("role", "assistant");
                                    // 只有当有文本内容时才添加content字段
                                    if (collectedContent != null && !collectedContent.isEmpty()) {
                                        toolCallMessage.put("content", collectedContent);
                                    }
                                    toolCallMessage.put("tool_calls", completeToolCalls);
                                    // DeepSeek 思考模式要求将 reasoning_content 传回
                                    if (reasoning != null && !reasoning.isEmpty()) {
                                        toolCallMessage.put("reasoning_content", reasoning);
                                    }
                                    messages.add(toolCallMessage);

                                    // 创建工具结果事件流
                                    List<String> toolResultEvents = new ArrayList<>();
                                    for (ToolExecutor.ToolCallResult toolResult : toolResults) {
                                        String toolResultEvent = createToolResultEvent(toolResult.getContent());
                                        toolResultEvents.add(toolResultEvent);
                                        log.debug("创建工具结果事件，内容长度: {}", toolResult.getContent() != null ? toolResult.getContent().length() : 0);
                                    }

                                    // 将工具结果消息添加到消息列表，并保存工具消息到数据库
                                    List<ObjectNode> toolMessages = toolExecutor.buildToolMessages(toolResults);
                                    for (ObjectNode toolMessage : toolMessages) {
                                        messages.add(objectMapper.convertValue(toolMessage, Map.class));
                                        // 保存工具消息到数据库，格式化工具调用结果
                                        String toolContent = toolMessage.path("content").asText("");
                                        String formattedToolContent = formatToolResult(toolContent);
                                        saveToolMessage(storageConversationId, formattedToolContent);
                                    }

                                    // 创建事件流：工具调用开始事件 + 所有工具结果事件 + 下一轮迭代
                                    Flux<String> toolCallEvents = Flux.just(toolCallStartEvent);
                                    for (String toolResultEvent : toolResultEvents) {
                                        toolCallEvents = toolCallEvents.concatWith(Flux.just(toolResultEvent));
                                    }

                                    // 如果有 pending_commit 事件，追加到流中
                                    if (hasPendingCommit && pendingCommitEvent != null) {
                                        toolCallEvents = toolCallEvents.concatWith(Flux.just(pendingCommitEvent));
                                    }

                                    // 继续下一轮迭代（使用非流式）
                                    Flux<String> nextIteration = handleToolCallIteration(conversationId, storageConversationId, apiRequest, messages, iteration + 1, maxIterations);
                                    return toolCallEvents.concatWith(nextIteration);
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
                        }
                    } catch (Exception e) {
                        log.debug("解析SSE事件失败，可能是不完整的JSON: {}", e.getMessage());
                        // 忽略解析错误，继续转发原始数据
                    }

                    // 如果正在累积工具调用但不完整，不转发事件
                    if (accumulatingToolCalls.get()) {
                        return Flux.empty();
                    }

                    // 没有检测到工具调用，转发原始事件
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
                                content = fullResponse;
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

    // ===== ask_user 相关方法 =====

    /**
     * 检查工具结果中是否有 ask_user 问题
     */
    private ToolExecutor.ToolCallResult findAskUserResult(List<ToolExecutor.ToolCallResult> toolResults) {
        for (ToolExecutor.ToolCallResult result : toolResults) {
            if (result.getContent() != null && result.getContent().startsWith("__QUESTION__:")) {
                return result;
            }
        }
        return null;
    }

    /**
     * 查找 pending_commit 结果
     */
    private ToolExecutor.ToolCallResult findPendingCommitResult(List<ToolExecutor.ToolCallResult> toolResults) {
        for (ToolExecutor.ToolCallResult result : toolResults) {
            if (result.getContent() != null && result.getContent().startsWith("__PENDING_COMMIT__:")) {
                return result;
            }
        }
        return null;
    }

    /**
     * 创建 pending_commit SSE 事件
     */
    private String createPendingCommitEvent(String content) {
        try {
            // 格式：__PENDING_COMMIT__:uuid:json
            int uuidStart = "__PENDING_COMMIT__:".length();
            String uuid = content.substring(uuidStart, uuidStart + 36);
            String jsonStr = content.substring(uuidStart + 37);

            ObjectNode event = objectMapper.createObjectNode();
            event.put("event", "pending_commit");
            event.put("uuid", uuid);

            // 解析 JSON 内容
            JsonNode data = objectMapper.readTree(jsonStr);
            event.set("data", data);

            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            log.error("创建 pending_commit 事件失败", e);
            return "{\"event\":\"pending_commit\",\"uuid\":\"error\",\"data\":{}}";
        }
    }

    /**
     * 创建 ask_user SSE 事件
     */
    private String createAskUserEvent(String uuid, String question) {
        try {
            ObjectNode event = objectMapper.createObjectNode();
            event.put("event", "ask_user");
            event.put("uuid", uuid);
            event.put("question", question);
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

        // 解析 ask_user 标记：__QUESTION__:uuid:question
        String content = askUserResult.getContent();
        int uuidStart = "__QUESTION__:".length();
        String uuid = content.substring(uuidStart, uuidStart + 36);
        String question = content.substring(uuidStart + 37);

        // 保存待处理问题
        com.example.agentdeepseek.model.dto.PendingQuestion pq =
                new com.example.agentdeepseek.model.dto.PendingQuestion(uuid, question);
        pendingQuestionStore.put(uuid, pq);

        log.info("ask_user 等待回答: uuid={}, question={}", uuid, question);

        // 创建 ask_user SSE 事件
        String questionEvent = createAskUserEvent(uuid, question);

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
                                String formattedToolContent = formatToolResult(toolContent);
                                saveToolMessage(storageConversationId, formattedToolContent);
                            }

                            // 授予执行令牌（manual模式下，用户同意后受限工具获得一次执行权限）
                            executionTokenManager.grant(conversationId);

                            // 发送恢复事件，然后继续工具循环
                            return Flux.just(createResumeEvent())
                                    .concatWith(handleToolCallIteration(
                                            conversationId, storageConversationId, apiRequest,
                                            messages, iteration + 1, maxIterations));
                        })
                        .timeout(java.time.Duration.ofMinutes(5))
                        .onErrorResume(e -> {
                            pendingQuestionStore.remove(uuid);
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

}