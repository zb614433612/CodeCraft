package com.example.agentdeepseek;

import com.example.agentdeepseek.model.vo.SqlExecuteResult;
import com.example.agentdeepseek.service.dbconnection.SqlExecutor;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Objects;

/**
 * SqlExecutor 集成测试 — H2 内存库验证 SQL 检查与执行（含多语句）
 * 无需 Spring 上下文，直接实例化组件测试（风格与项目现有 main 测试类一致）。
 */
public class SqlExecutorTest {

    static final SqlExecutor executor = new SqlExecutor();
    static int passed = 0;
    static int failed = 0;

    static final int MAX_ROWS = 200;

    public static void main(String[] args) throws Exception {
        System.out.println("═══════════════════════════════════════════");
        System.out.println("  SqlExecutor 集成测试（H2 内存库）");
        System.out.println("  — SQL 检查（严格/宽松双模式）与多语句执行");
        System.out.println("═══════════════════════════════════════════\n");

        try (Connection conn = DriverManager.getConnection(
                "jdbc:h2:mem:sqlExecTest;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1")) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE demo_user (id INT PRIMARY KEY, name VARCHAR(50), status VARCHAR(20))");
            }

            // ========== 检查：严格模式（AI 工具） ==========
            test("T1.1 严格模式拒绝空 SQL", () -> {
                SqlExecutor.CheckResult r = executor.checkStrict("   ");
                assertTrue(!r.ok() && r.error() == SqlExecutor.CheckError.EMPTY, "空 SQL 应被拒绝");
            });
            test("T1.2 严格模式拒绝多语句（1 个分号的双语句）", () -> {
                SqlExecutor.CheckResult r = executor.checkStrict("SELECT 1; SELECT 2");
                assertTrue(!r.ok() && r.error() == SqlExecutor.CheckError.MULTIPLE_STATEMENTS, "多语句应被严格模式拒绝");
            });
            test("T1.3 严格模式允许单语句尾部单个分号", () -> {
                SqlExecutor.CheckResult r = executor.checkStrict("SELECT 1;");
                assertTrue(r.ok() && "SELECT".equals(r.sqlType()), "尾部单分号仍为单语句");
            });
            test("T1.4 严格模式拒绝非 SELECT/写操作（SHOW）", () -> {
                SqlExecutor.CheckResult r = executor.checkStrict("SHOW TABLES");
                assertTrue(!r.ok() && r.error() == SqlExecutor.CheckError.UNKNOWN_TYPE, "SHOW 应被严格模式拒绝");
            });

            // ========== 检查：宽松模式（管理页） ==========
            test("T2.1 宽松模式允许多条 INSERT", () -> {
                SqlExecutor.CheckResult r = executor.checkUi(
                        "INSERT INTO demo_user VALUES (1,'a','on'); INSERT INTO demo_user VALUES (2,'b','off')");
                assertTrue(r.ok(), "多语句应被宽松模式放行");
            });
            test("T2.2 宽松模式允许 SHOW / 任意语句", () -> {
                SqlExecutor.CheckResult r = executor.checkUi("SHOW TABLES");
                assertTrue(r.ok(), "SHOW 应被宽松模式放行");
            });
            test("T2.3 宽松模式识别写操作类型", () -> {
                SqlExecutor.CheckResult r = executor.checkUi("UPDATE demo_user SET status='x' WHERE id=1");
                assertTrue(r.ok() && "WRITE".equals(r.sqlType()), "UPDATE 应识别为 WRITE");
            });

            // ========== 执行：单条 ==========
            test("T3.1 单条 INSERT（1 条语句）", () -> {
                SqlExecuteResult r = executor.execute(conn, "INSERT INTO demo_user VALUES (1, 'Alice', 'active')", "test", MAX_ROWS);
                assertEq(1, r.getStatementCount(), "statementCount");
                assertEq(1, r.getResults().get(0).getAffectedRows(), "affectedRows");
            });
            test("T3.2 SELECT 查询结果（列名与值类型）", () -> {
                SqlExecuteResult r = executor.execute(conn, "SELECT id, name, status FROM demo_user WHERE id = 1", "test", MAX_ROWS);
                SqlExecuteResult.StatementResult s = r.getResults().get(0);
                assertEq("SELECT", s.getSqlType(), "sqlType");
                assertEq(1, s.getTotalRows(), "totalRows");
                assertTrue(s.getColumns().contains("name"), "columns 应含 name");
                assertEq("Alice", s.getRows().get(0).get("name"), "name 值");
                assertEq(1L, s.getRows().get(0).get("id"), "id 值（整型归一化为 Long）");
            });

            // ========== 执行：多语句（管理页核心诉求） ==========
            test("T4.1 两条 INSERT（分号分隔）→ 2 个结果块", () -> {
                SqlExecuteResult r = executor.execute(conn,
                        "INSERT INTO demo_user VALUES (2, 'Bob', 'active'); INSERT INTO demo_user VALUES (3, 'Cindy', 'off')",
                        "test", MAX_ROWS);
                assertEq(2, r.getStatementCount(), "statementCount");
                assertEq(1, r.getResults().get(0).getAffectedRows(), "第 1 条影响行数");
                assertEq(1, r.getResults().get(1).getAffectedRows(), "第 2 条影响行数");
            });
            test("T4.2 三条 INSERT（含尾分号）→ 3 个结果块", () -> {
                SqlExecuteResult r = executor.execute(conn,
                        "INSERT INTO demo_user VALUES (4, 'Dave', 'on'); INSERT INTO demo_user VALUES (5, 'Eve', 'off'); INSERT INTO demo_user VALUES (6, 'Frank', 'on');",
                        "test", MAX_ROWS);
                assertEq(3, r.getStatementCount(), "statementCount");
            });
            test("T4.3 混合语句：INSERT + SELECT → 结果类型各归其位", () -> {
                SqlExecuteResult r = executor.execute(conn,
                        "INSERT INTO demo_user VALUES (7, 'Grace', 'on'); SELECT COUNT(*) AS cnt FROM demo_user",
                        "test", MAX_ROWS);
                assertEq(2, r.getStatementCount(), "statementCount");
                assertEq("WRITE", r.getResults().get(0).getSqlType(), "第 1 条应为 WRITE");
                assertEq("SELECT", r.getResults().get(1).getSqlType(), "第 2 条应为 SELECT");
            });
            test("T4.4 双 SELECT → 2 个查询结果块（各自行数据）", () -> {
                SqlExecuteResult r = executor.execute(conn,
                        "SELECT id FROM demo_user WHERE id = 1; SELECT name FROM demo_user WHERE id = 1",
                        "test", MAX_ROWS);
                assertEq(2, r.getStatementCount(), "statementCount");
                assertEq("SELECT", r.getResults().get(0).getSqlType(), "第 1 条应为 SELECT");
                assertEq("SELECT", r.getResults().get(1).getSqlType(), "第 2 条应为 SELECT");
                assertEq("Alice", r.getResults().get(1).getRows().get(0).get("name"), "第 2 条查询值");
            });
            test("T4.5 多语句第 2 条失败 → 错误定位到第 2 条", () -> {
                try {
                    executor.execute(conn,
                            "INSERT INTO demo_user VALUES (100, 'X', 'on'); SELECT * FROM no_such_table_abc",
                            "test", MAX_ROWS);
                    throw new AssertionError("应抛异常");
                } catch (SqlExecutor.SqlExecutionException expected) {
                    assertTrue(expected.getMessage().contains("第 2 条"), "错误消息应定位到第 2 条，实际=" + expected.getMessage());
                }
            });
            test("T4.6 字符串字面量内的分号不拆分", () -> {
                SqlExecuteResult r = executor.execute(conn,
                        "INSERT INTO demo_user VALUES (8, 'a;b;c', 'on')", "test", MAX_ROWS);
                assertEq(1, r.getStatementCount(), "字面量内分号不应拆分为多条");
            });

            // ========== 执行：行数截断 ==========
            test("T5.1 SELECT 超过 maxRows → truncated=true", () -> {
                SqlExecuteResult r = executor.execute(conn, "SELECT * FROM demo_user", "test", 2);
                SqlExecuteResult.StatementResult s = r.getResults().get(0);
                assertEq(2, s.getTotalRows(), "totalRows 应为 maxRows");
                assertTrue(Boolean.TRUE.equals(s.getTruncated()), "应标记 truncated");
            });
            test("T5.2 SELECT 未超 maxRows → truncated 不为 true", () -> {
                SqlExecuteResult r = executor.execute(conn, "SELECT * FROM demo_user WHERE id = 1", "test", MAX_ROWS);
                SqlExecuteResult.StatementResult s = r.getResults().get(0);
                assertTrue(!Boolean.TRUE.equals(s.getTruncated()), "不应标记 truncated");
            });
            test("T5.3 无结果 SELECT → 0 行", () -> {
                SqlExecuteResult r = executor.execute(conn, "SELECT * FROM demo_user WHERE id = 99999", "test", MAX_ROWS);
                assertEq(0, r.getResults().get(0).getTotalRows(), "totalRows");
            });

            // ========== 执行：异常与边界 ==========
            test("T6.1 空 SQL → IllegalArgumentException", () -> {
                try {
                    executor.execute(conn, "  ", "test", MAX_ROWS);
                    throw new AssertionError("应抛 IllegalArgumentException");
                } catch (IllegalArgumentException expected) {
                    // 预期
                }
            });
            test("T6.2 语法错误 → 抛异常（消息含第 1 条定位）", () -> {
                try {
                    executor.execute(conn, "SELECT * FROM table_not_exists_xyz", "test", MAX_ROWS);
                    throw new AssertionError("应抛异常");
                } catch (SqlExecutor.SqlExecutionException expected) {
                    assertTrue(expected.getMessage() != null && !expected.getMessage().isEmpty(), "异常消息不应为空");
                    assertTrue(expected.getMessage().contains("第 1 条"), "错误消息应定位到第 1 条，实际=" + expected.getMessage());
                }
            });
        }

        System.out.println("\n═══════════════════════════════════════════");
        System.out.println("  通过 " + passed + " / 失败 " + failed);
        System.out.println("═══════════════════════════════════════════");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== 测试辅助 ====================

    interface TestCase {
        void run() throws Exception;
    }

    static void test(String name, TestCase tc) {
        try {
            tc.run();
            passed++;
            System.out.println("✅ " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("❌ " + name + " — " + t.getMessage());
        }
    }

    static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }

    static void assertEq(Object expected, Object actual, String field) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(field + " 期望=" + expected + "，实际=" + actual);
        }
    }
}
