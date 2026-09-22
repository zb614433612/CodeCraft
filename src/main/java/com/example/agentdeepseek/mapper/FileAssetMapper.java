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
     * 按用户查询文件列表（动态条件 + 游标翻页 / offset 分页）
     * <p>
     * 2026-09-21 文件管理页分页改造：新增 {@code offset} 参数支持标准页码分页
     * （offset = (page-1)*pageSize，与游标 afterId 二选一，两者并存时 SQL 均兼容）。
     * H2 / MySQL 均支持「LIMIT ? OFFSET ?」语法（项目内 LessonMapper 等已同款使用）。
     * </p>
     *
     * @param userId         用户ID（必填，"仅本人"数据隔离）
     * @param afterId        游标：上一页最后一条 id（可空，从最新开始；与 offset 二选一）
     * @param offset         偏移量（可空；标准分页使用，offset=(page-1)*pageSize）
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
            "<if test='offset != null'> OFFSET #{offset}</if>" +
            "</script>")
    @ResultMap("fileAssetResultMap")
    List<FileAsset> selectByUser(@Param("userId") Long userId,
                                 @Param("afterId") Long afterId,
                                 @Param("offset") Integer offset,
                                 @Param("limit") int limit,
                                 @Param("orderDesc") boolean orderDesc,
                                 @Param("keyword") String keyword,
                                 @Param("status") String status,
                                 @Param("storageType") String storageType,
                                 @Param("conversationId") Long conversationId);

    /**
     * 统计按用户筛选条件下的文件总数（2026-09-21 分页改造：页码计算用）
     * <p>筛选条件与 {@link #selectByUser} 完全对齐，保证「总数 / 页数 / 列表」口径一致。</p>
     *
     * @param userId         用户ID（必填）
     * @param keyword        文件名/显示名模糊筛选（可空）
     * @param status         状态筛选（可空）
     * @param storageType    存储类型筛选：cloud/local（可空）
     * @param conversationId 按引用会话筛选（可空）
     * @return 满足条件的记录总数
     */
    @Select("<script>" +
            "SELECT COUNT(*) FROM file_asset WHERE user_id = #{userId} " +
            "<if test='status != null and status != \"\"'> AND status = #{status} </if>" +
            "<if test='storageType != null and storageType != \"\"'> AND COALESCE(storage_type, 'cloud') = #{storageType} </if>" +
            "<if test='keyword != null and keyword != \"\"'> AND (filename LIKE CONCAT('%', #{keyword}, '%') OR display_name LIKE CONCAT('%', #{keyword}, '%')) </if>" +
            "<if test='conversationId != null'> AND id IN (SELECT DISTINCT file_asset_id FROM file_reference WHERE conversation_id = #{conversationId}) </if>" +
            "</script>")
    long countByUser(@Param("userId") Long userId,
                     @Param("keyword") String keyword,
                     @Param("status") String status,
                     @Param("storageType") String storageType,
                     @Param("conversationId") Long conversationId);

    /**
     * 统计按用户筛选条件下的文件占用空间合计（2026-09-21 分页改造：头部统计用）
     * <p>返回 Long 而非 long：COALESCE 保证无记录时为 0，避免 null 拆箱。</p>
     *
     * @param userId         用户ID（必填）
     * @param keyword        文件名/显示名模糊筛选（可空）
     * @param status         状态筛选（可空）
     * @param storageType    存储类型筛选：cloud/local（可空）
     * @param conversationId 按引用会话筛选（可空）
     * @return 满足条件的字节数合计（无记录为 0）
     */
    @Select("<script>" +
            "SELECT COALESCE(SUM(size), 0) FROM file_asset WHERE user_id = #{userId} " +
            "<if test='status != null and status != \"\"'> AND status = #{status} </if>" +
            "<if test='storageType != null and storageType != \"\"'> AND COALESCE(storage_type, 'cloud') = #{storageType} </if>" +
            "<if test='keyword != null and keyword != \"\"'> AND (filename LIKE CONCAT('%', #{keyword}, '%') OR display_name LIKE CONCAT('%', #{keyword}, '%')) </if>" +
            "<if test='conversationId != null'> AND id IN (SELECT DISTINCT file_asset_id FROM file_reference WHERE conversation_id = #{conversationId}) </if>" +
            "</script>")
    Long sumSizeByUser(@Param("userId") Long userId,
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
     * 查询指定 Provider 下的全部本地记录（2026-09-21 云端文件视图：与云端 Files API 清单比对用）
     * <p>跨用户全量（云端 Files API 为账号级空间，比对需覆盖所有本地记录）；
     * 仅取 file_id 非空记录（local 文档无远端 ID，不参与比对）；按 id 升序保证同 fileId 多记录时取序稳定。</p>
     *
     * @param providerCode Provider 编码
     * @return 资产列表
     */
    @Select("SELECT id, file_id, provider_code, user_id, filename, display_name, mime_type, size, local_path, storage_type, source, status, orphan_retry_count, expires_at, created_at " +
            "FROM file_asset WHERE provider_code = #{providerCode} AND file_id IS NOT NULL ORDER BY id ASC")
    @ResultMap("fileAssetResultMap")
    List<FileAsset> selectByProvider(@Param("providerCode") String providerCode);

    /**
     * 按 Provider + 远端 fileId 查询本地记录（2026-09-21 云端文件删除：联动清理本地用）
     *
     * @param providerCode Provider 编码
     * @param fileId       远端 Files API 文件 ID（file-api-...）
     * @return 匹配记录列表（防御性返回 List：正常唯一，异常数据下可能多条）
     */
    @Select("SELECT id, file_id, provider_code, user_id, filename, display_name, mime_type, size, local_path, storage_type, source, status, orphan_retry_count, expires_at, created_at " +
            "FROM file_asset WHERE provider_code = #{providerCode} AND file_id = #{fileId} ORDER BY id ASC")
    @ResultMap("fileAssetResultMap")
    List<FileAsset> selectByProviderAndFileId(@Param("providerCode") String providerCode, @Param("fileId") String fileId);

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
