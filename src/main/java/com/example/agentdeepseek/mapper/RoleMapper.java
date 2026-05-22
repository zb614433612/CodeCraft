package com.example.agentdeepseek.mapper;

import com.example.agentdeepseek.model.entity.Role;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.List;

@Mapper
@Repository
public interface RoleMapper {

    @Results(id = "roleResultMap", value = {
        @Result(property = "id", column = "id"),
        @Result(property = "name", column = "name"),
        @Result(property = "code", column = "code"),
        @Result(property = "description", column = "description"),
        @Result(property = "status", column = "status"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    @Select("SELECT * FROM sys_role WHERE status = 1 ORDER BY id")
    List<Role> selectAll();

    @Select("SELECT * FROM sys_role WHERE code = #{code}")
    @ResultMap("roleResultMap")
    Role selectByCode(@Param("code") String code);

    @Select("SELECT * FROM sys_role WHERE id = #{id}")
    @ResultMap("roleResultMap")
    Role selectById(Long id);

    @Insert("INSERT INTO sys_role (name, code, description, status) VALUES (#{name}, #{code}, #{description}, #{status})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Role role);

    @Update("UPDATE sys_role SET name=#{name}, code=#{code}, description=#{description}, status=#{status} WHERE id=#{id}")
    int update(Role role);

    @Delete("DELETE FROM sys_role WHERE id = #{id}")
    int delete(Long id);
}
