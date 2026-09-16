package com.example.agentdeepseek.service.files;

import lombok.Getter;

/**
 * Files API 统一异常
 * <p>
 * 远端 4xx/5xx 与本地/网络错误统一转换为此异常：
 * <ul>
 *   <li>{@code code}：HTTP 状态码（0 表示本地/网络错误）</li>
 *   <li>{@code retryable}：429/5xx 为 true（WebClient 层已自动指数退避重试，此标记供上层业务判断）</li>
 * </ul>
 */
@Getter
public class FilesApiException extends RuntimeException {

    /** HTTP 状态码（0 = 本地/网络错误） */
    private final int code;

    /** 是否可重试（429 / 5xx） */
    private final boolean retryable;

    public FilesApiException(int code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public FilesApiException(int code, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }
}
