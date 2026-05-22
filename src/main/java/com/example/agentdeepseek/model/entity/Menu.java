package com.example.agentdeepseek.model.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class Menu {
    private Long id;
    private String name;
    private String path;
    private String icon;
    private Long parentId;
    private Integer sortOrder;
    private String permissionCode;
    private String menuType;
    private Integer visible;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
