package com.example.agentdeepseek.model.vo;

import lombok.Data;

/**
 * 「某一轮用户问了什么」的轻量查询结果。
 *
 * <p>用途：改动回滚面板要按快照的 turnId 反查该轮提问（快照本身只有时间与文件数）。
 * 只取两列，避免把整条消息（content + reasoning + tool_calls，单条可达上百 KB）拉出来。</p>
 *
 * <p>为什么用 VO 而不是 {@code Map<String,Object>}：{@code conversation_message.content}
 * 是 LONGTEXT（H2 → CLOB），Map 方式取到的是 {@code JdbcClob} 对象，直接 String.valueOf
 * 会得到 "org.h2.jdbc.JdbcClob@1a2b"，必须额外做类型判断；声明成 String 字段后
 * MyBatis 会用 StringTypeHandler（底层 getString）取值，既干净也避免列别名大小写问题。</p>
 */
@Data
public class TurnPromptVO {

    /** 轮次ID（conversation_message.turn_id） */
    private String turnId;

    /** 该轮用户提问原文 */
    private String content;
}
