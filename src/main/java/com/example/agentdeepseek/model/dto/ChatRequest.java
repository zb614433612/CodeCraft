package com.example.agentdeepseek.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天请求DTO
 * 用于接收用户聊天消息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "聊天请求参数")
public class ChatRequest {

    @Schema(description = "用户消息内容", required = true, example = "你好，请介绍一下你自己")
    private String message;

    @Schema(description = "会话ID（可选），如果提供则继续该会话，否则创建新会话", example = "1")
    private Long sessionId;

    @Schema(description = "用户ID（服务器自动填充，无需客户端传递）", example = "1")
    private Long userId;

    @Schema(description = "提示词文件名称（可选），如果为空则使用默认的system_prompt.txt", example = "system_prompt.txt")
    private String promptFileName;

    @Schema(description = "执行模式：auto（自动执行，无需询问）/ manual（手动模式，执行前需询问用户）", example = "auto")
    private String executionMode;

    @Schema(description = "项目根目录（可选），编码助手的工作根目录，默认为启动目录", example = "E:/zbcode/agent-deepseek")
    private String projectRoot;

    @Schema(description = "模型名称（可选）：deepseek-v4-pro / deepseek-v4-flash，为空则使用服务端默认配置", example = "deepseek-v4-flash")
    private String model;

    @Schema(description = "思考模式（可选）：non-thinking（关闭）/ thinking（思考_high）/ thinking_max（思考_max），为空则使用服务端默认配置", example = "non-thinking")
    private String thinkingMode;

    @Schema(description = "是否重连到正在执行的后台任务（为true时忽略message等字段）", example = "false")
    private boolean reconnect;

    @Schema(description = "前端生成的 turnId，用于匹配快照（每次用户消息生成唯一ID）", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    private String turnId;

    @Schema(description = "Agent配置ID（可选），指定使用的自定义Agent配置", example = "1")
    private Long agentConfigId;

    @Schema(description = "上下文模式（可选）：full（全量注入）/ compact（精简历史工具调用和思考过程），为空则使用服务端默认配置", example = "compact")
    private String contextMode;
}