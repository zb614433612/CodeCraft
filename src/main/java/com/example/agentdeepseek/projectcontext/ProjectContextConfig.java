package com.example.agentdeepseek.projectcontext;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 项目知识库（.codecraft/）配置。
 *
 * <p>设计原则（关键）：<b>本功能不自动创建任何目录</b>。
 * 只有模型显式调用 {@code project_map action=bootstrap} 时才会落盘；
 * 本配置里的所有开关都不具备「触发创建」的能力，只能「关闭能力」。</p>
 *
 * <p>对应 application.yml 的 {@code codecraft.project-context.*} 配置块。</p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "codecraft.project-context")
public class ProjectContextConfig {

    /** 总开关：false 时工具仍可查询，但不会做任何注入（请求体与未启用前逐字节一致） */
    private boolean enabled = true;

    /** 是否把 INDEX.md 摘要注入到每轮请求的系统消息中（≤ budgetBootstrap 字符） */
    private boolean injectBootstrap = true;

    /** 是否在写类工具成功后追加上下文提示（写哪个模块、这个模块负责什么） */
    private boolean injectFileHint = true;

    /**
     * 是否在 {@code skill action=load} 结果末尾追加「项目规则片段」。
     *
     * <p>只匹配 key 前缀为 {@code skill.<技能 slug>.} / {@code skill.<技能 ID>.} /
     * {@code skill.all.} 的规则条目——即「这个技能在本项目里该怎么用」。
     * 不注入全部规则：规则是全局知识，全量塞进一次技能加载既浪费 token 又冲淡重点。</p>
     */
    private boolean injectSkillRules = true;

    /** 技能规则片段预算（字符数）；超预算时按规则文件中的先后顺序截断 */
    private int budgetSkillRule = 800;

    /** 常驻注入预算（字符数，约 650 token）；模块职责优先占用，代表符号只保留 1~2 个 */
    private int budgetBootstrap = 1100;

    /**
     * 文档模式下的注入预算（字符数）。
     * <p>工作目录可能一个代码文件都没有（例如桌面上的「V2.0需求文档」），
     * 这时索引就是「文件清单 + 每个文件的标题」，信息密度比模块表低但条目多，
     * 所以单独给一档预算（约 900 token）。</p>
     */
    private int budgetDocList = 1500;

    /**
     * 文档模式判定阈值：代码文件数 × 本值 &lt; 总文件数 时判定为「纯文档目录」。
     * <p>10 表示代码占比低于 10% 就走文档模式。CodeCraft 自身是 84%，
     * 纯 md 目录是 0%，都能正确落位。</p>
     */
    private int docModeCodeRatio = 10;

    /** 单个文件上下文提示预算（字符数） */
    private int budgetFileHint = 120;

    /** 扫描文件数上限（超出后截断，避免超大仓库拖慢） */
    private int maxFiles = 5000;

    /** 单文件大小上限（字节，超出跳过，避免扫压缩产物/大 JSON） */
    private long maxFileSize = 512L * 1024;

    /**
     * 模块数软上限。这里刻意设得宽（60）而不追求「合并到 24 个」——
     * 强制把最小的模块并进父目录会造出 {@code example(1)}、{@code main(4)} 这种碎片，
     * 反而让结构更难读。展示数量由注入预算自然控制，INDEX.md 里全列出来没有坏处。
     */
    private int maxModules = 60;

    /**
     * 模块划分阈值：某目录下文件数 ≤ max(moduleMinFiles, 总文件数 × 本值) 时不再下钻，收为一个模块。
     * 0.04 是实测调出来的：0.15 时 1030 个文件的项目里前端 99 个文件被并成一个大模块，
     * components / views / api 的区分全丢了；0.06 时仍会漏掉一级。
     */
    private double moduleFileRatio = 0.04;

    /** 模块划分的文件数下限（小项目不细分；10 个文件以下单独成模块基本没有信息量） */
    private int moduleMinFiles = 10;

    /** 单文件扫描的最大行数（只看前 N 行，大文件不深扫） */
    private int maxScanLinesPerFile = 2000;

    /** 单次 read 返回的最大字符数 */
    private int budgetRead = 6000;

    /**
     * rules.md 活跃条目上限（超出提示归档）。
     * 60 条是经验值：中等项目「模块间联动 + 构建/禁区约定」通常 20~40 条，留一倍余量。
     */
    private int maxRules = 60;

    /**
     * 单条规则最大字符数。
     * 400 字符（约 200 汉字）足够写清一条联动约束，例如
     * 「改前端 settings.ts 的 uiDensity 必须同步 AppearanceView 与 AppShell 的 IPC 调用」。
     * 早期试过 140，联动类规则全被截断，语义丢失严重。
     */
    private int maxRuleLength = 400;

    /** 模块职责描述（annotate）最大字符数：允许写多行联动说明，INDEX 只取首行 */
    private int maxNoteLength = 600;

    /**
     * 建库软闸门：项目可扫描文件数低于此值时，bootstrap 拒绝建库（除非 force=true）。
     * 8 而不是 20：纯文档仓库、需求库、单模块运维脚本集往往只有十来个文件，
     * 门槛太高会让「非代码项目」用不了这个功能。
     */
    private int bootstrapMinFiles = 8;
}
