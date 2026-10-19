package com.example.agentdeepseek.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 子Agent执行记录（列表视图，只读）。
 *
 * <p>数据源：{@code sub_agent_log} 表（{@code AgentForkManager.forkAgent} 创建时 INSERT，
 * 执行结束 {@code updateLogResult} 更新状态/摘要/文件变更）。</p>
 *
 * <p><b>为什么需要这个 VO</b>：后端从未向前端推送过 {@code agent_event} 事件
 * （{@code AgentEventBus} 只有 ask_user，子Agent结果是以纯文本打进主Agent思考流的），
 * 所以子Agent面板没有实时事件通道可依赖。改为直接读库：fork 时即写入 running，
 * 结束时更新为 completed/failed，前端轮询即可看到状态流转，历史会话也能回放。</p>
 */
@Data
@Schema(description = "子Agent执行记录")
public class SubAgentVO {

    /** 记录主键 */
    private Long id;

    /** 子Agent唯一标识（主Agent fork 时指定，如 sub-dal-1） */
    private String agentId;

    /** 子Agent名称（用于面板展示） */
    private String name;

    /** 状态：running / completed / failed / timeout */
    private String status;

    /** 完整任务描述（主Agent 传给子Agent 的 instructions） */
    private String instructions;

    /** 上下文继承模式：inherit_summary / inherit_full / none */
    private String contextMode;

    /** 可用工具名列表（库中为 JSON 数组字符串，这里已解析） */
    private List<String> tools;

    /** 可用技能列表（库中为 JSON 数组字符串，这里已解析） */
    private List<String> skills;

    /** 结构化执行摘要（完成时写入） */
    private String summary;

    /** 编译结果：passed / failed / not_run */
    private String compileResult;

    /** 实际使用迭代次数 */
    private Integer iterationsUsed;

    /** 最大允许迭代次数 */
    private Integer maxIterations;

    /** 错误信息（failed / timeout 时写入） */
    private String errorMessage;

    /** 创建该子Agent的那条用户消息的 turnId（前端按此关联「哪一轮」） */
    private String parentTurnId;

    /** 新建的文件（完成时写入） */
    private List<String> createdFiles;

    /** 修改的文件（完成时写入） */
    private List<String> modifiedFiles;

    /** 删除的文件（完成时写入） */
    private List<String> deletedFiles;

    /** 创建时间（yyyy-MM-dd HH:mm:ss，服务端已格式化，避免前端解析时区差异） */
    private String createdAt;

    /** 完成时间（running 时为空） */
    private String completedAt;
}
