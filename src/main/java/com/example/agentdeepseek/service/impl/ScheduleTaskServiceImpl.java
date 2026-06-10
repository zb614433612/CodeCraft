package com.example.agentdeepseek.service.impl;

import com.example.agentdeepseek.mapper.ScheduleTaskMapper;
import com.example.agentdeepseek.model.entity.ScheduleTask;
import com.example.agentdeepseek.service.ScheduleTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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
