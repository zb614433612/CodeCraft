package com.example.agentdeepseek.model.vo;

import com.example.agentdeepseek.common.response.PageResult;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 文件资产分页结果（2026-09-21 文件管理页分页改造）
 *
 * <p>继承通用 {@link PageResult}（records/total/page/pageSize/totalPages），
 * 额外携带「占用空间合计」，供文件管理页头部统计显示——
 * 分页后列表只含当前页，头部「文件总数 / 占用空间」必须由后端全量统计提供（与列表同筛选口径）。</p>
 */
@Schema(description = "文件资产分页结果")
public class FilePageVO extends PageResult<FileAssetVO> {

    @Schema(description = "占用空间合计（字节；筛选条件下全部文件，不受分页影响）", example = "104857600")
    private long totalSize;

    public long getTotalSize() { return totalSize; }

    public void setTotalSize(long totalSize) { this.totalSize = totalSize; }
}
