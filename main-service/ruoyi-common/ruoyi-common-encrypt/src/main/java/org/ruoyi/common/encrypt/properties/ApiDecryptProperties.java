package org.ruoyi.common.encrypt.properties;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * API 解密属性配置类。
 *
 * @author xiaoqh
 */
@Data
@Validated
@ConfigurationProperties(prefix = "api-decrypt")
public class ApiDecryptProperties {

    /**
     * 加密开关
     */
    private Boolean enabled = Boolean.FALSE;

    /**
     * 头部标识
     */
    @NotBlank
    private String headerFlag = "encrypt-key";

    /**
     * 响应加密公钥
     */
    @NotBlank
    private String publicKey;

    /**
     * 请求解密私钥
     */
    @NotBlank
    private String privateKey;

}

