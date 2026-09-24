package com.example.agentdeepseek.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * SQL 执行结果（管理页「执行 SQL」与 AI 工具 execute_sql 共用）
 * <p>
 * 支持一次执行多条语句：results 按执行顺序每个语句一个结果块
 * （SELECT 给 columns/rows；写操作给 affectedRows）。
 * </p>
 */
@Data
@Schema(description = "SQL 执行结果")
public class SqlExecuteResult {

    @Schema(description = "目标库描述", example = "外部库[订单库] mysql@192.168.1.100:3306/orders")
    private String dbLabel;

    @Schema(description = "总耗时（毫秒）")
    private Long elapsedMs;

    @Schema(description = "实际执行的语句数")
    private Integer statementCount;

    @Schema(description = "各语句结果（按执行顺序）")
    private List<StatementResult> results;

    /**
     * 单条语句的执行结果
     */
    @Data
    @Schema(description = "单条语句结果")
    public static class StatementResult {

        @Schema(description = "语句序号（从 0 开始）")
        private Integer index;

        @Schema(description = "结果类型：SELECT=查询结果集 / WRITE=写操作影响行数")
        private String sqlType;

        @Schema(description = "SELECT 列名（按查询顺序）")
        private List<String> columns;

        @Schema(description = "SELECT 行数据")
        private List<Map<String, Object>> rows;

        @Schema(description = "SELECT 返回行数")
        private Integer totalRows;

        @Schema(description = "是否受最大行数限制（结果可能不完整）")
        private Boolean truncated;

        @Schema(description = "WRITE 影响行数")
        private Integer affectedRows;
    }
}
