package com.example.agentdeepseek.service.impl;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 任务事件信封：为 SSE 事件附加单调递增序号，支持重连游标续传（方案B）。
 * <p>
 * 背景：原实现将原始事件字符串直接放入 Replay Sink，重连时全部重放，
 * 导致已授权的 ask_user 重复弹窗、历史 content 分片重复追加。
 * 改造后所有事件统一经 AgentEventBus.emit() 封装为信封（自动分配 seq），
 * 重连时前端携带 cursor，后端 skipWhile(seq &lt;= cursor) 只推增量事件，
 * 历史内容由数据库（fetchMessages）兜底。
 * </p>
 */
@Getter
@AllArgsConstructor
public class TaskEventEnvelope {

    /** 单调递增事件序号（重连游标） */
    private final long seq;

    /** 原始 SSE 事件负载（JSON 字符串 / [DONE] 等） */
    private final String payload;
}
