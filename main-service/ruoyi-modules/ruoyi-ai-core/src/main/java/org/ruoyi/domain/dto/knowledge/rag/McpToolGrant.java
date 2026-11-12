// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.CodePointLength;

/** 只描述本次可执行工具；凭证仅以别名传递。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record McpToolGrant(
    @JsonProperty(value = "serverId", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String serverId,
    @JsonProperty(value = "serverCode", required = true)
    @NotNull @CodePointLength(min = 1, max = 100) String serverCode,
    @JsonProperty(value = "endpoint", required = true)
    @NotNull @Pattern(regexp = "^https?://[^\\s?#]+$") @CodePointLength(max = 2048) String endpoint,
    @JsonProperty(value = "authType", required = true)
    @NotNull AuthType authType,
    @JsonProperty(value = "credentialKey", required = true)
    @Pattern(regexp = "^[A-Za-z0-9_.-]{1,100}$") String credentialKey,
    @JsonProperty(value = "connectTimeoutSeconds", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "60", inclusive = true) Integer connectTimeoutSeconds,
    @JsonProperty(value = "toolId", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String toolId,
    @JsonProperty(value = "toolName", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String toolName,
    @JsonProperty(value = "qualifiedName", required = true)
    @NotNull @CodePointLength(min = 1, max = 255) String qualifiedName,
    @JsonProperty(value = "inputSchema", required = true)
    @NotNull @CodePointLength(min = 2, max = 100000) String inputSchema,
    @JsonProperty(value = "timeoutSeconds", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "120", inclusive = true) Integer timeoutSeconds
) {

    /** 保留契约定义的线枚举值。 */
    public enum AuthType {
        V_NONE("NONE"),
        V_BEARER("BEARER");
        private final String value;
        AuthType(String value) { this.value = value; }
        @JsonValue
        public String value() { return value; }
        @JsonCreator
        public static AuthType fromValue(String value) {
            for (AuthType candidate : values()) {
                if (candidate.value.equals(value)) { return candidate; }
            }
            throw new IllegalArgumentException("未知契约枚举");
        }
    }
}
