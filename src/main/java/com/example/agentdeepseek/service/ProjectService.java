package com.example.agentdeepseek.service;

import com.example.agentdeepseek.model.vo.ProjectTreeNode;

/**
 * 项目文件树服务接口
 */
public interface ProjectService {

    /**
     * 获取项目文件树
     * @param rootPath 项目根目录路径，为空则使用当前项目目录
     * @param depth 递归深度，默认4
     * @return 根节点
     */
    ProjectTreeNode getProjectTree(String rootPath, int depth);
}
