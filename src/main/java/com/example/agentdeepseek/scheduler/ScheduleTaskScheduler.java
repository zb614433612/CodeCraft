package com.example.agentdeepseek.scheduler;

import com.example.agentdeepseek.mapper.ConversationMapper;
import com.example.agentdeepseek.mapper.ScheduleTaskMapper;
import com.example.agentdeepseek.model.entity.Conversation;
import com.example.agentdeepseek.model.entity.ScheduleTask;
import com.example.agentdeepseek.service.DeepSeekService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;

@Component
@Slf4j
public class ScheduleTaskScheduler {

    @Autowired
    private ScheduleTaskMapper taskMapper;

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private DeepSeekService deepSeekService;

    @Autowired
    private javax.sql.DataSource dataSource;

    @PostConstruct
    public void init() {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS schedule_task (" +
                    "  id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "  name VARCHAR(100) NOT NULL," +
                    "  agent_type VARCHAR(50) NOT NULL," +
                    "  agent_config_id BIGINT," +
                    "  instruction CLOB NOT NULL," +
                    "  cron_expression VARCHAR(100)," +
                    "  execute_time TIMESTAMP," +
                    "  status VARCHAR(20) DEFAULT 'ENABLED'," +
                    "  last_execute_time TIMESTAMP," +
                    "  last_conversation_id BIGINT," +
                    "  execute_count INT DEFAULT 0," +
                    "  max_execute_count INT DEFAULT 0," +
                    "  user_id BIGINT NOT NULL," +
                    "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")");
            // 兼容旧表：添加 agent_config_id 列
            try { stmt.execute("ALTER TABLE schedule_task ADD COLUMN agent_config_id BIGINT"); }
            catch (Exception ignored) { /* 列已存在 */ }
            log.info("定时任务表初始化完成");
        } catch (Exception e) {
            log.warn("定时任务表初始化异常: {}", e.getMessage());
        }
    }

    @Scheduled(fixedRate = 30000)
    public void scanAndExecute() {
        List<ScheduleTask> dueTasks = taskMapper.selectDueTasks();
        if (dueTasks.isEmpty()) return;

        log.info("定时任务扫描: 发现 {} 个到期任务", dueTasks.size());
        for (ScheduleTask task : dueTasks) {
            try {
                executeTask(task);
            } catch (Exception e) {
                log.error("执行定时任务失败: taskId={}, name={}", task.getId(), task.getName(), e);
            }
        }
    }

    @Transactional
    public void executeTask(ScheduleTask task) {
        // 1. 创建新会话
        Conversation conv = new Conversation();
        conv.setName(task.getName().length() > 20 ? task.getName().substring(0, 20) : task.getName());
        conv.setUserId(task.getUserId());
        conv.setAgentType(task.getAgentType());
        conv.setAgentConfigId(task.getAgentConfigId());
        conv.setCreatedAt(LocalDateTime.now());
        conv.setUpdatedAt(LocalDateTime.now());
        conversationMapper.insert(conv);
        Long conversationId = conv.getId();

        // 2. 更新任务统计（消息由 streamChat 内部自动保存，避免重复）
        taskMapper.updateExecuteInfo(task.getId(), conversationId);

        log.info("定时任务已触发: taskId={}, name={}, conversationId={}", task.getId(), task.getName(), conversationId);

        // 4. 执行后处理：防止下次扫描重复触发
        String cronExpr = task.getCronExpression();
        if (cronExpr != null && !cronExpr.isEmpty()) {
            // cron 任务：计算下次执行时间
            try {
                CronExpression cron = CronExpression.parse(cronExpr);
                ZonedDateTime next = cron.next(ZonedDateTime.now());
                if (next != null) {
                    taskMapper.updateExecuteTime(task.getId(), next.toLocalDateTime());
                    log.info("定时任务下次执行时间: taskId={}, nextTime={}", task.getId(), next.toLocalDateTime());
                } else {
                    taskMapper.updateStatus(task.getId(), "DISABLED");
                    log.info("定时任务cron已过期，已禁用: taskId={}", task.getId());
                }
            } catch (Exception e) {
                log.warn("cron表达式解析失败，已禁用任务: taskId={}, cron={}", task.getId(), cronExpr);
                taskMapper.updateStatus(task.getId(), "DISABLED");
            }
        } else {
            // 一次性或立即执行任务：执行后禁用
            taskMapper.updateStatus(task.getId(), "DISABLED");
            log.info("一次性定时任务执行完毕，已禁用: taskId={}", task.getId());
        }

        // 5. 异步调用 AI 处理
        deepSeekService.processConversationAsync(conversationId, task.getInstruction(), task.getAgentType(), task.getAgentConfigId());
    }
}
