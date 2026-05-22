package com.example.agentdeepseek.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 查看子Agent详情请求参数
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InspectAgentRequest {
    /** 子Agent ID */
    private String agentId;

    /** 查看范围：diff / thinking / calls / full_log */
    private String scope;
}
