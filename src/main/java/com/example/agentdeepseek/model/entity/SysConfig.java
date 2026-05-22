package com.example.agentdeepseek.model.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class SysConfig {
    private Long id;
    private String configKey;
    private String configValue;
    private LocalDateTime updatedAt;
}
