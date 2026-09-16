package com.example.agentdeepseek.service.files.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Files API 列表分页对象（跨厂商归一化）
 * <p>
 * OpenAI 兼容风格响应：{object:"list", data:[...], first_id, last_id, has_more}
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FilePage {

    /** 当前页文件列表 */
    private List<FileObject> data = new ArrayList<>();

    /** 本页第一个文件 ID（游标） */
    private String firstId;

    /** 本页最后一个文件 ID（下一页请求的 after 游标） */
    private String lastId;

    /** 是否还有更多（true 时可用 lastId 作为 after 继续翻页） */
    private boolean hasMore;
}
