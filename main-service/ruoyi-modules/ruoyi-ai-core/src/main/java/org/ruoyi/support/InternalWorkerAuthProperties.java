package org.ruoyi.support;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Python Worker 调用 Java 内部资源接口的独立服务身份。 */
@Data
@Validated
@ConfigurationProperties(prefix = "knowledge.worker-auth")
public class InternalWorkerAuthProperties {
    private boolean enabled;
    @NotBlank @Size(min = 32, max = 4096) private String serviceToken;
    @NotBlank @Size(min = 32, max = 4096) private String signingKey;
    @NotBlank @Pattern(regexp = "^https?://[^\\s?#]+(?::[0-9]{1,5})?(?:/[^\\s?#]*)?$")
    private String resourceBaseUrl = "http://127.0.0.1:8080";
    @Min(5) @Max(300) private int ticketTtlSeconds = 60;
    @Min(1024) @Max(67108864) private int manifestMaxBytes = 16777216;
    @Min(1) @Max(900) private int maxAgeSeconds = 300;
}
