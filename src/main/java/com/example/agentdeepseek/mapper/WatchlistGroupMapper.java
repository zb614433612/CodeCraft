package com.example.agentdeepseek.mapper;

import com.example.agentdeepseek.model.entity.WatchlistGroup;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.List;

@Mapper
@Repository
public interface WatchlistGroupMapper {

    @Insert("INSERT INTO watchlist_group (user_id, name, sort_order) VALUES (#{userId}, #{name}, #{sortOrder})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(WatchlistGroup group);

    @Select("SELECT id, user_id AS userId, name, sort_order AS sortOrder, created_at AS createdAt, updated_at AS updatedAt "
            + "FROM watchlist_group WHERE id = #{id}")
    @Results(id = "groupResult", value = {
        @Result(property = "id", column = "id"),
        @Result(property = "userId", column = "userId"),
        @Result(property = "name", column = "name"),
        @Result(property = "sortOrder", column = "sortOrder"),
        @Result(property = "createdAt", column = "createdAt"),
        @Result(property = "updatedAt", column = "updatedAt")
    })
    WatchlistGroup selectById(@Param("id") Long id);

    @Select("SELECT id, user_id AS userId, name, sort_order AS sortOrder, created_at AS createdAt, updated_at AS updatedAt "
            + "FROM watchlist_group WHERE user_id = #{userId} ORDER BY created_at DESC, id DESC")
    @ResultMap("groupResult")
    List<WatchlistGroup> selectByUserId(@Param("userId") Long userId);

    @Select("SELECT id FROM watchlist_group WHERE user_id = #{userId} AND name = #{name} LIMIT 1")
    Long selectIdByNameAndUserId(@Param("userId") Long userId, @Param("name") String name);

    @Update("UPDATE watchlist_group SET name = #{name} WHERE id = #{id}")
    int updateName(@Param("id") Long id, @Param("name") String name);

    @Delete("DELETE FROM watchlist_group WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
