// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigInteger;
import org.hibernate.validator.constraints.CodePointLength;
import org.ruoyi.domain.dto.knowledge.RagDefaultValueDeserializer;
import org.ruoyi.domain.dto.knowledge.RagIntegerSerializer;

/** 复用既有生成模型字段和默认值，仅收紧 RAG 边界的结构校验。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RagGenerationProfile(
    @JsonProperty(value = "id", required = true) @JsonSerialize(using = RagIntegerSerializer.class)
    @NotNull @DecimalMin(value = "0", inclusive = false) BigInteger id,
    @JsonProperty(value = "revision", required = true) @JsonSerialize(using = RagIntegerSerializer.class)
    @NotNull @DecimalMin(value = "0", inclusive = false) BigInteger revision,
    @JsonProperty(value = "modelName", required = true)
    @NotNull @CodePointLength(min = 1, max = 255) String modelName,
    @JsonProperty(value = "providerCode", required = true)
    @NotNull @CodePointLength(min = 1, max = 50) String providerCode,
    @JsonProperty(value = "apiHost", required = true)
    @NotNull @CodePointLength(min = 1, max = 2083) @Pattern(regexp = "^https?://[^\\s]+$") String apiHost,
    @JsonProperty(value = "apiMode", required = false) @JsonDeserialize(using = RagDefaultValueDeserializer.class)
    @NotNull ApiMode apiMode,
    @JsonProperty(value = "credentialKey", required = true)
    @NotNull @Pattern(regexp = "^[A-Za-z0-9_.-]{1,100}$") String credentialKey,
    @JsonProperty(value = "maxInputTokens", required = true)
    @NotNull @DecimalMax(value = "2000000", inclusive = true) @DecimalMin(value = "0", inclusive = false) Integer maxInputTokens,
    @JsonProperty(value = "maxOutputTokens", required = false)
    @DecimalMax(value = "2000000", inclusive = true) @DecimalMin(value = "0", inclusive = false) Integer maxOutputTokens
) {

    public RagGenerationProfile {
        if (apiMode == null) { apiMode = ApiMode.V_CHAT_COMPLETIONS; }
    }

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
