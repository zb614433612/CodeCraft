package com.example.agentdeepseek.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 云端文件条目（Files API 远端文件 + 本地登记状态；2026-09-21 云端文件视图）
 *
 * <p>数据来源为远端 Files API 列表（Provider 账号级），与本地 file_asset 记录比对后标注：
 * 「已登记」= 本地存在同 provider+fileId 记录；「仅云端」= 本地无记录
 * （如软件重装/数据清理后），是云端孤儿的主要形态，可在此删除清理。</p>
 */
@Data
@Schema(description = "云端文件条目（远端文件 + 本地登记状态）")
public class CloudFileVO {

    @Schema(description = "远端 Files API 文件ID", example = "file-api-0a1b2c3d4e5f60718293a4b5c6d7e8f9")
    private String fileId;

    @Schema(description = "文件名", example = "screenshot.png")
    private String filename;

    @Schema(description = "文件大小（字节）", example = "102400")
    private Long bytes;

    @Schema(description = "创建时间（Unix 秒）", example = "1700000000")
    private Long createdAtEpoch;

    @Schema(description = "远端过期时间（Unix 秒；null=永久保存）", example = "null")
    private Long expiresAtEpoch;

    @Schema(description = "是否已在本地登记（file_asset 存在同 provider+fileId 记录）", example = "true")
    private Boolean linked;

    @Schema(description = "本地资产ID（linked=true 时为对应记录，供「在列表中查看」；否则 null）", example = "1")
    private Long linkedAssetId;

    @Schema(description = "本地显示名（linked=true 时为本地重命名后的显示名；否则 null）", example = "架构图")
    private String linkedDisplayName;

    @Schema(description = "本地记录状态（active/orphan；linked=false 时 null）", example = "active")
    private String linkedStatus;
}
