package com.example.agentdeepseek.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 云端文件删除结果（2026-09-21 云端文件视图）
 *
 * <p>远端删除幂等（目标不存在视为成功）；本地若存在同 provider+fileId 记录则联动清理
 * （引用 + 记录 + 本地副本），保证云端不留孤儿、本地不留悬空记录。</p>
 */
@Data
@Schema(description = "云端文件删除结果")
public class CloudFileDeleteResultVO {

    @Schema(description = "被删除的远端文件ID", example = "file-api-0a1b2c3d4e5f60718293a4b5c6d7e8f9")
    private String fileId;

    @Schema(description = "远端是否已删除（幂等：目标不存在也视为 true）", example = "true")
    private Boolean deleted;

    @Schema(description = "是否联动清理了本地记录", example = "true")
    private Boolean localRecordDeleted;

    @Schema(description = "联动清理的本地记录数", example = "1")
    private Integer localRecordCount;
}
