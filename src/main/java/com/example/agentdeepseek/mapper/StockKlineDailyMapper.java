package com.example.agentdeepseek.mapper;

import com.example.agentdeepseek.model.entity.StockKlineDaily;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Mapper
@Repository
public interface StockKlineDailyMapper {

    @Select("SELECT id, ts_code AS tsCode, trade_date AS tradeDate, open, high, low, close, "
            + "pre_close AS preClose, change_ AS changeVal, pct_change AS pctChange, "
            + "vol, amount, turnover, created_at AS createdAt "
            + "FROM stock_kline_daily WHERE ts_code = #{tsCode} ORDER BY trade_date")
    @Results(id = "dailyResult", value = {
        @Result(property = "id", column = "id"),
        @Result(property = "tsCode", column = "tsCode"),
        @Result(property = "tradeDate", column = "tradeDate"),
        @Result(property = "open", column = "open"),
        @Result(property = "high", column = "high"),
        @Result(property = "low", column = "low"),
        @Result(property = "close", column = "close"),
        @Result(property = "preClose", column = "preClose"),
        @Result(property = "change", column = "changeVal"),
        @Result(property = "pctChange", column = "pctChange"),
        @Result(property = "vol", column = "vol"),
        @Result(property = "amount", column = "amount"),
        @Result(property = "turnover", column = "turnover"),
        @Result(property = "createdAt", column = "createdAt")
    })
    List<StockKlineDaily> findByTsCode(@Param("tsCode") String tsCode);

    @Select("SELECT id, ts_code AS tsCode, trade_date AS tradeDate, open, high, low, close, "
            + "pre_close AS preClose, change_ AS changeVal, pct_change AS pctChange, "
            + "vol, amount, turnover, created_at AS createdAt "
            + "FROM stock_kline_daily WHERE trade_date = #{tradeDate} ORDER BY ts_code")
    @ResultMap("dailyResult")
    List<StockKlineDaily> findByDate(@Param("tradeDate") LocalDate tradeDate);
}
