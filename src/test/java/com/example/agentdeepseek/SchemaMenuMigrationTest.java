package com.example.agentdeepseek;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * schema.sql 菜单结构整理迁移测试（H2 内存库）
 * <p>
 * 全量执行 src/main/resources/schema.sql（模拟应用启动时的 sql.init：mode=always + continue-on-error），
 * 验证「侧边栏菜单结构整理（2026-09-23）」迁移段的效果与幂等性。三轮覆盖：
 * <ol>
 *   <li>首跑：新库初始化路径（插入旧值 → 迁移段收敛到目标态）；</li>
 *   <li>二跑：重复启动路径（条件式 UPDATE 幂等，无重复/漂移）；</li>
 *   <li>老库模拟：回退到旧值 + 删除 user 角色断链 → 再跑 → 收敛 + 断链修复。</li>
 * </ol>
 * 运行：mvn test-compile exec:java -Dexec.mainClass=com.example.agentdeepseek.SchemaMenuMigrationTest -Dexec.classpathScope=test
 */
public class SchemaMenuMigrationTest {

    static int passed = 0;
    static int failed = 0;

    /** 目标态：菜单 id → {menu_type, sort_order, name} */
    static final Map<Long, String[]> EXPECTED = new LinkedHashMap<>();

    static {
        EXPECTED.put(4L, new String[]{"LINK", "1", "AI 助手"});
        EXPECTED.put(11L, new String[]{"AI", "1", "智能体"});
        EXPECTED.put(12L, new String[]{"AI", "2", "技能"});
        EXPECTED.put(15L, new String[]{"AI", "3", "MCP 服务器"});
        EXPECTED.put(9L, new String[]{"AI", "4", "定时任务"});
        EXPECTED.put(16L, new String[]{"AI", "5", "踩坑经验"});
        EXPECTED.put(17L, new String[]{"TOOL", "1", "数据库连接"});
        EXPECTED.put(18L, new String[]{"TOOL", "2", "文件管理"});
        EXPECTED.put(13L, new String[]{"TOOL", "3", "设备互联"});
        EXPECTED.put(8L, new String[]{"SYSTEM", "1", "模型配置"});
        EXPECTED.put(10L, new String[]{"SYSTEM", "2", "运行日志"});
        EXPECTED.put(14L, new String[]{"SYSTEM", "3", "数据管理"});
        EXPECTED.put(7L, new String[]{"MANAGE", "1", "个人信息"});
        EXPECTED.put(5L, new String[]{"MANAGE", "2", "用户管理"});
        EXPECTED.put(6L, new String[]{"MANAGE", "3", "菜单权限管理"});
    }

    public static void main(String[] args) throws Exception {
        System.out.println("═══════════════════════════════════════════");
        System.out.println("  schema.sql 菜单迁移测试（H2 内存库）");
        System.out.println("  全量执行建库脚本 + 目标态断言 + 幂等/老库验证");
        System.out.println("═══════════════════════════════════════════\n");

        try (Connection conn = DriverManager.getConnection(
                "jdbc:h2:mem:schemaMenuTest;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1", "sa", "")) {

            // ========== 第 1 轮：首跑（新库初始化 → 迁移收敛） ==========
            System.out.println("---- 第 1 轮：全量执行 schema.sql（新库首跑）----");
            runSchema(conn);
            verifyTargetState(conn, "首跑");

            // ========== 第 2 轮：重复执行（模拟重启，验证幂等） ==========
            System.out.println("\n---- 第 2 轮：再次全量执行（重启幂等验证）----");
            runSchema(conn);
            verifyTargetState(conn, "二跑");

            // ========== 第 3 轮：模拟老库（回退旧值 + 断链 → 再跑 → 修复验证） ==========
            System.out.println("\n---- 第 3 轮：回退为老库状态后执行（老库迁移验证）----");
            try (Statement st = conn.createStatement()) {
                // 回退菜单到旧值（分组 SETTING；id=8/13 恢复旧名）
                st.execute("UPDATE sys_menu SET menu_type = 'SETTING', " +
                        "name = CASE id WHEN 13 THEN '协作' WHEN 8 THEN '配置' ELSE name END, " +
                        "sort_order = 99 WHERE id IN (8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18)");
                st.execute("UPDATE sys_menu SET sort_order = 4 WHERE id = 4");
                st.execute("UPDATE sys_menu SET sort_order = 7 WHERE id = 7");
                // 删除 user 角色的 AI 助手分配（模拟历史断链）
                st.execute("DELETE FROM sys_role_menu WHERE role_id = 2 AND menu_id = 4");
            }
            // 老库态确认：SETTING=11 条、user 无 id=4
            assertCount(conn, "老库态 SETTING 应为 11", "SELECT COUNT(*) FROM sys_menu WHERE menu_type = 'SETTING'", 11);
            assertCount(conn, "老库态 user 无 id=4", "SELECT COUNT(*) FROM sys_role_menu WHERE role_id = 2 AND menu_id = 4", 0);
            runSchema(conn);
            verifyTargetState(conn, "老库迁移");
        }

        System.out.println("\n═══════════════════════════════════════════");
        System.out.println("  通过 " + passed + " / 失败 " + failed);
        System.out.println("═══════════════════════════════════════════");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 全量执行 schema.sql（复刻应用启动 sql.init：continue-on-error 容错） */
    static void runSchema(Connection conn) {
        ClassPathResource resource = new ClassPathResource("schema.sql");
        ScriptUtils.executeSqlScript(conn, new EncodedResource(resource, StandardCharsets.UTF_8),
                true, true, "--", ";", "/*", "*/");
    }

    /** 断言 15 个菜单的目标态 + 组计数 + SETTING 清零 + user 断链修复 */
    static void verifyTargetState(Connection conn, String round) throws Exception {
        for (Map.Entry<Long, String[]> e : EXPECTED.entrySet()) {
            long id = e.getKey();
            String[] exp = e.getValue();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT menu_type, sort_order, name FROM sys_menu WHERE id = " + id)) {
                if (!rs.next()) {
                    fail(round + " id=" + id + " 菜单不存在");
                    continue;
                }
                String actualType = rs.getString(1);
                String actualSort = String.valueOf(rs.getInt(2));
                String actualName = rs.getString(3);
                if (Objects.equals(exp[0], actualType) && Objects.equals(exp[1], actualSort)
                        && Objects.equals(exp[2], actualName)) {
                    pass(round + " id=" + id + " → " + actualType + "/" + actualSort + "/" + actualName);
                } else {
                    fail(round + " id=" + id + " 期望 " + exp[0] + "/" + exp[1] + "/" + exp[2]
                            + "，实际 " + actualType + "/" + actualSort + "/" + actualName);
                }
            }
        }
        // 组计数
        assertCount(conn, round + " LINK 组数量", "SELECT COUNT(*) FROM sys_menu WHERE menu_type = 'LINK'", 1);
        assertCount(conn, round + " AI 组数量", "SELECT COUNT(*) FROM sys_menu WHERE menu_type = 'AI'", 5);
        assertCount(conn, round + " TOOL 组数量", "SELECT COUNT(*) FROM sys_menu WHERE menu_type = 'TOOL'", 3);
        assertCount(conn, round + " SYSTEM 组数量", "SELECT COUNT(*) FROM sys_menu WHERE menu_type = 'SYSTEM'", 3);
        assertCount(conn, round + " MANAGE 组数量", "SELECT COUNT(*) FROM sys_menu WHERE menu_type = 'MANAGE'", 3);
        // SETTING 清零 + 菜单总数
        assertCount(conn, round + " SETTING 残留", "SELECT COUNT(*) FROM sys_menu WHERE menu_type = 'SETTING'", 0);
        assertCount(conn, round + " 菜单总数", "SELECT COUNT(*) FROM sys_menu", 15);
        // user 角色断链修复（AI 助手 = id 4，COUNT 天然验证不重复）
        assertCount(conn, round + " user 角色含 AI 助手", "SELECT COUNT(*) FROM sys_role_menu WHERE role_id = 2 AND menu_id = 4", 1);
    }

    static void assertCount(Connection conn, String name, String sql, int expected) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            int actual = rs.getInt(1);
            if (actual == expected) {
                pass(name + "（=" + actual + "）");
            } else {
                fail(name + "，期望 " + expected + "，实际 " + actual);
            }
        }
    }

    static void pass(String msg) {
        passed++;
        System.out.println("✅ " + msg);
    }

    static void fail(String msg) {
        failed++;
        System.out.println("❌ " + msg);
    }
}
