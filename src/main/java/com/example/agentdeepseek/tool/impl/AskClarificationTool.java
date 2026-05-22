package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;

/**
 * 询问用户工具 —— 用于澄清需求
 * 当需求模糊、需要用户做决策时调用此工具询问用户。
 * 不受自动/手动开关控制，任何时候都加载。
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.COMMUNICATION, description = "向用户提问")
public class AskClarificationTool implements Tool {

    /** 返回格式前缀 */
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
        return "向用户提问以澄清需求或征求意见。\n"
                + "【适用场景】需求模糊（如「帮我改一下那个页面」）、信息不足（如缺少文件路径或配置项）、多个可选方案需要用户决策时\n"
                + "【使用方式】将问题写入 question 参数，调用后等待用户回复再继续；一次只问一个核心问题，避免一次抛出多个问题\n"
                + "【注意】本工具不执行实际操作，仅用于获取用户补充说明或确认决策";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode question = objectMapper.createObjectNode();
        question.put("type", "string");
        question.put("description", "【必填】向用户提出的问题。示例：\"当前有3个API方案（RESTful/GraphQL/WebSocket），请选择一个继续实现\"。建议用选项编号让用户直接回复数字更高效");
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
            return RESULT_PREFIX + "error:【参数错误】arguments 为 null，请传入包含 question 字段的 JSON 对象，示例：{\"question\": \"请选择方案A还是方案B？\"}";
        }

        String question = arguments.path("question").asText();
        if (question.isEmpty()) {
            log.warn("ask_clarification: question 缺失或为空");
            return RESULT_PREFIX + "error:【缺少参数】question 字段缺失或为空。请提供具体问题，示例：{\"question\": \"需要修改哪个文件？\"}";
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
