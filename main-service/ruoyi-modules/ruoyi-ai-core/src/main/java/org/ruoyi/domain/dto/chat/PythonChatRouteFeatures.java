package org.ruoyi.domain.dto.chat;

/**
 * Java 根据当前请求核定的路由特征。
 *
 * @param ragEnabled             是否需要知识库检索
 * @param hasImage               是否包含图片输入
 * @param toolRequired           是否需要调用 Agent 工具
 * @param estimatedInputTokens   Java 估算的输入 Token 数，仅用于候选窗口筛选
 */
public record PythonChatRouteFeatures(boolean ragEnabled, boolean hasImage, boolean toolRequired,
                                      long estimatedInputTokens) {
}