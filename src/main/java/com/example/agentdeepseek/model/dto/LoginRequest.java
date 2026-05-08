package com.example.agentdeepseek.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录请求DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "登录请求参数")
public class LoginRequest {

    @Schema(description = "用户名", required = true, example = "admin")
    private String username;

    @Schema(description = "密码（前端二次加密：MD5(前端MD5(明文密码) + 随机码)）", required = true, example = "二次加密后的密码")
    private String password;
}