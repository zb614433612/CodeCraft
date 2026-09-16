package com.example.agentdeepseek.tool.permission;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 无副作用预检工具（授权层钩子）。
 *
 * <p>实现方声明"本次调用可被判定为不产生副作用的预检"（如 desktop_control 的坐标预检拦截：
 * 仅生成校验图、不执行任何键鼠动作），{@link ToolExecutionPipeline} 据此免授权直接执行——
 * 避免"预检轮 + 执行轮"两次授权弹窗。</p>
 *
 * <p><b>契约</b>：实现必须保证"返回 true 的调用"在工具执行时确实不会产生副作用
 * ——判定与执行路径的拦截逻辑必须严格同源（同一判定函数、同一输入状态），否则将出现
 * "未授权即执行"的安全漏洞。判定不确定/异常时必须返回 false（走正常授权）。</p>
 */
public interface SideEffectFreePreflight {

    /**
     * @param arguments 本次调用的原始参数
     * @return true = 本次调用为无副作用预检，可免授权执行；false = 走正常授权流程
     */
    boolean isSideEffectFreePreflight(JsonNode arguments);
}
