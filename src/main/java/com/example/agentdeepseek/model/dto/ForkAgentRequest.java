package com.example.agentdeepseek.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 创建子Agent请求参数
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ForkAgentRequest {
    /** 子Agent唯一标识，如 sub-dal-1 */
    private String agentId;

    /** 子Agent名称 */
    private String name;

    /** 子Agent的完整任务描述 */
    private String instructions;

    /** 子Agent可用的工具列表 */
    private List<String> tools;

    /** 子Agent可用的技能列表（名称或ID） */
    private List<String> skills;

    /** 上下文继承模式：inherit_summary / inherit_full / none */
    private String contextMode;

    /** 最大迭代次数，默认30 */
    private Integer maxIterations;
}
