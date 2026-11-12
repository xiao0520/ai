// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigInteger;
import org.ruoyi.domain.dto.knowledge.RagIntegerSerializer;

/** 完成事件保留原聊天用量字段；未知用量仍可为 null。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RagGenerationUsage(
    @JsonProperty(value = "inputTokens", required = false) @JsonSerialize(using = RagIntegerSerializer.class)
    @DecimalMin(value = "0", inclusive = true) BigInteger inputTokens,
    @JsonProperty(value = "outputTokens", required = false) @JsonSerialize(using = RagIntegerSerializer.class)
    @DecimalMin(value = "0", inclusive = true) BigInteger outputTokens,
    @JsonProperty(value = "totalTokens", required = false) @JsonSerialize(using = RagIntegerSerializer.class)
    @DecimalMin(value = "0", inclusive = true) BigInteger totalTokens
) {
}
