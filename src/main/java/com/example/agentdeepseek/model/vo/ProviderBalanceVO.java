package com.example.agentdeepseek.model.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * LLM Provider 余额信息VO
 * <p>
 * 返回给前端聊天页面展示。目前支持：
 * <ul>
 *   <li>DeepSeek —— 官方 GET /user/balance
 *       （https://api-docs.deepseek.com/zh-cn/api/get-user-balance）</li>
 *   <li>火山引擎（方舟）—— 费用中心 OpenAPI QueryBalanceAcct（需账号级 AK/SK，见 extraConfig）</li>
 * </ul>
 * </p>
 */
@Data
@NoArgsConstructor
@Schema(description = "Provider 账户余额")
public class ProviderBalanceVO {

    @Schema(description = "Provider 编码", example = "deepseek")
    private String providerCode;

    @Schema(description = "余额类型：balance=账户余额（金额），quota=套餐额度（百分比/次数）", example = "balance")
    private String balanceType;

    @Schema(description = "当前账户是否有余额可供 API 调用")
    @JsonProperty("isAvailable")
    private Boolean isAvailable;

    @Schema(description = "余额明细列表（按币种）")
    private List<BalanceInfo> balanceInfos;

    @Schema(description = "查询时间戳（毫秒）", example = "1678886400000")
    private Long updatedAt;

    /**
     * 单币种余额明细
     * <p>
     * 前三个字段（currency/totalBalance/grantedBalance/toppedUpBalance）为各平台通用语义；
     * 后续扩展字段仅在特定平台（如火山引擎）返回，前端按需展示。
     * </p>
     */
    @Data
    @Schema(description = "单币种余额明细")
    public static class BalanceInfo {

        @Schema(description = "货币（CNY/USD）", example = "CNY")
        private String currency;

        @Schema(description = "总的可用余额（含赠金和充值余额）", example = "110.00")
        private String totalBalance;

        @Schema(description = "未过期的赠金余额", example = "10.00")
        private String grantedBalance;

        @Schema(description = "充值余额", example = "100.00")
        private String toppedUpBalance;

        // ===== 扩展字段（目前火山引擎返回） =====

        @Schema(description = "现金余额（火山引擎 CashBalance）", example = "83.01")
        private String cashBalance;

        @Schema(description = "信控额度（火山引擎 CreditLimit）", example = "0.00")
        private String creditLimit;

        @Schema(description = "冻结金额（火山引擎 FreezeAmount）", example = "5.00")
        private String freezeAmount;

        @Schema(description = "欠费金额（火山引擎 ArrearsBalance）", example = "0.00")
        private String arrearsBalance;

        @Schema(description = "账号 ID（火山引擎 AccountID）", example = "210000000")
        private String accountId;

        // ===== 套餐额度字段（MiniMax Token Plan 等 quota 类型返回） =====

        @Schema(description = "剩余额度百分比（0~100，quota 类型有效）", example = "67.5")
        private Double remainingPercent;

        @Schema(description = "剩余次数（quota 类型有效）", example = "135")
        private Long remainingCount;

        @Schema(description = "总额次数（quota 类型有效）", example = "200")
        private Long totalCount;

        @Schema(description = "额度重置时间（epoch ms，quota 类型有效）", example = "1678886400000")
        private Long resetAt;

        @Schema(description = "套餐名称（quota 类型有效）", example = "Token Plan 标准版")
        private String planName;
    }
}
