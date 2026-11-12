package org.ruoyi.domain.dto.chat;

/**
 * Java 下发给 Python 的聊天模型发布快照，不包含真实供应商密钥。
 *
 * @param id              模型 ID
 * @param revision        已发布配置修订号
 * @param modelName       供应商侧模型调用标识
 * @param providerCode    模型供应商编码
 * @param apiHost         模型服务基础地址
 * @param apiMode         模型 API 协议模式
 * @param credentialKey   Python 本地安全配置中的凭证别名
 * @param maxInputTokens  最大输入 Token 数
 * @param maxOutputTokens 本次允许生成的最大输出 Token 数
 */
public record PythonModelProfile(Long id, Integer revision, String modelName, String providerCode,
                                 String apiHost, String apiMode, String credentialKey,
                                 Integer maxInputTokens, Integer maxOutputTokens) {
}