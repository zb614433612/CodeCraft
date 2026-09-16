package com.example.agentdeepseek.model.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * LLM Provider 余额信息VO
 * <p>
 * 返回给前端聊天页面展示。目前仅 DeepSeek 平台支持余额查询，
 * 对应官方接口 GET /user/balance
 * （https://api-docs.deepseek.com/zh-cn/api/get-user-balance）。
 * </p>
 */
@Data
@NoArgsConstructor
@Schema(description = "Provider 账户余额")
public class ProviderBalanceVO {

    @Schema(description = "Provider 编码", example = "deepseek")
    private String providerCode;

    @Schema(description = "当前账户是否有余额可供 API 调用")
    @JsonProperty("isAvailable")
    private Boolean isAvailable;

    @Schema(description = "余额明细列表（按币种）")
    private List<BalanceInfo> balanceInfos;

    @Schema(description = "查询时间戳（毫秒）", example = "1678886400000")
    private Long updatedAt;

    /**
     * 单币种余额明细
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
    }
}
