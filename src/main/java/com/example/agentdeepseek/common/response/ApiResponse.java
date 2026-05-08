package com.example.agentdeepseek.common.response;

import com.example.agentdeepseek.common.enums.ResponseEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 统一API响应封装
 *
 * @param <T> 数据泛型
 */
@Data
@Schema(description = "统一API响应")
public class ApiResponse<T> {

    /**
     * 状态码
     */
    @Schema(description = "状态码", example = "200")
    private int code;

    /**
     * 消息
     */
    @Schema(description = "消息", example = "操作成功")
    private String message;

    /**
     * 响应数据
     */
    @Schema(description = "响应数据")
    private T data;

    /**
     * 时间戳
     */
    @Schema(description = "时间戳", example = "1678886400000")
    private long timestamp;

    public ApiResponse() {
        this.timestamp = System.currentTimeMillis();
    }

    public ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    public ApiResponse(ResponseEnum responseEnum, T data) {
        this.code = responseEnum.getCode();
        this.message = responseEnum.getMessage();
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 成功响应
     *
     * @param data 响应数据
     * @param <T>  数据类型
     * @return ApiResponse
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(ResponseEnum.SUCCESS, data);
    }

    /**
     * 成功响应（自定义消息）
     *
     * @param data    响应数据
     * @param message 自定义消息
     * @param <T>     数据类型
     * @return ApiResponse
     */
    public static <T> ApiResponse<T> success(T data, String message) {
        ApiResponse<T> response = new ApiResponse<>(ResponseEnum.SUCCESS, data);
        response.setMessage(message);
        return response;
    }

    /**
     * 失败响应
     *
     * @param responseEnum 响应枚举
     * @param <T>          数据类型
     * @return ApiResponse
     */
    public static <T> ApiResponse<T> error(ResponseEnum responseEnum) {
        return new ApiResponse<>(responseEnum, null);
    }

    /**
     * 失败响应（自定义消息）
     *
     * @param responseEnum 响应枚举
     * @param message      自定义消息
     * @param <T>          数据类型
     * @return ApiResponse
     */
    public static <T> ApiResponse<T> error(ResponseEnum responseEnum, String message) {
        ApiResponse<T> response = new ApiResponse<>(responseEnum, null);
        response.setMessage(message);
        return response;
    }

    /**
     * 失败响应（使用系统错误）
     *
     * @param <T> 数据类型
     * @return ApiResponse
     */
    public static <T> ApiResponse<T> error() {
        return new ApiResponse<>(ResponseEnum.ERROR, null);
    }

    /**
     * 失败响应（自定义错误码和消息）
     *
     * @param code    状态码
     * @param message 消息
     * @param <T>     数据类型
     * @return ApiResponse
     */
    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}