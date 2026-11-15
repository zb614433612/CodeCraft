package com.example.agentdeepseek.model.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Provider 能力视图 —— <b>前端唯一的能力真相源</b>（T9）
 *
 * <h3>为什么要把能力算在后端</h3>
 * <p>
 * 改动前同一套能力结论存在<b>四份副本</b>：
 * </p>
 * <ol>
 *   <li>后端各 service 的 if / switch 链（真正执行的地方）；</li>
 *   <li>{@code application.yml} 的 {@code vision-rules}；</li>
 *   <li>{@code ModelCapabilityConfig} 的代码默认值；</li>
 *   <li>前端 {@code provider-capability.ts} 的 350 行推导（{@code VISION_TEMPLATES} +
 *       5 段正则 + {@code deriveCapabilities}）。</li>
 * </ol>
 * <p>
 * 后果是「改一处、漏三处」，而漏掉<b>不报错</b>：界面说能发图、实际图片被静默丢弃；
 * 或界面说能查余额、点下去却报「缺凭证」。
 * 本 VO 把 ①②③ 收敛成 {@code ProviderRegistry} 一处后，由后端<b>算好结论再下发</b>，
 * 前端从「推导者」降级为「展示者」，副本数量从 4 → 1。
 * </p>
 *
 * <h3>为什么必须由后端判定凭证是否就绪</h3>
 * <p>
 * 凭证存在 Provider 的 {@code extraConfig} 里，而列表接口
 * {@code GET /api/llm-providers} 返回时会把密钥字段替换成 {@code ****} 掩码。
 * 前端只能判断「有掩码 = 已配置」，判断不了「配的到底是不是一对完整凭证」。
 * 后端读 DB 原始值，可以准确回答「<b>缺哪一个</b>」——
 * 这正是 {@link CapabilityItem#missingKeys} 存在的意义：不是笼统说「缺凭证」，
 * 而是直接告诉用户「请填写 secretKey」。
 * </p>
 *
 * <h3>三态语义（与前端 {@code CapabilityState} 逐条对齐，避免迁移时文案漂移）</h3>
 * <table border="1">
 *   <caption>state 取值</caption>
 *   <tr><th>state</th><th>含义</th><th>用户该怎么做</th></tr>
 *   <tr>
 *     <td>{@code on}</td><td>平台具备，且当前配置可用</td><td>直接用</td>
 *   </tr>
 *   <tr>
 *     <td>{@code warn}</td>
 *     <td>平台具备，但当前配置用不了（缺凭证 / 模型不支持 / 被中转挡住）</td>
 *     <td>补配置或切换模型；<b>不是「完全不支持」</b></td>
 *   </tr>
 *   <tr>
 *     <td>{@code off}</td><td>平台本身没有这个能力</td><td>换平台</td>
 *   </tr>
 * </table>
 * <p>
 * ⚠️ 特别注意 {@code warn} 与 {@code off} 的区别：{@code warn} 表示「能修好」，
 * {@code off} 表示「改配置也没用」。两者混为一谈会让用户白白折腾。
 * </p>
 */
@Data
public class ProviderCapabilityVO {

    /** 请求模板（{@code requestTemplate}；为空时已用 code 兜底） */
    private String template;

    /**
     * Provider 编码。
     * <p>模板级接口（{@code GET /api/llm-providers/templates}）为 {@code null}；
     * 实例级接口（{@code GET /api/llm-providers/{code}/capabilities}）为实际 code。</p>
     */
    private String providerCode;

    /** 中文展示名（来自 {@code ProviderRegistry} 的 display.label，前端不必再维护一份） */
    private String label;

    /** 配色档位（对应前端 {@code .is-*} 修饰类；空串表示默认） */
    private String iconTone;

    /** 模板下拉排序（小的在前） */
    private int order;

    /** 默认模型（hint 文案里会引用，便于用户知道「是哪个模型不行」） */
    private String defaultModel;

    /** 图片识别能力 */
    private CapabilityItem vision = new CapabilityItem();

    /** 官方联网搜索能力 */
    private CapabilityItem search = new CapabilityItem();

    /** 余额 / 额度查询能力 */
    private CapabilityItem balance = new CapabilityItem();

    /**
     * 云端文件上传（Files API）能力。
     * <p>前端此前没有这个概念（只在后端 FilesApiClientManager 里体现）。
     * 加进来是因为它直接决定「上传图片后文件放哪」：</p>
     * <ul>
     *   <li>{@code on}：有 Files API，可上传到平台云端（失败自动降级本地）；</li>
     *   <li>{@code warn}：未适配，<b>仅本地存储</b> + 注入时转 base64 —— 功能完全可用，
     *       只是文件不落云端。这不是缺陷，是 v3.1.4 定下的隐私口径（不向第三方云盘传用户文件）。</li>
     * </ul>
     */
    private CapabilityItem filesApi = new CapabilityItem();

    /** 计费模式：{@code TOKEN} / {@code QUOTA} / {@code FREE}（决定要不要显示金额） */
    private String pricingMode = "TOKEN";

    /** 计费模式的解释文案（QUOTA / FREE 时用来说明「为什么没有金额」） */
    private String pricingHint = "";

    /**
     * 单项能力的状态与说明。
     */
    @Data
    public static class CapabilityItem {

        /** 三态：{@code on} / {@code warn} / {@code off} */
        private String state = "off";

        /** 徽章短文案（如「联网搜索·通用抓取」） */
        private String label = "";

        /** 悬停说明：讲清「为什么是这个状态」以及「怎么修」 */
        private String hint = "";

        /**
         * 平台本身是否具备该能力（与 state 不同）。
         * <p>{@code platformSupported=false} → 无论怎么配都用不了（对应 {@code off}）；
         * {@code true} 但 {@code state=warn} → 补配置就能变成 {@code on}。</p>
         */
        private boolean platformSupported;

        /**
         * 缺失的凭证字段名（仅当「平台支持但缺凭证」时非空）。
         * <p>让提示能精确到「请填写 secretKey」，而不是笼统说「缺凭证」。</p>
         */
        private List<String> missingKeys = new ArrayList<>();

        // ==================== 便捷工厂（避免各处手写 set 链导致状态不一致） ====================

        public static CapabilityItem on(String label, String hint) {
            CapabilityItem item = new CapabilityItem();
            item.state = "on";
            item.label = label;
            item.hint = hint;
            item.platformSupported = true;
            return item;
        }

        public static CapabilityItem warn(String label, String hint, List<String> missingKeys) {
            CapabilityItem item = new CapabilityItem();
            item.state = "warn";
            item.label = label;
            item.hint = hint;
            item.platformSupported = true;
            item.missingKeys = missingKeys == null ? new ArrayList<>() : new ArrayList<>(missingKeys);
            return item;
        }

        public static CapabilityItem off(String label, String hint) {
            CapabilityItem item = new CapabilityItem();
            item.state = "off";
            item.label = label;
            item.hint = hint;
            item.platformSupported = false;
            return item;
        }
    }
}
