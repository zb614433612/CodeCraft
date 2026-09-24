package com.example.agentdeepseek.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 执行 SQL 请求（管理页「执行 SQL」）
 */
@Data
@Schema(description = "执行 SQL 请求参数")
public class SqlExecuteRequest {

    @Schema(description = "SQL 语句（支持多条，以分号分隔按顺序执行；单条 SELECT 自动追加 LIMIT 保护）",
            required = true,
            example = "INSERT INTO t (name) VALUES ('a'); INSERT INTO t (name) VALUES ('b');")
    private String sql;

    @Schema(description = "每个查询结果集的最大返回行数（可选，默认 200，上限 1000）", example = "200")
    private Integer maxRows;
}
