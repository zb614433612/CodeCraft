package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.model.entity.DbConnection;
import com.example.agentdeepseek.service.dbconnection.DbConnectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 外部数据库连接配置 API（Phase 20）
 * <p>
 * 管理 execute_sql 可访问的外部数据库连接（MySQL/PostgreSQL/H2）。
 * 安全约定：密码仅写不读——列表/详情一律不回显（hasPassword 布尔），编辑时留空=不修改。
 * 响应格式：统一 ApiResponse（{"code","message","data","timestamp"}），与前端 http-client
 * 的 request 封装（要求 code===200）契约一致。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/db-connections")
@Tag(name = "外部数据库连接", description = "外部业务库连接管理（execute_sql 指定连接用）")
public class DbConnectionController {

    private final DbConnectionService dbConnectionService;

    public DbConnectionController(DbConnectionService dbConnectionService) {
        this.dbConnectionService = dbConnectionService;
    }

    /** 可见连接列表（出参脱敏） */
    @Operation(summary = "连接列表")
    @GetMapping
    public ApiResponse<List<DbConnection>> list(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return ApiResponse.success(dbConnectionService.list(userId));
    }

    /** 创建连接（passwordPlain 明文密码由服务端加密存储） */
    @Operation(summary = "创建连接")
    @PostMapping
    public ApiResponse<DbConnection> create(@RequestBody DbConnection connection, HttpServletRequest request) {
        try {
            connection.setUserId((Long) request.getAttribute("userId"));
            return ApiResponse.success(dbConnectionService.create(connection));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 更新连接（passwordPlain 为空=不修改密码） */
    @Operation(summary = "更新连接")
    @PutMapping("/{id}")
    public ApiResponse<DbConnection> update(@PathVariable Long id, @RequestBody DbConnection connection,
                                            HttpServletRequest request) {
        try {
            connection.setId(id);
            return ApiResponse.success(dbConnectionService.update(connection));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 删除连接 */
    @Operation(summary = "删除连接")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        dbConnectionService.delete(id);
        return ApiResponse.success(null, "已删除");
    }

    /** 测试连接（已保存配置：传 id 即可，密码由服务端解密） */
    @Operation(summary = "测试已保存连接")
    @PostMapping("/{id}/test")
    public ApiResponse<Void> testSaved(@PathVariable Long id) {
        try {
            DbConnection c = new DbConnection();
            c.setId(id);
            String error = dbConnectionService.test(c);
            return error == null ? ApiResponse.success(null, "连接成功")
                    : ApiResponse.error(400, error);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 测试未保存连接（新增弹窗内先测试再保存：需传完整配置含 passwordPlain） */
    @Operation(summary = "测试未保存连接")
    @PostMapping("/test")
    public ApiResponse<Void> test(@RequestBody DbConnection connection) {
        try {
            String error = dbConnectionService.test(connection);
            return error == null ? ApiResponse.success(null, "连接成功")
                    : ApiResponse.error(400, error);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }
}
