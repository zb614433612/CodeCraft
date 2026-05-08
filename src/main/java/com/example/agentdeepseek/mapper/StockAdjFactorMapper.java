package com.example.agentdeepseek.mapper;

import com.example.agentdeepseek.model.entity.StockAdjFactor;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.List;

@Mapper
@Repository
public interface StockAdjFactorMapper {

    @Select("SELECT id, ts_code AS tsCode, trade_date AS tradeDate, "
            + "adj_factor AS adjFactor, fore_adj_factor AS foreAdjFactor, "
            + "back_adj_factor AS backAdjFactor "
            + "FROM stock_adj_factor WHERE ts_code = #{tsCode} ORDER BY trade_date")
    @Results(id = "adjFactorResult", value = {
        @Result(property = "id", column = "id"),
        @Result(property = "tsCode", column = "tsCode"),
        @Result(property = "tradeDate", column = "tradeDate"),
        @Result(property = "adjFactor", column = "adjFactor"),
        @Result(property = "foreAdjFactor", column = "foreAdjFactor"),
        @Result(property = "backAdjFactor", column = "backAdjFactor")
    })
    List<StockAdjFactor> findByTsCode(@Param("tsCode") String tsCode);
}
