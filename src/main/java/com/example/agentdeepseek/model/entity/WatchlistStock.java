package com.example.agentdeepseek.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WatchlistStock {
    private Long id;
    private Long groupId;
    private String tsCode;
    private String stockName;
    private Integer sortOrder;
    private LocalDateTime createdAt;
}
