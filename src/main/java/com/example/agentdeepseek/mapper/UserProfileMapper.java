package com.example.agentdeepseek.mapper;

import com.example.agentdeepseek.model.entity.UserProfile;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.List;

@Mapper
@Repository
public interface UserProfileMapper {

    @Insert("INSERT INTO user_profile (username, user_id, created_at) VALUES (#{username}, #{userId}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(UserProfile userProfile);

    @Select("SELECT id, username, user_id, created_at FROM user_profile WHERE id = #{id}")
    @Results(id = "userProfileResultMap", value = {
        @Result(property = "id", column = "id"),
        @Result(property = "username", column = "username"),
        @Result(property = "userId", column = "user_id"),
        @Result(property = "createdAt", column = "created_at")
    })
    UserProfile selectById(Long id);

    @Select("SELECT id, username, user_id, created_at FROM user_profile WHERE username = #{username} ORDER BY created_at DESC LIMIT 1")
    @ResultMap("userProfileResultMap")
    UserProfile selectLatestByUsername(String username);

    @Select("SELECT id, username, user_id, created_at FROM user_profile WHERE user_id = #{userId} ORDER BY created_at DESC")
    @ResultMap("userProfileResultMap")
    List<UserProfile> selectByUserId(Long userId);

    @Delete("DELETE FROM user_profile WHERE id = #{id}")
    int deleteById(Long id);

    @Update("UPDATE user_profile SET username = #{username} WHERE id = #{id}")
    int updateUsername(@Param("id") Long id, @Param("username") String username);
}
