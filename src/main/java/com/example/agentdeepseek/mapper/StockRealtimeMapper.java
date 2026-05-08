package com.example.agentdeepseek.mapper;

import com.example.agentdeepseek.model.entity.StockRealtime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

@Mapper
@Repository
public interface StockRealtimeMapper {

    @Insert({"INSERT INTO stock_realtime (",
            "ts_code, name, price, open, high, low, pre_close, change_, pct_change,",
            "volume, amount, bid_prices, bid_volumes, ask_prices, ask_volumes,",
            "turnover_rate, pe, pb, total_mv, circ_mv, limit_up, limit_down,",
            "buy_vol, sell_vol, amplitude, order_diff, avg_price, volume_ratio,",
            "pe_ttm, eps, bvps, capital_reserve, net_profit_growth, revenue_growth,",
            "total_shares, circ_shares, pcf, raw_data, update_time",
            ") VALUES (",
            "#{tsCode}, #{name}, #{price}, #{open}, #{high}, #{low}, #{preClose},",
            "#{change_}, #{pctChange}, #{volume}, #{amount},",
            "#{bidPrices}, #{bidVolumes}, #{askPrices}, #{askVolumes},",
            "#{turnoverRate}, #{pe}, #{pb}, #{totalMv}, #{circMv},",
            "#{limitUp}, #{limitDown}, #{buyVol}, #{sellVol},",
            "#{amplitude}, #{orderDiff}, #{avgPrice}, #{volumeRatio},",
            "#{peTtm}, #{eps}, #{bvps}, #{capitalReserve},",
            "#{netProfitGrowth}, #{revenueGrowth}, #{totalShares}, #{circShares},",
            "#{pcf}, #{rawData}, #{updateTime}",
            ") AS new ON DUPLICATE KEY UPDATE",
            "name=new.name, price=new.price, open=new.open, high=new.high,",
            "low=new.low, pre_close=new.pre_close, change_=new.change_,",
            "pct_change=new.pct_change, volume=new.volume, amount=new.amount,",
            "bid_prices=new.bid_prices, bid_volumes=new.bid_volumes,",
            "ask_prices=new.ask_prices, ask_volumes=new.ask_volumes,",
            "turnover_rate=new.turnover_rate, pe=new.pe, pb=new.pb,",
            "total_mv=new.total_mv, circ_mv=new.circ_mv,",
            "limit_up=new.limit_up, limit_down=new.limit_down,",
            "buy_vol=new.buy_vol, sell_vol=new.sell_vol,",
            "amplitude=new.amplitude, order_diff=new.order_diff,",
            "avg_price=new.avg_price, volume_ratio=new.volume_ratio,",
            "pe_ttm=new.pe_ttm, eps=new.eps, bvps=new.bvps,",
            "capital_reserve=new.capital_reserve,",
            "net_profit_growth=new.net_profit_growth,",
            "revenue_growth=new.revenue_growth,",
            "total_shares=new.total_shares, circ_shares=new.circ_shares,",
            "pcf=new.pcf, raw_data=new.raw_data,",
            "update_time=new.update_time"})
    int upsert(StockRealtime record);

    @Insert({
            "<script>",
            "INSERT INTO stock_realtime (",
            "ts_code, name, price, open, high, low, pre_close, change_, pct_change,",
            "volume, amount, bid_prices, bid_volumes, ask_prices, ask_volumes,",
            "turnover_rate, pe, pb, total_mv, circ_mv, limit_up, limit_down,",
            "buy_vol, sell_vol, amplitude, order_diff, avg_price, volume_ratio,",
            "pe_ttm, eps, bvps, capital_reserve, net_profit_growth, revenue_growth,",
            "total_shares, circ_shares, pcf, raw_data, update_time",
            ") VALUES ",
            "<foreach collection='list' item='r' separator=','>",
            "(",
            "#{r.tsCode}, #{r.name}, #{r.price}, #{r.open}, #{r.high}, #{r.low},",
            "#{r.preClose}, #{r.change_}, #{r.pctChange}, #{r.volume}, #{r.amount},",
            "#{r.bidPrices}, #{r.bidVolumes}, #{r.askPrices}, #{r.askVolumes},",
            "#{r.turnoverRate}, #{r.pe}, #{r.pb}, #{r.totalMv}, #{r.circMv},",
            "#{r.limitUp}, #{r.limitDown}, #{r.buyVol}, #{r.sellVol},",
            "#{r.amplitude}, #{r.orderDiff}, #{r.avgPrice}, #{r.volumeRatio},",
            "#{r.peTtm}, #{r.eps}, #{r.bvps}, #{r.capitalReserve},",
            "#{r.netProfitGrowth}, #{r.revenueGrowth}, #{r.totalShares},",
            "#{r.circShares}, #{r.pcf}, #{r.rawData}, #{r.updateTime}",
            ")",
            "</foreach>",
            "AS new ON DUPLICATE KEY UPDATE",
            "name=new.name, price=new.price, open=new.open, high=new.high,",
            "low=new.low, pre_close=new.pre_close, change_=new.change_,",
            "pct_change=new.pct_change, volume=new.volume, amount=new.amount,",
            "bid_prices=new.bid_prices, bid_volumes=new.bid_volumes,",
            "ask_prices=new.ask_prices, ask_volumes=new.ask_volumes,",
            "turnover_rate=new.turnover_rate, pe=new.pe, pb=new.pb,",
            "total_mv=new.total_mv, circ_mv=new.circ_mv,",
            "limit_up=new.limit_up, limit_down=new.limit_down,",
            "buy_vol=new.buy_vol, sell_vol=new.sell_vol,",
            "amplitude=new.amplitude, order_diff=new.order_diff,",
            "avg_price=new.avg_price, volume_ratio=new.volume_ratio,",
            "pe_ttm=new.pe_ttm, eps=new.eps, bvps=new.bvps,",
            "capital_reserve=new.capital_reserve,",
            "net_profit_growth=new.net_profit_growth,",
            "revenue_growth=new.revenue_growth,",
            "total_shares=new.total_shares, circ_shares=new.circ_shares,",
            "pcf=new.pcf, raw_data=new.raw_data,",
            "update_time=new.update_time",
            "</script>"
    })
    int upsertBatch(@Param("list") java.util.List<StockRealtime> list);
}
