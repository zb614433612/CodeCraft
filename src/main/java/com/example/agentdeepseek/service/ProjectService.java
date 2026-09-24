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
     * 获取指定目录下的子目录（和文件）列表
     * @param parentPath 父目录路径
     * @param includeFiles 是否包含文件，默认 false
     */
    List<DirectoryEntry> listChildren(String parentPath, boolean includeFiles);

    /**
     * 读取文件内容
     * @param filePath 文件路径（相对于项目根目录或绝对路径）
     * @return 文件内容
     */
    String readFile(String filePath);

    /**
     * 读取文件原始字节（用于图片等二进制文件的预览）
     * @param filePath 文件路径（相对于项目根目录或绝对路径）
     * @return 文件字节内容
     */
    byte[] readFileBytes(String filePath);

    /**
     * 写入文件内容
     * @param filePath 文件路径（相对于项目根目录或绝对路径）
     * @param content 文件内容
     */
    void writeFile(String filePath, String content);

    /**
     * 将"不完整路径"（如 LLM 输出时省略前缀的短路径 api/appearance.ts）解析为项目内真实文件的相对路径候选。
     * 匹配规则：文件相对路径与 partialPath 全等，或以 "/partialPath" 结尾；自动跳过 .git、node_modules、target 等排除目录。
     *
     * @param rootPath    项目根目录（绝对路径；为空时回退 ProjectRootContext / user.dir）
     * @param partialPath 待解析的不完整路径（相对路径形式）
     * @return 候选文件的相对路径列表（/ 分隔；精确匹配在前，最多 50 个）
     */
    List<String> resolveFilePath(String rootPath, String partialPath);
}
