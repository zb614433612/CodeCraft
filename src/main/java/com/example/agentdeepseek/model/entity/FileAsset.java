package com.example.agentdeepseek.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文件资产实体（对齐远端 Files API 文件，永久保存）
 * <p>
 * file_asset 表映射：文件本体记录，与"被引用关系"（file_reference）分离。
 * 状态机：active（正常）→ orphan（远端删除失败待补偿）/ deleted（已删除）。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FileAsset {

    /** 本地主键 */
    private Long id;

    /** 远端 Files API 文件ID（file-api-...） */
    private String fileId;

    /** 所属 LLM Provider code */
    private String providerCode;

    /** 上传用户ID */
    private Long userId;

    /** 文件名（≤512） */
    private String filename;

    /** 本地显示名（重命名用，可空；空时显示 filename） */
    private String displayName;

    /** MIME（由内容魔数判定，如 image/png） */
    private String mimeType;

    /** 字节数 */
    private Long size;

    /** 本地副本文件名（位于 file-asset.store.dir 目录内，由 FileAssetStore 负责目录拼接） */
    private String localPath;

    /** 存储类型：cloud=云端 Files API（图片）/ local=本地存储（文档） */
    private String storageType;

    /** 来源：upload/paste */
    private String source;

    /** 状态：active/deleted/orphan */
    private String status;

    /** orphan 补偿重试次数（远端删除失败累计；超阈值保留告警，P3） */
    private Integer orphanRetryCount;

    /** 远端过期时间（永久策略下恒为 null，字段保留兼容） */
    private LocalDateTime expiresAt;

    /** 上传时间 */
    private LocalDateTime createdAt;
}
