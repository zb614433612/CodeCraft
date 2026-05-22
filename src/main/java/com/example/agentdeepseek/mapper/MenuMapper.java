package com.example.agentdeepseek.mapper;

import com.example.agentdeepseek.model.entity.Menu;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.List;

@Mapper
@Repository
public interface MenuMapper {

    @Results(id = "menuResultMap", value = {
        @Result(property = "id", column = "id"),
        @Result(property = "name", column = "name"),
        @Result(property = "path", column = "path"),
        @Result(property = "icon", column = "icon"),
        @Result(property = "parentId", column = "parent_id"),
        @Result(property = "sortOrder", column = "sort_order"),
        @Result(property = "permissionCode", column = "permission_code"),
        @Result(property = "menuType", column = "menu_type"),
        @Result(property = "visible", column = "visible"),
        @Result(property = "status", column = "status"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    @Select("SELECT * FROM sys_menu ORDER BY sort_order")
    List<Menu> selectAll();

    @Select("SELECT * FROM sys_menu WHERE id = #{id}")
    @ResultMap("menuResultMap")
    Menu selectById(Long id);

    @Select("SELECT * FROM sys_menu WHERE menu_type = #{menuType} AND status = 1 ORDER BY sort_order")
    @ResultMap("menuResultMap")
    List<Menu> selectByType(@Param("menuType") String menuType);

    @Select("<script>" +
            "SELECT * FROM sys_menu WHERE id IN " +
            "<foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach> " +
            "ORDER BY sort_order" +
            "</script>")
    @ResultMap("menuResultMap")
    List<Menu> selectByIds(@Param("ids") List<Long> ids);

    @Insert("INSERT INTO sys_menu (name, path, icon, parent_id, sort_order, permission_code, menu_type, visible, status, created_at, updated_at) " +
            "VALUES (#{name}, #{path}, #{icon}, #{parentId}, #{sortOrder}, #{permissionCode}, #{menuType}, #{visible}, #{status}, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Menu menu);

    @Update("UPDATE sys_menu SET name=#{name}, path=#{path}, icon=#{icon}, parent_id=#{parentId}, " +
            "sort_order=#{sortOrder}, permission_code=#{permissionCode}, menu_type=#{menuType}, " +
            "visible=#{visible}, status=#{status}, updated_at=NOW() WHERE id=#{id}")
    int update(Menu menu);

    @Delete("DELETE FROM sys_menu WHERE id = #{id}")
    int delete(Long id);

    @Select("SELECT DISTINCT parent_id FROM sys_menu WHERE parent_id IS NOT NULL")
    List<Long> selectParentIds();
}
