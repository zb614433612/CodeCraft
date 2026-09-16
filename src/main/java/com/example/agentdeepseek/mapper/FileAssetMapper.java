package com.example.agentdeepseek.mapper;

import com.example.agentdeepseek.model.entity.FileAsset;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 文件资产数据访问接口
 */
@Mapper
@Repository
public interface FileAssetMapper {

    /**
     * 插入文件资产记录
     *
     * @param asset 资产实体
     * @return 受影响的行数
     */
    @Insert("INSERT INTO file_asset (file_id, provider_code, user_id, filename, display_name, mime_type, size, local_path, storage_type, source, status, expires_at, created_at) " +
            "VALUES (#{fileId}, #{providerCode}, #{userId}, #{filename}, #{displayName}, #{mimeType}, #{size}, #{localPath}, #{storageType}, #{source}, #{status}, #{expiresAt}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(FileAsset asset);

    /**
     * 根据ID查询文件资产
     *
     * @param id 本地主键
     * @return 资产实体（不存在返回 null）
     */
    @Select("SELECT id, file_id, provider_code, user_id, filename, display_name, mime_type, size, local_path, storage_type, source, status, orphan_retry_count, expires_at, created_at " +
            "FROM file_asset WHERE id = #{id}")
    @Results(id = "fileAssetResultMap", value = {
        @Result(property = "id", column = "id"),
        @Result(property = "fileId", column = "file_id"),
        @Result(property = "providerCode", column = "provider_code"),
        @Result(property = "userId", column = "user_id"),
        @Result(property = "filename", column = "filename"),
        @Result(property = "displayName", column = "display_name"),
        @Result(property = "mimeType", column = "mime_type"),
        @Result(property = "size", column = "size"),
        @Result(property = "localPath", column = "local_path"),
        @Result(property = "storageType", column = "storage_type"),
        @Result(property = "source", column = "source"),
        @Result(property = "status", column = "status"),
        @Result(property = "orphanRetryCount", column = "orphan_retry_count"),
        @Result(property = "expiresAt", column = "expires_at"),
        @Result(property = "createdAt", column = "created_at")
    })
    FileAsset selectById(Long id);

    /**
     * 按用户查询文件列表（动态条件 + 游标翻页）
     *
     * @param userId         用户ID（必填，"仅本人"数据隔离）
     * @param afterId        游标：上一页最后一条 id（可空，从最新开始）
     * @param limit          条数上限
     * @param orderDesc      true=按 id 倒序（默认最新在前）/ false=正序
     * @param keyword        文件名/显示名模糊筛选（可空）
     * @param status         状态筛选（可空）
     * @param storageType    存储类型筛选：cloud/local（可空）
     * @param conversationId 按引用会话筛选（可空：只返回被该会话引用的文件）
     * @return 资产列表
     */
    @Select("<script>" +
            "SELECT id, file_id, provider_code, user_id, filename, display_name, mime_type, size, local_path, storage_type, source, status, orphan_retry_count, expires_at, created_at " +
            "FROM file_asset WHERE user_id = #{userId} " +
            "<if test='status != null and status != \"\"'> AND status = #{status} </if>" +
            "<if test='storageType != null and storageType != \"\"'> AND COALESCE(storage_type, 'cloud') = #{storageType} </if>" +
            "<if test='keyword != null and keyword != \"\"'> AND (filename LIKE CONCAT('%', #{keyword}, '%') OR display_name LIKE CONCAT('%', #{keyword}, '%')) </if>" +
            "<if test='conversationId != null'> AND id IN (SELECT DISTINCT file_asset_id FROM file_reference WHERE conversation_id = #{conversationId}) </if>" +
            "<if test='afterId != null and orderDesc'> AND id &lt; #{afterId} </if>" +
            "<if test='afterId != null and !orderDesc'> AND id &gt; #{afterId} </if>" +
            "<choose><when test='orderDesc'>ORDER BY id DESC</when><otherwise>ORDER BY id ASC</otherwise></choose> " +
            "LIMIT #{limit}" +
            "</script>")
    @ResultMap("fileAssetResultMap")
    List<FileAsset> selectByUser(@Param("userId") Long userId,
                                 @Param("afterId") Long afterId,
                                 @Param("limit") int limit,
                                 @Param("orderDesc") boolean orderDesc,
                                 @Param("keyword") String keyword,
                                 @Param("status") String status,
                                 @Param("storageType") String storageType,
                                 @Param("conversationId") Long conversationId);

    /**
     * 更新本地显示名（重命名）
     *
     * @param id          本地主键
     * @param displayName 新显示名
     * @return 受影响的行数
     */
    @Update("UPDATE file_asset SET display_name = #{displayName} WHERE id = #{id}")
    int updateDisplayName(@Param("id") Long id, @Param("displayName") String displayName);

    /**
     * 更新状态（active/deleted/orphan）
     *
     * @param id     本地主键
     * @param status 新状态
     * @return 受影响的行数
     */
    @Update("UPDATE file_asset SET status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    /**
     * 按状态查询资产（P3：orphan 补偿任务扫描用；全用户范围，按 id 升序保证处理顺序稳定）
     *
     * @param status 状态（如 orphan）
     * @param limit  单批条数上限
     * @return 资产列表
     */
    @Select("SELECT id, file_id, provider_code, user_id, filename, display_name, mime_type, size, local_path, storage_type, source, status, orphan_retry_count, expires_at, created_at " +
            "FROM file_asset WHERE status = #{status} ORDER BY id ASC LIMIT #{limit}")
    @ResultMap("fileAssetResultMap")
    List<FileAsset> selectByStatus(@Param("status") String status, @Param("limit") int limit);

    /**
     * orphan 补偿重试计数 +1（P3：远端删除失败累计；超阈值保留告警）
     *
     * @param id 本地主键
     * @return 受影响的行数
     */
    @Update("UPDATE file_asset SET orphan_retry_count = COALESCE(orphan_retry_count, 0) + 1 WHERE id = #{id}")
    int incrementOrphanRetry(Long id);

    /**
     * 删除文件资产记录
     *
     * @param id 本地主键
     * @return 受影响的行数
     */
    @Delete("DELETE FROM file_asset WHERE id = #{id}")
    int deleteById(Long id);
}
