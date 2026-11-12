package org.ruoyi.domain.dto.chat;

/**
 * Java 发布给 Python 的确定性路由策略。
 *
 * @param revision          策略修订号，响应必须原样返回
 * @param strategy          路由策略编码
 * @param fallbackModelId   没有规则命中时使用的候选模型 ID
 */
public record PythonChatRoutePolicy(Integer revision, String strategy, Long fallbackModelId) {
}