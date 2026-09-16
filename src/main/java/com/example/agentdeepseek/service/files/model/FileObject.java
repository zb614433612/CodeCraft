package com.example.agentdeepseek.service.files.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Files API 文件对象（跨厂商归一化）
 * <p>
 * 各厂商字段差异在客户端内归一化为此模型：
 * <ul>
 *   <li>id / file_id → fileId</li>
 *   <li>bytes / size_bytes → bytes</li>
 *   <li>created_at（Unix 秒 / RFC3339 字符串）→ createdAtEpoch（Unix 秒）</li>
 *   <li>expires_at 缺省（永久保存）→ expiresAtEpoch 为 null</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FileObject {

    /** 远端 Files API 文件 ID（如 file-api-xxxxxxxx） */
    private String fileId;

    /** 文件名 */
    private String filename;

    /** 文件字节数 */
    private Long bytes;

    /** 创建时间（Unix 秒） */
    private Long createdAtEpoch;

    /** 用途（OpenAI 风格固定为 user_data） */
    private String purpose;

    /** 远端过期时间（Unix 秒）；永久保存策略下为 null */
    private Long expiresAtEpoch;
}
