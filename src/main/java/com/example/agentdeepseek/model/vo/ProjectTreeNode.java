package com.example.agentdeepseek.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 项目文件树节点
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "项目文件树节点")
public class ProjectTreeNode {

    @Schema(description = "文件/目录名称")
    private String name;

    @Schema(description = "相对项目根目录的路径")
    private String path;

    @Schema(description = "是否为目录")
    private boolean directory;

    @Schema(description = "子节点（目录时才有）")
    private List<ProjectTreeNode> children;
}
