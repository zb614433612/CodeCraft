package com.example.agentdeepseek.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户实体类
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class User {
    /**
     * 用户ID，自增主键
     */
    private Long id;

    /**
     * 用户名
     */
    private String username;

    /**
     * 密码（MD5加密后）
     */
    private String password;

    /**
     * 用户昵称
     */
    private String nickname;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 构造函数，用于创建新用户
     * @param username 用户名
     * @param password 密码（MD5加密后）
     * @param nickname 昵称
     */
    public User(String username, String password, String nickname) {
        this.username = username;
        this.password = password;
        this.nickname = nickname;
        this.createTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
    }
}