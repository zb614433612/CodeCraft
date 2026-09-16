package com.example.agentdeepseek.model.vo;

import com.example.agentdeepseek.model.entity.FileReference;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文件资产视图对象（上传/列表/查询响应）
 */
@Data
@Schema(description = "文件资产信息")
public class FileAssetVO {

    @Schema(description = "本地资产ID", example = "1")
    private Long id;

    @Schema(description = "远端 Files API 文件ID", example = "file-api-xxxxxxxx")
    private String fileId;

    @Schema(description = "文件名", example = "screenshot.png")
    private String filename;

    @Schema(description = "显示名（重命名后；空时前端显示 filename）", example = "架构图")
    private String displayName;

    @Schema(description = "MIME 类型", example = "image/png")
    private String mimeType;

    @Schema(description = "存储类型：cloud=云端 Files API（图片）/ local=本地存储（文档）", example = "cloud")
    private String storageType;

    @Schema(description = "字节数", example = "102400")
    private Long size;

    @Schema(description = "来源：upload/paste", example = "upload")
    private String source;

    @Schema(description = "状态：active/deleted/orphan", example = "active")
    private String status;

    @Schema(description = "所属 Provider code", example = "deepseek")
    private String providerCode;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "预览地址（本地副本）", example = "/api/files/1/preview")
    private String previewUrl;

    @Schema(description = "引用信息（withRefs=true 时返回；含会话/消息ID，供历史回显）")
    private List<FileReference> references;
}
