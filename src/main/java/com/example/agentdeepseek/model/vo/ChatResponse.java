package com.example.agentdeepseek.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天响应VO
 * 用于返回聊天结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "聊天响应结果")
public class ChatResponse {

    @Schema(description = "响应内容", example = "你好！我是DeepSeek助手。")
    private String response;

    @Schema(description = "响应状态", example = "success")
    private String status = "success";

    @Schema(description = "时间戳", example = "1640995200000")
    private long timestamp = System.currentTimeMillis();

    /**
     * 快速创建成功响应
     */
    public static ChatResponse success(String response) {
        return new ChatResponse(response, "success", System.currentTimeMillis());
    }

    /**
     * 快速创建失败响应
     */
    public static ChatResponse error(String response) {
        return new ChatResponse(response, "error", System.currentTimeMillis());
    }
}