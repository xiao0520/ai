// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.CodePointLength;

/** 文档图片/OCR 处理模型快照；只保存凭证别名和精确发布修订。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record VisionProfile(
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
    @JsonProperty(value = "maxInputTokens", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer maxInputTokens,
    @JsonProperty(value = "timeoutSeconds", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "300", inclusive = true) Integer timeoutSeconds
) {
}
