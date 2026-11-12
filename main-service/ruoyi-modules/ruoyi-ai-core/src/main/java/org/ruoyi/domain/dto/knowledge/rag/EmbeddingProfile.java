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

/** 模型管理发布的向量执行快照；供应商能力由 Python 适配器注册表裁定。  字段分为三组：   标识 — 配置引用 + 模型定位   连接 — API 端点与凭证   能力 — 向量维度、输入模式、归一化、Token 与超时限制 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record EmbeddingProfile(
    @JsonProperty(value = "profile", required = true)
    @NotNull @Valid ProfileRef profile,
    @JsonProperty(value = "providerCode", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String providerCode,
    @JsonProperty(value = "modelName", required = true)
    @NotNull @CodePointLength(min = 1, max = 255) String modelName,
    @JsonProperty(value = "apiHost", required = true)
    @NotNull @Pattern(regexp = "^https?://[^\\s?#]+$") @CodePointLength(max = 2048) String apiHost,
    @JsonProperty(value = "apiMode", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String apiMode,
    @JsonProperty(value = "credentialKey", required = true)
    @NotNull @Pattern(regexp = "^[A-Za-z0-9_.-]{1,100}$") String credentialKey,
    @JsonProperty(value = "dimensions", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "65536", inclusive = true) Integer dimensions,
    @JsonProperty(value = "documentInputMode", required = true)
    @NotNull DocumentInputMode documentInputMode,
    @JsonProperty(value = "queryInputMode", required = true)
    @NotNull QueryInputMode queryInputMode,
    @JsonProperty(value = "normalized", required = true)
    @NotNull Boolean normalized,
    @JsonProperty(value = "maxInputTokens", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer maxInputTokens,
    @JsonProperty(value = "timeoutSeconds", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "300", inclusive = true) Integer timeoutSeconds
) {

    /** 保留契约定义的线枚举值。 */
    public enum DocumentInputMode {
        V_TEXT_SINGLE("TEXT_SINGLE"),
        V_MULTIMODAL_SINGLE("MULTIMODAL_SINGLE");
        private final String value;
        DocumentInputMode(String value) { this.value = value; }
        @JsonValue
        public String value() { return value; }
        @JsonCreator
        public static DocumentInputMode fromValue(String value) {
            for (DocumentInputMode candidate : values()) {
                if (candidate.value.equals(value)) { return candidate; }
            }
            throw new IllegalArgumentException("未知契约枚举");
        }
    }

    /** 保留契约定义的线枚举值。 */
    public enum QueryInputMode {
        V_TEXT_SINGLE("TEXT_SINGLE");
        private final String value;
        QueryInputMode(String value) { this.value = value; }
        @JsonValue
        public String value() { return value; }
        @JsonCreator
        public static QueryInputMode fromValue(String value) {
            for (QueryInputMode candidate : values()) {
                if (candidate.value.equals(value)) { return candidate; }
            }
            throw new IllegalArgumentException("未知契约枚举");
        }
    }
}
