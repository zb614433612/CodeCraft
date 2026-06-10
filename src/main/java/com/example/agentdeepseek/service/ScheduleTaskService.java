package com.example.agentdeepseek.service;

import com.example.agentdeepseek.model.entity.ScheduleTask;

import java.util.List;

public interface ScheduleTaskService {

    List<ScheduleTask> getAllTasks();

    ScheduleTask createTask(ScheduleTask task);

    void updateTask(ScheduleTask task);

    void deleteTask(Long id);

    void enableTask(Long id);

    void disableTask(Long id);

    ScheduleTask getTaskById(Long id);
}
