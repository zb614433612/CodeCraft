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
        String question = arguments.path("question").asText();
        if (question.isEmpty()) {
            return "错误：缺少必要参数 question";
        }

        String uuid = UUID.randomUUID().toString();
        log.info("ask_execution: uuid={}, question={}", uuid, question);

        return "__QUESTION__:" + uuid + ":" + question;
    }
}
