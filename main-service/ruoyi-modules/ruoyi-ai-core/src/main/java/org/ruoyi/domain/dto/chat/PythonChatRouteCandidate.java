package org.ruoyi.domain.dto.chat;

import java.math.BigDecimal;
import java.util.List;

/**
 * 下发给 Python 路由器的聊天模型候选快照，不包含供应商凭证和真实模型请求参数。
 *
 * @param modelId                 模型 ID
 * @param revision                已发布模型配置修订号
 * @param capabilities            规范化后的能力标签
 * @param maxInputTokens          最大输入 Token 数
 * @param inputCostPerMillion     每百万输入 Token 成本
 * @param outputCostPerMillion    每百万输出 Token 成本
 * @param qualityTier             质量等级，数字越大表示能力越强
 * @param latencyTier             延迟等级，数字越大表示延迟越高
 * @param priority                管理端配置的路由优先级
 */
public record PythonChatRouteCandidate(Long modelId, Integer revision, List<String> capabilities,
                                       Integer maxInputTokens, BigDecimal inputCostPerMillion,
                                       BigDecimal outputCostPerMillion, Integer qualityTier,
                                       Integer latencyTier, Integer priority) {
}