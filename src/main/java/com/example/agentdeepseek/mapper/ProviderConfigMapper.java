package com.example.agentdeepseek.mapper;

import com.example.agentdeepseek.model.entity.ProviderConfig;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * LLM Provider 配置数据访问接口
 */
@Mapper
@Repository
public interface ProviderConfigMapper {

    @Select("SELECT id, code, name, base_url, api_key, default_model, model_list, request_template, " +
            "is_default, enabled, sort_order, created_at, updated_at FROM llm_provider WHERE id = #{id}")
    @Results(id = "providerConfigResultMap", value = {
        @Result(property = "id", column = "id"),
        @Result(property = "code", column = "code"),
        @Result(property = "name", column = "name"),
        @Result(property = "baseUrl", column = "base_url"),
        @Result(property = "apiKey", column = "api_key"),
        @Result(property = "defaultModel", column = "default_model"),
        @Result(property = "modelList", column = "model_list"),
        @Result(property = "requestTemplate", column = "request_template"),
        @Result(property = "isDefault", column = "is_default"),
        @Result(property = "enabled", column = "enabled"),
        @Result(property = "sortOrder", column = "sort_order"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    ProviderConfig selectById(Long id);

    @Select("SELECT id, code, name, base_url, api_key, default_model, model_list, request_template, " +
            "is_default, enabled, sort_order, created_at, updated_at FROM llm_provider " +
            "WHERE code = #{code}")
    @ResultMap("providerConfigResultMap")
    ProviderConfig selectByCode(String code);

    @Select("SELECT id, code, name, base_url, api_key, default_model, model_list, request_template, " +
            "is_default, enabled, sort_order, created_at, updated_at FROM llm_provider " +
            "WHERE enabled = 1 ORDER BY sort_order ASC, id ASC")
    @ResultMap("providerConfigResultMap")
    List<ProviderConfig> selectAllEnabled();

    @Insert("INSERT INTO llm_provider (code, name, base_url, api_key, default_model, model_list, " +
            "request_template, is_default, enabled, sort_order, created_at, updated_at) " +
            "VALUES (#{code}, #{name}, #{baseUrl}, #{apiKey}, #{defaultModel}, #{modelList}, " +
            "#{requestTemplate}, #{isDefault}, #{enabled}, #{sortOrder}, #{createdAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ProviderConfig config);

    @Update("UPDATE llm_provider SET code = #{code}, name = #{name}, base_url = #{baseUrl}, " +
            "api_key = #{apiKey}, default_model = #{defaultModel}, model_list = #{modelList}, " +
            "request_template = #{requestTemplate}, is_default = #{isDefault}, enabled = #{enabled}, " +
            "sort_order = #{sortOrder}, updated_at = #{updatedAt} WHERE id = #{id}")
    int update(ProviderConfig config);

    @Delete("DELETE FROM llm_provider WHERE id = #{id}")
    int delete(Long id);
}
