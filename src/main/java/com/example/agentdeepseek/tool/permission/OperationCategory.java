package com.example.agentdeepseek.tool.permission;

/**
 * 工具操作分类枚举
 */
public enum OperationCategory {
    READ,           // 读取（read_file, glob_files, grep_search 等）
    WRITE,          // 写入文件（write_file, edit_file）
    DELETE,         // 删除（delete_file）
    EXECUTE,        // 命令执行（run_command, run_server）
    NETWORK,        // 网络请求（web_search, http_request）
    DATABASE,       // 数据库操作（execute_sql）
    GIT,            // Git 操作（全部 git_xxx）
    SERVICE,        // 服务管理（service_control）
    SKILL,          // 技能管理（manage_skill, report_skill_result）
    COMMUNICATION,  // 用户交互（ask_clarification）
    ADMIN           // 管理操作
}
