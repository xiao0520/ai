// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigInteger;
import org.ruoyi.domain.dto.knowledge.RagIntegerSerializer;

/** RAG 生成预算由 Java 核定；工具多轮执行另带总 Token 上限。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RagExecutionPolicy(
    @JsonProperty(value = "maxInputTokens", required = true) @JsonSerialize(using = RagIntegerSerializer.class)
    @NotNull @DecimalMin(value = "0", inclusive = false) BigInteger maxInputTokens,
    @JsonProperty(value = "maxOutputTokens", required = true)
    @NotNull @DecimalMax(value = "2000000", inclusive = true) @DecimalMin(value = "0", inclusive = false) Integer maxOutputTokens,
    @JsonProperty(value = "timeoutSeconds", required = true)
    @NotNull @DecimalMax(value = "300", inclusive = true) @DecimalMin(value = "0", inclusive = false) Integer timeoutSeconds,
    @JsonProperty(value = "maxTotalTokens", required = false) @JsonSerialize(using = RagIntegerSerializer.class)
    @DecimalMin(value = "0", inclusive = false) BigInteger maxTotalTokens
) {
}
