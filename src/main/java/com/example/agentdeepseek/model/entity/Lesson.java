package com.example.agentdeepseek.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 踩坑经验实体类
 * 记录 LLM 执行过程中的失败现象与解法，支持按项目/工具/错误码确定性检索。
 * 与 skill 不同：skill 是「工作流模板」，lesson 是「失败经验」，按需查询而非常驻注入。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Lesson {

    /** 状态：草稿（自动捕获，待验证） */
    public static final int STATUS_DRAFT = 0;
    /** 状态：有效（被有效复用 ≥2 次后转正） */
    public static final int STATUS_ACTIVE = 1;
    /** 状态：隐藏（连续失败 ≥5 次，自动降级） */
    public static final int STATUS_HIDDEN = 2;

    /** 来源：自动捕获（ToolExecutor 失败钩子） */
    public static final String SOURCE_AUTO = "auto";
    /** 来源：LLM 主动记录（lesson action=record） */
    public static final String SOURCE_LLM = "llm";
    /** 来源：人工沉淀 */
    public static final String SOURCE_MANUAL = "manual";
    /** 来源：对话级复盘（C2，工具循环结束后 LLM 提炼，友好提示不抛异常也能捕获） */
    public static final String SOURCE_REVIEW = "review";

    /** 全局共享池项目标识：通用坑（与项目无关）记录到这里，所有项目检索时兜底可见 */
    public static final String GLOBAL_PROJECT_KEY = "__global__";

    /** 自动捕获草稿的占位解法（无真实解法时写入，检索时跳过注入、只提示补全） */
    public static final String PLACEHOLDER_SOLUTION = "(自动捕获，解法待 LLM 或人工补充)";

    /**
     * 是否有可用解法：非空、非占位符、非空白 → true
     * 用于区分「有解法的经验」（可注入指导 LLM）与「无解法的草稿」（只提示补全）
     */
    public boolean hasUsableSolution() {
        return solution != null
                && !solution.isBlank()
                && !PLACEHOLDER_SOLUTION.equals(solution.trim());
    }

    private Long id;
    /** 项目标识（隔离第一维度，取项目根目录名） */
    private String projectKey;
    /** 工具名：command / file_writer / mcp_server_manager ... */
    private String toolName;
    /** 错误类别：COMPILE / DEPENDENCY / NETWORK / AUTH / MCP_HANDSHAKE / SQL / PARAM / ENV / OTHER */
    private String errorCategory;
    /** 归一化错误码：NoClassDefFoundError / ECONNREFUSED / 401 ... */
    private String errorCode;
    /** 指纹：md5(projectKey|tool|category|code|paramsHash)，同坑去重 */
    private String errorSignature;
    /** 失败现象 */
    private String symptom;
    /** 根因 */
    private String rootCause;
    /** 解法（支持 {变量占位符}） */
    private String solution;
    /** 变量参数 JSON：[{"name":"缺失依赖","value":"starter-web"}] */
    private String paramsJson;
    /** 适用条件（自然语言约束） */
    private String applicableCond;
    /** 标签，空格分隔，兜底检索用 */
    private String keywords;
    /** 状态：0=草稿 1=有效 2=隐藏 */
    private Integer status;
    /** 来源：auto / llm / manual */
    private String source;
    /** 被检索命中次数 */
    private Integer hitCount;
    /** 应用后有效次数 */
    private Integer successCount;
    /** 应用后仍失败次数 */
    private Integer failCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
