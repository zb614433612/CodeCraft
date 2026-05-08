package com.example.agentdeepseek.common.enums;

import lombok.Getter;

/**
 * 响应状态枚举
 * 定义统一的状态码和消息
 */
@Getter
public enum ResponseEnum {
    /**
     * 成功状态码
     */
    SUCCESS(200, "操作成功"),

    /**
     * 系统错误
     */
    ERROR(500, "系统错误"),

    /**
     * 请求参数错误
     */
    BAD_REQUEST(400, "请求参数错误"),

    /**
     * 资源不存在
     */
    NOT_FOUND(404, "资源不存在"),

    /**
     * 操作失败
     */
    OPERATION_FAILED(1001, "操作失败"),

    /**
     * 数据库操作失败
     */
    DATABASE_ERROR(1002, "数据库操作失败"),

    /**
     * 向量生成失败
     */
    EMBEDDING_ERROR(1003, "向量生成失败"),

    /**
     * 搜索失败
     */
    SEARCH_ERROR(1004, "搜索失败"),

    /**
     * 删除失败
     */
    DELETE_ERROR(1005, "删除失败"),

    /**
     * 插入失败
     */
    INSERT_ERROR(1006, "插入失败");

    /**
     * 状态码
     */
    private final int code;

    /**
     * 消息
     */
    private final String message;

    ResponseEnum(int code, String message) {
        this.code = code;
        this.message = message;
    }
}