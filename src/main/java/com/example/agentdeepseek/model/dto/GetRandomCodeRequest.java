package com.example.agentdeepseek.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 获取随机码请求DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "获取随机码请求参数")
public class GetRandomCodeRequest {

    @Schema(description = "用户名", required = true, example = "admin")
    private String username;
}