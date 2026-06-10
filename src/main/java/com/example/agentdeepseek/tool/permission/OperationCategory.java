package com.example.agentdeepseek.tool.permission;

/**
 * 工具操作分类枚举
 */
public enum OperationCategory {
    READ,           // 文件读取与搜索（file_explorer）
    WRITE,          // 文件写入与修改（file_writer action=write/edit）
    DELETE,         // 文件删除（file_writer action=delete）
    EXECUTE,        // 命令执行（command action=exec/start）
    NETWORK,        // 网络请求（web_search, web_fetch, http_request, check_network）
    DATABASE,       // 数据库操作（execute_sql）
    GIT,            // Git 操作（git_query / git_submit / git_branch）
    SERVICE,        // 服务管理（command action=list/logs/stop）
    SKILL,          // 技能管理（skill: create/update/delete/list/report）
    COMMUNICATION,  // 用户交互（ask_clarification）
    ADMIN           // 管理操作（agent / task_manager）
}
