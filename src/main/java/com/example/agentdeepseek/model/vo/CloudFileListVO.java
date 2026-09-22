package com.example.agentdeepseek.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 云端文件列表结果（2026-09-21 云端文件视图）
 *
 * <p>汇总当前 Provider 账号下云端文件清单（游标循环全量拉取）与本地比对统计。
 * {@code truncated=true} 表示云端文件数超过服务端拉取上限（10 页 × 1000 条），仅返回前 N 条。</p>
 */
@Data
@Schema(description = "云端文件列表结果（含本地比对统计）")
public class CloudFileListVO {

    @Schema(description = "实际查询的 Provider code", example = "deepseek")
    private String providerCode;

    @Schema(description = "云端文件总数（本次返回条数；truncated=true 时为上限值）", example = "26")
    private Integer total;

    @Schema(description = "已登记数（本地存在记录）", example = "20")
    private Integer linkedCount;

    @Schema(description = "仅云端数（本地无记录，可清理）", example = "6")
    private Integer cloudOnlyCount;

    @Schema(description = "仅云端文件占用空间合计（字节）", example = "1048576")
    private Long cloudOnlySize;

    @Schema(description = "是否因单次拉取上限而截断（true=云端文件数超上限，仅展示部分）", example = "false")
    private Boolean truncated;

    @Schema(description = "云端文件列表（最新在前）")
    private List<CloudFileVO> files = new ArrayList<>();
}
