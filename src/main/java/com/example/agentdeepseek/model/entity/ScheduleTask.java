package com.example.agentdeepseek.model.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ScheduleTask {
    private Long id;
    private String name;
    private String agentType;
    private String instruction;
    private String cronExpression;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime executeTime;
    private String status;
    private LocalDateTime lastExecuteTime;
    private Long lastConversationId;
    private Integer executeCount;
    private Integer maxExecuteCount;
    private Long userId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
