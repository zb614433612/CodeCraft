package com.example.agentdeepseek.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文件引用关联实体
 * <p>
 * file_reference 表映射：记录文件被哪些会话/消息引用，
 * 支撑历史消息回显（按 messageId 映射）、删除会话时"引用归零"级联清理、跨会话引用保护。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FileReference {

    /** 本地主键 */
    private Long id;

    /** 关联 file_asset.id */
    private Long fileAssetId;

    /** 引用所在会话 */
    private Long conversationId;

    /** 引用所在用户消息ID（发送时回填；供历史消息回显） */
    private Long messageId;

    /** 引用建立时间 */
    private LocalDateTime createdAt;
}
