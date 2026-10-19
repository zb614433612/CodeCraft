package com.example.agentdeepseek.service;

import com.example.agentdeepseek.mapper.ConversationMessageMapper;
import com.example.agentdeepseek.model.vo.TurnPromptVO;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

/**
 * 验证 {@link ConversationMessageMapper#selectUserPromptsByTurnIds} 的 MyBatis 注解 SQL。
 *
 * <p>这条 SQL 是「改动回滚面板显示该轮提问」的数据源，必须确认：</p>
 * <ul>
 *   <li>{@code <script>} + {@code <foreach>} 能被 MyBatis 正确解析（多元素 / 单元素）；</li>
 *   <li>H2 下 {@code turn_id AS turnId} 的别名大小写（H2 会转大写，Controller 需要忽略大小写取值）；</li>
 *   <li>{@code role = 'USER'} 与 {@code conversation_id} 过滤正确（不串会话）。</li>
 * </ul>
 *
 * <p>不启动 Spring 上下文：直接手工组装 MyBatis Configuration，用内存 H2 跑。</p>
 *
 * <pre>
 * mvn -q -DskipTests -DskipFrontend=true test-compile
 * mvn -q org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
 *     -Dexec.mainClass=com.example.agentdeepseek.service.SnapshotPromptMapperTest \
 *     -Dexec.classpathScope=test -DskipFrontend=true
 * </pre>
 */
public class SnapshotPromptMapperTest {

    private static final String URL =
            "jdbc:h2:mem:snap_prompt_mapper;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false";

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Class.forName("org.h2.Driver");
        try (Connection conn = DriverManager.getConnection(URL, "sa", "")) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE conversation_message ("
                        + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + "conversation_id BIGINT, role VARCHAR(20), content CLOB, turn_id VARCHAR(80))");
                st.execute("INSERT INTO conversation_message (conversation_id, role, content, turn_id) VALUES "
                        + "(2, 'USER', '帮我修一下回滚按钮', '1792345853036-a'),"
                        + "(2, 'ASSISTANT', '好的', NULL),"
                        + "(2, 'USER', '第二轮的提问', '1792345853036-b'),"
                        + "(3, 'USER', '别的会话的提问', '1792345853036-c'),"
                        + "(2, 'TOOL', '工具输出里也提到 1792345853036-a', '1792345853036-a')");
            }
        }

        UnpooledDataSource ds = new UnpooledDataSource("org.h2.Driver", URL, "sa", "");
        Environment env = new Environment("test", new JdbcTransactionFactory(), ds);
        Configuration cfg = new Configuration(env);
        // addMapper 会解析 @Select 里的 <script>/<foreach>，语法有错这里就会抛
        cfg.addMapper(ConversationMessageMapper.class);

        try (SqlSession session = new SqlSessionFactoryBuilder().build(cfg).openSession(true)) {
            ConversationMessageMapper mapper = session.getMapper(ConversationMessageMapper.class);

            List<TurnPromptVO> rows =
                    mapper.selectUserPromptsByTurnIds(2L, List.of("1792345853036-a", "1792345853036-b"));
            check("多元素 IN 返回 2 行（TOOL 行被 role 过滤）", rows.size() == 2);
            check("turnId / content 均能取到（String 类型处理器吃掉 CLOB）",
                    rows.get(0).getTurnId() != null && rows.get(0).getContent() != null);
            check("content 是文本而非 JdbcClob.toString()",
                    rows.get(0).getContent().contains("回滚按钮"));
            check("第一条 turnId 正确", "1792345853036-a".equals(rows.get(0).getTurnId()));

            List<TurnPromptVO> single =
                    mapper.selectUserPromptsByTurnIds(2L, List.of("1792345853036-b"));
            check("单元素 IN 返回 1 行", single.size() == 1);

            List<TurnPromptVO> otherSession =
                    mapper.selectUserPromptsByTurnIds(2L, List.of("1792345853036-c"));
            check("会话隔离：查不到别的会话的轮次", otherSession.isEmpty());
        }

        System.out.println();
        System.out.println("结果: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }
}
