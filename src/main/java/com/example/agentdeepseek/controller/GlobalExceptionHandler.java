package com.example.agentdeepseek.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理器
 * 将框架层异常转换为前端可读的友好提示（如上传超限等）。
 *
 * <p>背景：文件上传超过 multipart 上限时，{@link MaxUploadSizeExceededException} 在
 * 请求解析阶段抛出（Controller 方法执行之前），Controller 内的 try-catch 无法捕获，
 * 此前只能由 DefaultHandlerExceptionResolver 兜底返回晦涩的默认错误。
 * 本处理器将其转换为与 /api/deepseek/upload 一致的 {success:false, error} 结构。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 单文件上传上限文本（与 application.yml 的 spring.servlet.multipart.max-file-size 保持一致） */
    @Value("${spring.servlet.multipart.max-file-size:1MB}")
    private String maxFileSizeText;

    /**
     * 上传文件超过大小上限。
     *
     * @param e 上传超限异常
     * @return 前端可直接展示的失败结构
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public Map<String, Object> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException e) {
        String limit = formatLimit(maxFileSizeText);
        log.warn("上传文件超过大小上限（最大 {}）: {}", limit, e.getMessage());
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("error", "文件大小超过上限（最大 " + limit + "）");
        return response;
    }

    /**
     * 将配置的大小文本（如 "64MB"）归一化为 MB 文案。
     *
     * @param sizeText DataSize 格式文本
     * @return 友好的大小文案
     */
    private String formatLimit(String sizeText) {
        try {
            DataSize size = DataSize.parse(sizeText);
            long megabytes = size.toMegabytes();
            if (megabytes >= 1) {
                return megabytes + "MB";
            }
            return size.toBytes() + "B";
        } catch (Exception ex) {
            return sizeText;
        }
    }
}
