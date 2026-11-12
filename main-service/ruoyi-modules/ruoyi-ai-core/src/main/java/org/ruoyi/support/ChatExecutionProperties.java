package org.ruoyi.support;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Java 调用 Python 执行服务的部署配置。 */
@Data
@Validated
@ConfigurationProperties(prefix = "ai.execution")
public class ChatExecutionProperties {

    /** Python 内网服务地址。 */
    @NotBlank
    private String pythonBaseUrl;

    /** Java 与 Python 共用的内部服务令牌。 */
    @NotBlank
    private String internalServiceToken;

    /** Java 与 Python 共用的 HMAC 签名密钥。 */
    @NotBlank
    private String internalSigningKey;

    /** 连接 Python 的超时。 */
    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(10);

    /** 完整流的最长耗时。 */
    @NotNull
    private Duration requestTotalTimeout = Duration.ofSeconds(300);

    /** 用户历史消息的最大条数。 */
    @Min(2)
    private int maxHistoryMessages = 20;
}
