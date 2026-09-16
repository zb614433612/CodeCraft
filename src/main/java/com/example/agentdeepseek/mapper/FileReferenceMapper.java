package com.example.agentdeepseek.mapper;

import com.example.agentdeepseek.model.entity.FileReference;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 文件引用关联数据访问接口
 */
@Mapper
@Repository
public interface FileReferenceMapper {

    /**
     * 插入引用记录
     *
     * @param reference 引用实体
     * @return 受影响的行数
     */
    @Insert("INSERT INTO file_reference (file_asset_id, conversation_id, message_id, created_at) " +
            "VALUES (#{fileAssetId}, #{conversationId}, #{messageId}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(FileReference reference);

    /**
     * 查询会话的全部引用
     *
     * @param conversationId 会话ID
     * @return 引用列表
     */
    @Select("SELECT id, file_asset_id, conversation_id, message_id, created_at FROM file_reference WHERE conversation_id = #{conversationId}")
    @Results(id = "fileReferenceResultMap", value = {
        @Result(property = "id", column = "id"),
        @Result(property = "fileAssetId", column = "file_asset_id"),
        @Result(property = "conversationId", column = "conversation_id"),
        @Result(property = "messageId", column = "message_id"),
        @Result(property = "createdAt", column = "created_at")
    })
    List<FileReference> selectByConversationId(Long conversationId);

    /**
     * 查询资产的全部引用
     *
     * @param fileAssetId 资产ID
     * @return 引用列表
     */
    @Select("SELECT id, file_asset_id, conversation_id, message_id, created_at FROM file_reference WHERE file_asset_id = #{fileAssetId}")
    @ResultMap("fileReferenceResultMap")
    List<FileReference> selectByAssetId(Long fileAssetId);

    /**
     * 统计资产被引用次数（级联删除时"引用归零"判定）
     *
     * @param fileAssetId 资产ID
     * @return 引用条数
     */
    @Select("SELECT COUNT(*) FROM file_reference WHERE file_asset_id = #{fileAssetId}")
    int countByAssetId(Long fileAssetId);

    /**
     * 查询会话引用的资产ID列表（去重；级联删除前收集）
     *
     * @param conversationId 会话ID
     * @return 去重后的资产ID列表
     */
    @Select("SELECT DISTINCT file_asset_id FROM file_reference WHERE conversation_id = #{conversationId}")
    List<Long> selectAssetIdsByConversationId(Long conversationId);

    /**
     * 删除会话的全部引用（会话级联删除时调用）
     *
     * @param conversationId 会话ID
     * @return 受影响的行数
     */
    @Delete("DELETE FROM file_reference WHERE conversation_id = #{conversationId}")
    int deleteByConversationId(Long conversationId);

    /**
     * 删除资产的全部引用（文件管理页删除文件时调用）
     *
     * @param fileAssetId 资产ID
     * @return 受影响的行数
     */
    @Delete("DELETE FROM file_reference WHERE file_asset_id = #{fileAssetId}")
    int deleteByAssetId(Long fileAssetId);
}
