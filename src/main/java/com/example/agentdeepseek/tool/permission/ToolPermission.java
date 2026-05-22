package com.example.agentdeepseek.tool.permission;

import java.lang.annotation.*;

/**
 * 三层权限防护注解，标记在 Tool 实现类上。
 *
 * <pre>
 * 层面一（数据影响）：manual 模式下 affectsData=true 的工具执行前弹窗授权
 * 层面二（路径穿透）：manual 模式下 isPathSensitive=true 且路径超出项目根目录时弹窗授权
 * 层面三（高危操作）：auto 模式下 highRisk=true 的工具也需弹窗授权
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ToolPermission {

    /** 操作分类 */
    OperationCategory category() default OperationCategory.READ;

    /**
     * 层面一：是否会影响到数据/代码
     * true → manual 模式下需要用户授权
     */
    boolean affectsData() default false;

    /**
     * 层面二：是否涉及文件路径（可能跨项目目录）
     * true → manual 模式下检查路径是否超出项目根目录
     */
    boolean isPathSensitive() default false;

    /**
     * 层面三：是否为高危操作
     * true → auto 模式下也需要用户授权
     */
    boolean highRisk() default false;

    /** 描述（用于审批弹窗的文案） */
    String description() default "";
}
