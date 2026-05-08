package com.example.agentdeepseek.mapper;

import com.example.agentdeepseek.model.entity.StockInfo;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Mapper
@Repository
public interface StockInfoMapper {

    @SelectProvider(type = StockInfoSqlProvider.class, method = "countByCondition")
    long countByCondition(@Param("keyword") String keyword,
                          @Param("market") String market,
                          @Param("status") Integer status);

    @SelectProvider(type = StockInfoSqlProvider.class, method = "selectByCondition")
    @ResultMap("stockInfoResult")
    List<StockInfo> selectByCondition(@Param("keyword") String keyword,
                                      @Param("market") String market,
                                      @Param("status") Integer status,
                                      @Param("offset") int offset,
                                      @Param("limit") int limit);

    @Select("SELECT id, ts_code AS tsCode, symbol, market, name, list_date AS listDate, "
            + "total_share AS totalShare, status, created_at AS createdAt, "
            + "updated_at AS updatedAt FROM stock_info WHERE ts_code = #{tsCode}")
    @Results(id = "stockInfoResult", value = {
        @Result(property = "id", column = "id"),
        @Result(property = "tsCode", column = "tsCode"),
        @Result(property = "symbol", column = "symbol"),
        @Result(property = "market", column = "market"),
        @Result(property = "name", column = "name"),
        @Result(property = "listDate", column = "listDate"),
        @Result(property = "totalShare", column = "totalShare"),
        @Result(property = "status", column = "status"),
        @Result(property = "createdAt", column = "createdAt"),
        @Result(property = "updatedAt", column = "updatedAt")
    })
    StockInfo findByTsCode(String tsCode);

    @Select("SELECT ts_code FROM stock_info WHERE status = 1")
    List<String> findAllActiveTsCodes();

    @Select("SELECT si.ts_code AS tsCode, si.symbol, si.name FROM stock_info si WHERE si.status = 1 "
            + "AND si.symbol NOT LIKE '30%' AND si.symbol NOT LIKE '68%' "
            + "AND si.name NOT LIKE 'ST%' AND si.name NOT LIKE '*ST%'")
    @Results(id = "eligibleBasic", value = {
        @Result(property = "tsCode", column = "tsCode"),
        @Result(property = "symbol", column = "symbol"),
        @Result(property = "name", column = "name")
    })
    List<StockInfo> findAllEligibleBasic();

    class StockInfoSqlProvider {

        public String countByCondition(Map<String, Object> params) {
            return buildWhere("SELECT COUNT(*) FROM stock_info", params);
        }

        public String selectByCondition(Map<String, Object> params) {
            return buildWhere("SELECT id, ts_code AS tsCode, symbol, market, name, "
                    + "list_date AS listDate, total_share AS totalShare, status, "
                    + "created_at AS createdAt, updated_at AS updatedAt FROM stock_info", params)
                    + " ORDER BY symbol ASC LIMIT #{limit} OFFSET #{offset}";
        }

        private String buildWhere(String base, Map<String, Object> params) {
            StringBuilder sql = new StringBuilder(base).append(" WHERE 1=1");
            String keyword = (String) params.get("keyword");
            String market = (String) params.get("market");
            Integer status = (Integer) params.get("status");
            if (keyword != null && !keyword.isEmpty()) {
                sql.append(" AND (ts_code LIKE CONCAT('%', #{keyword}, '%')"
                        + " OR symbol LIKE CONCAT('%', #{keyword}, '%')"
                        + " OR name LIKE CONCAT('%', #{keyword}, '%'))");
            }
            if (market != null && !market.isEmpty()) {
                sql.append(" AND market = #{market}");
            }
            if (status != null) {
                sql.append(" AND status = #{status}");
            }
            return sql.toString();
        }
    }
}
