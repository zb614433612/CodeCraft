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
        String question = arguments.path("question").asText();
        if (question.isEmpty()) {
            return "错误：缺少必要参数 question";
        }

        String uuid = UUID.randomUUID().toString();
        log.info("ask_clarification: uuid={}, question={}", uuid, question);

        return "__QUESTION__:" + uuid + ":" + question;
    }
}
