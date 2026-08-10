package com.example.agentdeepseek.util;

/**
 * 项目根目录上下文
 * 用于在工具执行期间传递用户选择的项目根目录。
 * 工具执行是同步的且在单一事件循环线程中完成，ThreadLocal 安全可用。
 */
public class ProjectRootContext {

    private static final ThreadLocal<String> currentProjectRoot = new ThreadLocal<>();

    /**
     * 设置当前请求的项目根目录
     */
    public static void set(String path) {
        currentProjectRoot.set(path);
    }

    /**
     * 获取当前项目根目录，如果未设置则返回系统属性 user.dir
     */
    public static String get() {
        String root = currentProjectRoot.get();
        return root != null && !root.isEmpty() ? root : System.getProperty("user.dir");
    }

    /**
     * 当前线程是否已显式设置过项目根目录
     * 用于调用方在临时修改后恢复原值（避免误把 get() 的 user.dir 兜底值当"原值"写回）
     */
    public static boolean isSet() {
        return currentProjectRoot.get() != null;
    }

    /**
     * 清除当前线程的项目根目录
     */
    public static void clear() {
        currentProjectRoot.remove();
    }
}
