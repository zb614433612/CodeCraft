package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 询问用户工具 —— 用于确认执行
 * 询问用户是否执行代码修改或运行命令。
 * 受自动/手动开关控制：手动模式下加载，自动模式下不加载。
 */
@Slf4j
@Component
public class AskExecutionTool implements Tool {

    /** 返回格式前缀，区别于 ask_clarification 的 __ASK_CLARIFICATION__ */
    static final String RESULT_PREFIX = "__ASK_EXECUTION__:";
    /** question 最大长度，防止 LLM 传入异常超长内容 */
    private static final int MAX_QUESTION_LENGTH = 2000;

    private final ObjectMapper objectMapper;

    public AskExecutionTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "ask_execution";
    }

    @Override
    public String getDescription() {
        return "询问用户是否执行代码修改或运行命令。在执行文件写入、代码编辑、命令运行等可能产生副作用的操作前，调用此工具获得用户确认。"
                + "注意：区别于 ask_clarification（澄清需求），本工具仅用于征求用户对具体操作的执行许可";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode question = objectMapper.createObjectNode();
        question.put("type", "string");
        question.put("description", "要向用户提出的问题，说明要执行的操作，请用户确认是否执行");
        properties.set("question", question);

        parameters.set("properties", properties);
        parameters.putArray("required").add("question");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        // 防御：上游可能传入 null
        if (arguments == null) {
            log.warn("ask_execution: arguments 为 null");
            return RESULT_PREFIX + "error:arguments 为 null，无法提取 question";
        }

        String question = arguments.path("question").asText();
        if (question.isEmpty()) {
            log.warn("ask_execution: question 缺失或为空");
            return RESULT_PREFIX + "error:缺少必要参数 question";
        }

        // 长度限制：截断过长内容，防止 SSE 传输和存储问题
        if (question.length() > MAX_QUESTION_LENGTH) {
            log.warn("ask_execution: question 超长({}), 已截断至 {}", question.length(), MAX_QUESTION_LENGTH);
            question = question.substring(0, MAX_QUESTION_LENGTH) + "...";
        }

        String uuid = UUID.randomUUID().toString();
        log.info("ask_execution: uuid={}, question={}", uuid, question);

        return RESULT_PREFIX + uuid + ":" + question;
    }
}
