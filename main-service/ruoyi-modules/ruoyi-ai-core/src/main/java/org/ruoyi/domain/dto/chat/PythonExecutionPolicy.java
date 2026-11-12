package org.ruoyi.domain.dto.chat;

/**
 * Java 为本次模型调用下发的执行限制。
 *
 * @param maxInputTokens  最大输入 Token 数
 * @param maxOutputTokens 最大输出 Token 数
 * @param timeoutSeconds  整体调用超时秒数
 * @param maxTotalTokens  工具多轮调用可消耗的总 Token 上限；普通单次调用为空
 */
public record PythonExecutionPolicy(Integer maxInputTokens, Integer maxOutputTokens,
                                    Integer timeoutSeconds, Long maxTotalTokens) {
}