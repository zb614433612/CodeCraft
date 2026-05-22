package com.example.agentdeepseek.mapper;

import com.example.agentdeepseek.model.entity.User;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.List;
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
    @Insert("INSERT INTO sys_user (username, password, nickname, role, email, phone, avatar, status, create_time, update_time) " +
            "VALUES (#{username}, #{password}, #{nickname}, #{role}, #{email}, #{phone}, #{avatar}, #{status}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(User user);

    /**
     * 根据ID查询用户
     * @param id 用户ID
     * @return 用户实体
     */
    @Select("SELECT id, username, password, nickname, role, email, phone, avatar, status, create_time, update_time FROM sys_user WHERE id = #{id}")
    @Results(id = "userResultMap", value = {
        @Result(property = "id", column = "id"),
        @Result(property = "username", column = "username"),
        @Result(property = "password", column = "password"),
        @Result(property = "nickname", column = "nickname"),
        @Result(property = "role", column = "role"),
        @Result(property = "email", column = "email"),
        @Result(property = "phone", column = "phone"),
        @Result(property = "avatar", column = "avatar"),
        @Result(property = "status", column = "status"),
        @Result(property = "createTime", column = "create_time"),
        @Result(property = "updateTime", column = "update_time")
    })
    Optional<User> selectById(Long id);

    /**
     * 根据用户名查询用户
     * @param username 用户名
     * @return 用户实体
     */
    @Select("SELECT id, username, password, nickname, role, email, phone, avatar, status, create_time, update_time FROM sys_user WHERE username = #{username}")
    @ResultMap("userResultMap")
    Optional<User> selectByUsername(String username);

    /**
     * 分页查询所有用户
     * @param offset 偏移量
     * @param limit 每页条数
     * @return 用户列表
     */
    @Select("SELECT id, username, password, nickname, role, email, phone, avatar, status, create_time, update_time FROM sys_user ORDER BY create_time DESC LIMIT #{offset}, #{limit}")
    @ResultMap("userResultMap")
    List<User> selectAll(@Param("offset") int offset, @Param("limit") int limit);

    /**
     * 查询用户总数
     * @return 用户总数
     */
    @Select("SELECT COUNT(*) FROM sys_user")
    long selectCount();

    /**
     * 更新用户信息（密码、昵称、角色、邮箱、手机号、头像、状态、更新时间）
     * @param user 用户实体
     * @return 受影响的行数
     */
    @Update("UPDATE sys_user SET password = #{password}, nickname = #{nickname}, role = #{role}, " +
            "email = #{email}, phone = #{phone}, avatar = #{avatar}, status = #{status}, update_time = #{updateTime} WHERE id = #{id}")
    int update(User user);

    /**
     * 删除用户
     * @param id 用户ID
     * @return 受影响的行数
     */
    @Delete("DELETE FROM sys_user WHERE id = #{id}")
    int delete(Long id);

    /**
     * 按用户名模糊搜索（分页）
     * @param username 搜索关键词
     * @param offset 偏移量
     * @param limit 每页条数
     * @return 用户列表
     */
    @Select("<script>" +
            "SELECT id, username, password, nickname, role, email, phone, avatar, status, create_time, update_time FROM sys_user " +
            "<where>" +
            "<if test='username != null and username != \"\"'>AND username LIKE CONCAT('%', #{username}, '%')</if>" +
            "</where>" +
            "ORDER BY create_time DESC LIMIT #{offset}, #{limit}" +
            "</script>")
    @ResultMap("userResultMap")
    List<User> searchByUsername(@Param("username") String username, @Param("offset") int offset, @Param("limit") int limit);

    /**
     * 按用户名模糊搜索统计总数
     * @param username 搜索关键词
     * @return 匹配的用户总数
     */
    @Select("<script>" +
            "SELECT COUNT(*) FROM sys_user " +
            "<where>" +
            "<if test='username != null and username != \"\"'>AND username LIKE CONCAT('%', #{username}, '%')</if>" +
            "</where>" +
            "</script>")
    long countByUsername(@Param("username") String username);
}
