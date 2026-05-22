package com.example.agentdeepseek.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 收集子Agent结果请求参数
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CollectAgentRequest {
    /** 子Agent ID */
    private String agentId;

    /** 等待超时秒数，默认120 */
    private Integer timeout;
}
