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

    /**
     * 在项目内按关键字搜索文件（文件树面板的搜索框、聊天里路径解析失败时的兜底）。
     *
     * <p>匹配口径（忽略大小写，命中任一即可）：
     * <ul>
     *   <li>文件名包含关键字（如 {@code App} → {@code src/App.vue}）</li>
     *   <li>相对路径包含关键字（如 {@code utils/mark} → {@code src/utils/markdown.ts}）</li>
     * </ul>
     * 排序优先级：文件名全等 &gt; 文件名前缀 &gt; 文件名包含 &gt; 仅路径包含；同级按路径长度升序（浅层优先）。</p>
     *
     * <p>与 {@link #resolveFilePath} 的差异：后者要求「整段路径」是真实路径的后缀，用于确认唯一目标；
     * 本方法只做「模糊查找」，返回多个候选交给用户挑。两者共用同一套目录排除规则
     * （跳过 .git / node_modules / target 等），遍历量达到上限即停止，避免超大仓库拖慢接口。</p>
     *
     * @param rootPath 项目根目录（绝对路径；为空时回退 ProjectRootContext / user.dir）
     * @param keyword  关键字（空串返回空列表；超过 200 字符按前 200 字符处理）
     * @param limit    返回条数上限（有效区间 1~300，超出按边界收敛）
     * @return 匹配文件的相对路径列表（/ 分隔）
     */
    List<String> searchFiles(String rootPath, String keyword, int limit);
}
