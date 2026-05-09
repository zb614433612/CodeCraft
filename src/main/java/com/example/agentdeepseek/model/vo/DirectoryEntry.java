package com.example.agentdeepseek.model.vo;

/**
 * 目录条目，用于目录浏览器
 */
public class DirectoryEntry {

    private String name;
    private String path;

    public DirectoryEntry() {}

    public DirectoryEntry(String name, String path) {
        this.name = name;
        this.path = path;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }
}
