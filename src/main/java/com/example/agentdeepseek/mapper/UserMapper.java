package com.example.agentdeepseek.mapper;

import com.example.agentdeepseek.model.entity.User;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 用户数据访问接口
 */
@Mapper
@Repository
public interface UserMapper {

    /**
     * 创建用户表（如果不存在）
     */
    @Select("SELECT 1")
    int createTable();

    /**
     * 插入新用户
     * @param user 用户实体
     * @return 受影响的行数
     */
    @Insert("INSERT INTO sys_user (username, password, nickname, create_time, update_time) " +
            "VALUES (#{username}, #{password}, #{nickname}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(User user);

    /**
     * 根据ID查询用户
     * @param id 用户ID
     * @return 用户实体
     */
    @Select("SELECT id, username, password, nickname, create_time, update_time FROM sys_user WHERE id = #{id}")
    @Results(id = "userResultMap", value = {
        @Result(property = "id", column = "id"),
        @Result(property = "username", column = "username"),
        @Result(property = "password", column = "password"),
        @Result(property = "nickname", column = "nickname"),
        @Result(property = "createTime", column = "create_time"),
        @Result(property = "updateTime", column = "update_time")
    })
    Optional<User> selectById(Long id);

    /**
     * 根据用户名查询用户
     * @param username 用户名
     * @return 用户实体
     */
    @Select("SELECT id, username, password, nickname, create_time, update_time FROM sys_user WHERE username = #{username}")
    @ResultMap("userResultMap")
    Optional<User> selectByUsername(String username);

    /**
     * 更新用户信息（密码、昵称、更新时间）
     * @param user 用户实体
     * @return 受影响的行数
     */
    @Update("UPDATE sys_user SET password = #{password}, nickname = #{nickname}, update_time = #{updateTime} WHERE id = #{id}")
    int update(User user);

    /**
     * 删除用户
     * @param id 用户ID
     * @return 受影响的行数
     */
    @Delete("DELETE FROM sys_user WHERE id = #{id}")
    int delete(Long id);
}