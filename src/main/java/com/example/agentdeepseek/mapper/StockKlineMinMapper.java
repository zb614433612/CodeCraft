package com.example.agentdeepseek.mapper;

import com.example.agentdeepseek.model.entity.StockKlineMin;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Mapper
@Repository
public interface StockKlineMinMapper {

    @Select("SELECT id, ts_code AS tsCode, trade_date AS tradeDate, minute, freq, "
            + "open, high, low, close, vol, amount, created_at AS createdAt "
            + "FROM stock_kline_min "
            + "WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate} "
            + "ORDER BY minute")
    @Results(id = "klineMinResult", value = {
        @Result(property = "id", column = "id"),
        @Result(property = "tsCode", column = "tsCode"),
        @Result(property = "tradeDate", column = "tradeDate"),
        @Result(property = "minute", column = "minute"),
        @Result(property = "freq", column = "freq"),
        @Result(property = "open", column = "open"),
        @Result(property = "high", column = "high"),
        @Result(property = "low", column = "low"),
        @Result(property = "close", column = "close"),
        @Result(property = "vol", column = "vol"),
        @Result(property = "amount", column = "amount"),
        @Result(property = "createdAt", column = "createdAt")
    })
    List<StockKlineMin> findByTsCodeAndTradeDate(
            @Param("tsCode") String tsCode,
            @Param("tradeDate") LocalDate tradeDate);
}
