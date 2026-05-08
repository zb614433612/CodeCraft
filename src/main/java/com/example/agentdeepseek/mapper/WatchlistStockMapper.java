package com.example.agentdeepseek.mapper;

import com.example.agentdeepseek.model.entity.WatchlistStock;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.List;

@Mapper
@Repository
public interface WatchlistStockMapper {

    @Insert({"<script>",
            "INSERT INTO watchlist_stock (group_id, ts_code, stock_name, sort_order) VALUES ",
            "<foreach collection='list' item='s' separator=','>",
            "(#{s.groupId}, #{s.tsCode}, #{s.stockName}, #{s.sortOrder})",
            "</foreach>",
            "AS new ON DUPLICATE KEY UPDATE stock_name = new.stock_name",
            "</script>"})
    int batchInsert(@Param("list") List<WatchlistStock> stocks);

    @Select("SELECT id, group_id AS groupId, ts_code AS tsCode, stock_name AS stockName, "
            + "sort_order AS sortOrder, created_at AS createdAt "
            + "FROM watchlist_stock WHERE group_id = #{groupId} ORDER BY sort_order, id")
    @Results(id = "stockResult", value = {
        @Result(property = "id", column = "id"),
        @Result(property = "groupId", column = "groupId"),
        @Result(property = "tsCode", column = "tsCode"),
        @Result(property = "stockName", column = "stockName"),
        @Result(property = "sortOrder", column = "sortOrder"),
        @Result(property = "createdAt", column = "createdAt")
    })
    List<WatchlistStock> selectByGroupId(@Param("groupId") Long groupId);

    @Delete({"<script>",
            "DELETE FROM watchlist_stock WHERE group_id = #{groupId} AND ts_code IN ",
            "<foreach collection='tsCodes' item='code' open='(' separator=',' close=')'>",
            "#{code}",
            "</foreach>",
            "</script>"})
    int batchDelete(@Param("groupId") Long groupId, @Param("tsCodes") List<String> tsCodes);

    @Delete("DELETE FROM watchlist_stock WHERE group_id = #{groupId}")
    int deleteByGroupId(@Param("groupId") Long groupId);
}
