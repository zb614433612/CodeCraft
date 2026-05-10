package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 询问用户工具 —— 用于澄清需求
 * 当需求模糊、需要用户做决策时调用此工具询问用户。
 * 不受自动/手动开关控制，任何时候都加载。
 */
@Slf4j
@Component
public class AskClarificationTool implements Tool {

    /** 返回格式前缀，区别于 ask_execution 的 __ASK_EXECUTION__ */
    static final String RESULT_PREFIX = "__ASK_CLARIFICATION__:";
    /** question 最大长度，防止 LLM 传入异常超长内容 */
    private static final int MAX_QUESTION_LENGTH = 2000;

    private final ObjectMapper objectMapper;

    public AskClarificationTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "ask_clarification";
    }

    @Override
    public String getDescription() {
        return "向用户提问以澄清需求或征求意见。当需求模糊、信息不足、有多个方案需要用户决策时，调用此工具询问用户。"
                + "注意：区别于 ask_execution（询问是否执行操作），本工具用于获取用户对需求的补充说明或决策";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode question = objectMapper.createObjectNode();
        question.put("type", "string");
        question.put("description", "要向用户提出的问题，清晰描述需要用户确认或选择的内容");
        properties.set("question", question);

        parameters.set("properties", properties);
        parameters.putArray("required").add("question");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        // 防御：上游可能传入 null
        if (arguments == null) {
            log.warn("ask_clarification: arguments 为 null");
            return RESULT_PREFIX + "error:arguments 为 null，无法提取 question";
        }

        String question = arguments.path("question").asText();
        if (question.isEmpty()) {
            log.warn("ask_clarification: question 缺失或为空");
            return RESULT_PREFIX + "error:缺少必要参数 question";
        }

        // 长度限制：截断过长内容，防止 SSE 传输和存储问题
        if (question.length() > MAX_QUESTION_LENGTH) {
            log.warn("ask_clarification: question 超长({}), 已截断至 {}", question.length(), MAX_QUESTION_LENGTH);
            question = question.substring(0, MAX_QUESTION_LENGTH) + "...";
        }

        String uuid = UUID.randomUUID().toString();
        log.info("ask_clarification: uuid={}, question={}", uuid, question);

        return RESULT_PREFIX + uuid + ":" + question;
    }
}
