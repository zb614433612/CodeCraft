package com.example.agentdeepseek.service;

public interface ConfigService {

    /**
     * 获取配置项的值
     * @param key 配置键
     * @return 配置值，不存在返回 null
     */
    String getValue(String key);

    /**
     * 更新配置项的值
     * @param key 配置键
     * @param value 配置值
     */
    void setValue(String key, String value);
}
