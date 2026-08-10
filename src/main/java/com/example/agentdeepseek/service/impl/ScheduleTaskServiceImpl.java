package com.example.agentdeepseek.service.impl;

import com.example.agentdeepseek.mapper.ScheduleTaskMapper;
import com.example.agentdeepseek.model.entity.ScheduleTask;
import com.example.agentdeepseek.service.ScheduleTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class ScheduleTaskServiceImpl implements ScheduleTaskService {

    @Autowired
    private ScheduleTaskMapper taskMapper;

    @Override
    public List<ScheduleTask> getAllTasks() {
        return taskMapper.selectAll();
    }

    @Override
    public ScheduleTask createTask(ScheduleTask task) {
        if (task.getStatus() == null) task.setStatus("ENABLED");
        if (task.getMaxExecuteCount() == null) task.setMaxExecuteCount(100);
        if (task.getExecuteCount() == null) task.setExecuteCount(0);
        taskMapper.insert(task);
        log.info("创建定时任务: id={}, name={}, agentType={}", task.getId(), task.getName(), task.getAgentType());
        return task;
    }

    @Override
    public void updateTask(ScheduleTask task) {
        taskMapper.update(task);
        log.info("更新定时任务: id={}", task.getId());
    }

    @Override
    public void deleteTask(Long id) {
        taskMapper.delete(id);
        log.info("删除定时任务: id={}", id);
    }

    @Override
    public void enableTask(Long id) {
        ScheduleTask task = taskMapper.selectById(id);
        if (task == null) {
            log.warn("启用定时任务失败，任务不存在: id={}", id);
            return;
        }
        String cron = task.getCronExpression();
        if (cron != null && !cron.isEmpty()) {
            // cron 任务：若 execute_time 为空或已过期，重新计算下次执行时间，
            // 避免任务被禁用一段时间后重新启用时立即执行一次
            try {
                org.springframework.scheduling.support.CronExpression ce =
                        org.springframework.scheduling.support.CronExpression.parse(cron);
                java.time.ZonedDateTime next = ce.next(java.time.ZonedDateTime.now());
                if (next != null) {
                    LocalDateTime nextTime = next.toLocalDateTime();
                    if (task.getExecuteTime() == null || !task.getExecuteTime().isAfter(LocalDateTime.now())) {
                        taskMapper.updateExecuteTime(id, nextTime);
                        log.info("启用定时任务时重置下次执行时间: id={}, nextTime={}", id, nextTime);
                    }
                }
            } catch (Exception e) {
                log.warn("解析 cron 表达式失败，跳过执行时间重置: id={}, cron={}", id, cron);
            }
        }
        taskMapper.updateStatus(id, "ENABLED");
        log.info("启用定时任务: id={}", id);
    }

    @Override
    public void disableTask(Long id) {
        taskMapper.updateStatus(id, "DISABLED");
        log.info("禁用定时任务: id={}", id);
    }

    @Override
    public ScheduleTask getTaskById(Long id) {
        return taskMapper.selectById(id);
    }
}
