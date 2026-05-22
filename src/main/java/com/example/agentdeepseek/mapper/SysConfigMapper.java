package com.example.agentdeepseek.mapper;

import com.example.agentdeepseek.model.entity.SysConfig;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

@Mapper
@Repository
public interface SysConfigMapper {

    @Select("SELECT * FROM sys_config WHERE config_key = #{configKey}")
    @Results(id = "configResultMap", value = {
        @Result(property = "id", column = "id"),
        @Result(property = "configKey", column = "config_key"),
        @Result(property = "configValue", column = "config_value"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    SysConfig selectByKey(@Param("configKey") String configKey);

    @Update("UPDATE sys_config SET config_value = #{configValue} WHERE config_key = #{configKey}")
    int updateValue(@Param("configKey") String configKey, @Param("configValue") String configValue);

    @Insert("INSERT INTO sys_config (config_key, config_value) VALUES (#{configKey}, #{configValue}) " +
            "ON DUPLICATE KEY UPDATE config_value = #{configValue}")
    int upsert(@Param("configKey") String configKey, @Param("configValue") String configValue);
}
