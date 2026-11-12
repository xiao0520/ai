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

/** 发布后的重排适配契约；不指定尚未选择的供应商或模型。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RerankProfile(
    @JsonProperty(value = "profile", required = true)
    @NotNull @Valid ProfileRef profile,
    @JsonProperty(value = "providerCode", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String providerCode,
    @JsonProperty(value = "modelName", required = true)
    @NotNull @CodePointLength(min = 1, max = 255) String modelName,
    @JsonProperty(value = "apiHost", required = true)
    @NotNull @Pattern(regexp = "^https?://[^\\s?#]+$") @CodePointLength(max = 2048) String apiHost,
    @JsonProperty(value = "adapterCode", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String adapterCode,
    @JsonProperty(value = "credentialKey", required = true)
    @NotNull @Pattern(regexp = "^[A-Za-z0-9_.-]{1,100}$") String credentialKey,
    @JsonProperty(value = "inputMode", required = true)
    @NotNull InputMode inputMode,
    @JsonProperty(value = "maxDocumentTokens", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer maxDocumentTokens,
    @JsonProperty(value = "timeoutSeconds", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "300", inclusive = true) Integer timeoutSeconds
) {

    /** 保留契约定义的线枚举值。 */
    public enum InputMode {
        V_TEXT("TEXT"),
        V_MULTIMODAL("MULTIMODAL");
        private final String value;
        InputMode(String value) { this.value = value; }
        @JsonValue
        public String value() { return value; }
        @JsonCreator
        public static InputMode fromValue(String value) {
            for (InputMode candidate : values()) {
                if (candidate.value.equals(value)) { return candidate; }
            }
            throw new IllegalArgumentException("未知契约枚举");
        }
    }
}
