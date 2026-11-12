// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.CodePointLength;

/** 仅用于 RAG 改写的模型快照；普通聊天原 DTO 的数值 ID 不变。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RagChatModelProfile(
    @JsonProperty(value = "profile", required = true)
    @NotNull @Valid ProfileRef profile,
    @JsonProperty(value = "modelName", required = true)
    @NotNull @CodePointLength(min = 1, max = 255) String modelName,
    @JsonProperty(value = "providerCode", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String providerCode,
    @JsonProperty(value = "apiHost", required = true)
    @NotNull @Pattern(regexp = "^https?://[^\\s?#]+$") @CodePointLength(max = 2048) String apiHost,
    @JsonProperty(value = "apiMode", required = true)
    @NotNull ApiMode apiMode,
    @JsonProperty(value = "credentialKey", required = true)
    @NotNull @Pattern(regexp = "^[A-Za-z0-9_.-]{1,100}$") String credentialKey,
    @JsonProperty(value = "maxInputTokens", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer maxInputTokens,
    @JsonProperty(value = "maxOutputTokens", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer maxOutputTokens,
    @JsonProperty(value = "timeoutSeconds", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "300", inclusive = true) Integer timeoutSeconds
) {

    /** 保留契约定义的线枚举值。 */
    public enum ApiMode {
        V_CHAT_COMPLETIONS("CHAT_COMPLETIONS"),
        V_RESPONSES("RESPONSES");
        private final String value;
        ApiMode(String value) { this.value = value; }
        @JsonValue
        public String value() { return value; }
        @JsonCreator
        public static ApiMode fromValue(String value) {
            for (ApiMode candidate : values()) {
                if (candidate.value.equals(value)) { return candidate; }
            }
            throw new IllegalArgumentException("未知契约枚举");
        }
    }
}
