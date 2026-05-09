package com.example.agentdeepseek.service;

import com.example.agentdeepseek.model.vo.DirectoryEntry;
import com.example.agentdeepseek.model.vo.ProjectTreeNode;

import java.util.List;

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

    /**
     * 获取系统所有盘符/根目录
     * Windows 下返回 C:/, D:/, 等；Linux/Mac 返回 /
     */
    List<DirectoryEntry> listDrives();

    /**
     * 获取指定目录下的子目录列表
     * @param parentPath 父目录路径
     */
    List<DirectoryEntry> listChildren(String parentPath);
}
