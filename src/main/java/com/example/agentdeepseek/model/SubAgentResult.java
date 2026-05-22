package com.example.agentdeepseek.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 子Agent执行结果模型（三层结构）
 * <p>
 * 第一层（必需层）：状态、摘要、文件清单、编译结果、关键决策
 * 第二层（可选层）：试错记录、意外发现、技术债务
 * 第三层（按需层）：完整diff、thinking日志、工具调用历史（通过 inspect_agent 获取）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubAgentResult {

    // ==================== 第一层：必需层 ====================

    /** 子Agent ID */
    private String agentId;

    /** 状态：completed / failed / timeout */
    private String status;

    /** 执行摘要（2-3句话） */
    private String summary;

    /** 修改的文件列表 */
    private List<String> modifiedFiles;

    /** 新建的文件列表 */
    private List<String> createdFiles;

    /** 删除的文件列表 */
    private List<String> deletedFiles;

    /** 编译结果：passed / failed / not_run */
    private String compileResult;

    /** 关键决策记录：每个分支点的选择和理由 */
    private List<String> keyDecisions;

    /** 实际使用迭代次数 */
    private int iterationsUsed;

    /** 错误信息（failed/timeout时填写） */
    private String errorMessage;

    // ==================== 第二层：可选层 ====================

    /** 试错记录：尝试过但放弃的方案及原因 */
    private List<String> trialRecords;

    /** 意外发现：过程中发现的现有代码问题 */
    private List<String> unexpectedFindings;

    /** 技术债务：被迫留下的临时方案 */
    private List<String> techDebts;

    // ==================== 第三层：引用（按需获取） ====================

    /** 子Agent的完整消息历史（用于inspect_agent查询） */
    private String fullMessagesJson;

    /** 子Agent的文件变更diff（用于inspect_agent查询） */
    private String fullDiff;

    /** 子Agent的完整工具调用日志（用于inspect_agent查询） */
    private String toolCallLog;

    // ==================== 工厂方法 ====================

    /**
     * 创建成功的结果
     */
    public static SubAgentResult success(String agentId, String summary) {
        SubAgentResult result = new SubAgentResult();
        result.setAgentId(agentId);
        result.setStatus("completed");
        result.setSummary(summary);
        result.setCompileResult("passed");
        result.setModifiedFiles(new ArrayList<>());
        result.setCreatedFiles(new ArrayList<>());
        result.setDeletedFiles(new ArrayList<>());
        result.setKeyDecisions(new ArrayList<>());
        result.setTrialRecords(new ArrayList<>());
        result.setUnexpectedFindings(new ArrayList<>());
        result.setTechDebts(new ArrayList<>());
        return result;
    }

    /**
     * 创建失败的结果
     */
    public static SubAgentResult failed(String agentId, String errorMessage) {
        SubAgentResult result = new SubAgentResult();
        result.setAgentId(agentId);
        result.setStatus("failed");
        result.setSummary("执行失败");
        result.setCompileResult("not_run");
        result.setErrorMessage(errorMessage);
        result.setModifiedFiles(new ArrayList<>());
        result.setCreatedFiles(new ArrayList<>());
        result.setDeletedFiles(new ArrayList<>());
        return result;
    }

    /**
     * 创建超时的结果
     */
    public static SubAgentResult timeout(String agentId, long timeoutSec) {
        SubAgentResult result = new SubAgentResult();
        result.setAgentId(agentId);
        result.setStatus("timeout");
        result.setSummary("执行超时（" + timeoutSec + "秒）");
        result.setCompileResult("not_run");
        result.setErrorMessage("等待超时 " + timeoutSec + " 秒");
        return result;
    }

    /**
     * 创建未找到的结果
     */
    public static SubAgentResult notFound(String agentId) {
        SubAgentResult result = new SubAgentResult();
        result.setAgentId(agentId);
        result.setStatus("not_found");
        result.setSummary("未找到子Agent");
        result.setCompileResult("not_run");
        result.setErrorMessage("agent_id '" + agentId + "' 不存在");
        return result;
    }

    // ==================== 格式化输出 ====================

    /**
     * 将结果格式化为文本，供主Agent消费
     * 第一层信息全部展示，第二层信息仅在非空时展示
     */
    public String toResultString() {
        StringBuilder sb = new StringBuilder();

        // 状态行
        String statusIcon;
        switch (status) {
            case "completed": statusIcon = "✅"; break;
            case "failed":    statusIcon = "❌"; break;
            case "timeout":   statusIcon = "⏰"; break;
            default:          statusIcon = "❓";
        }
        sb.append(statusIcon).append(" 子Agent「").append(agentId).append("」").append(getStatusText()).append("\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        // 执行摘要
        if (summary != null && !summary.isEmpty()) {
            sb.append("【执行摘要】").append(summary).append("\n");
        }

        // 文件变更
        boolean hasFileChanges = (createdFiles != null && !createdFiles.isEmpty())
                || (modifiedFiles != null && !modifiedFiles.isEmpty())
                || (deletedFiles != null && !deletedFiles.isEmpty());
        if (hasFileChanges) {
            int total = (createdFiles != null ? createdFiles.size() : 0)
                      + (modifiedFiles != null ? modifiedFiles.size() : 0)
                      + (deletedFiles != null ? deletedFiles.size() : 0);
            sb.append("【文件变更】共 ").append(total).append(" 个\n");
            if (createdFiles != null && !createdFiles.isEmpty()) {
                sb.append("  📄 新增：").append(String.join(", ", createdFiles)).append("\n");
            }
            if (modifiedFiles != null && !modifiedFiles.isEmpty()) {
                sb.append("  ✏️ 修改：").append(String.join(", ", modifiedFiles)).append("\n");
            }
            if (deletedFiles != null && !deletedFiles.isEmpty()) {
                sb.append("  🗑 删除：").append(String.join(", ", deletedFiles)).append("\n");
            }
        }

        // 编译结果
        sb.append("【编译结果】");
        if ("passed".equals(compileResult)) {
            sb.append("通过 ✅\n");
        } else if ("failed".equals(compileResult)) {
            sb.append("失败 ❌\n");
        } else {
            sb.append("未运行\n");
        }

        // 迭代次数
        sb.append("【迭代次数】").append(iterationsUsed).append(" 次\n");

        // 关键决策
        if (keyDecisions != null && !keyDecisions.isEmpty()) {
            sb.append("【关键决策】\n");
            for (String decision : keyDecisions) {
                sb.append("  • ").append(decision).append("\n");
            }
        }

        // ====== 第二层（仅在非空时展示） ======

        if (trialRecords != null && !trialRecords.isEmpty()) {
            sb.append("【试错记录】\n");
            for (String record : trialRecords) {
                sb.append("  • ").append(record).append("\n");
            }
        }

        if (unexpectedFindings != null && !unexpectedFindings.isEmpty()) {
            sb.append("【意外发现】\n");
            for (String finding : unexpectedFindings) {
                sb.append("  • ").append(finding).append("\n");
            }
        }

        if (techDebts != null && !techDebts.isEmpty()) {
            sb.append("【技术债务】\n");
            for (String debt : techDebts) {
                sb.append("  • ").append(debt).append("\n");
            }
        }

        // 错误信息
        if (errorMessage != null && !errorMessage.isEmpty()) {
            sb.append("【错误信息】").append(errorMessage).append("\n");
        }

        return sb.toString();
    }

    private String getStatusText() {
        switch (status) {
            case "completed": return "执行完毕";
            case "failed":    return "执行失败";
            case "timeout":   return "执行超时";
            case "not_found": return "不存在";
            default:          return "状态未知";
        }
    }
}
