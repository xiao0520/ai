// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.CodePointLength;

/** 阶段分数为 null 表示未执行该阶段；Java 仍需做候选复核。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RetrievedChunk(
    @JsonProperty(value = "location", required = true)
    @NotNull @Valid SourceLocation location,
    @JsonProperty(value = "text", required = true)
    @NotNull @CodePointLength(min = 1, max = 40000) String text,
    @JsonProperty(value = "denseScore", required = true)
    @DecimalMin(value = "-1e+308", inclusive = true) @DecimalMax(value = "1e+308", inclusive = true) Double denseScore,
    @JsonProperty(value = "sparseScore", required = true)
    @DecimalMin(value = "-1e+308", inclusive = true) @DecimalMax(value = "1e+308", inclusive = true) Double sparseScore,
    @JsonProperty(value = "rrfScore", required = true)
    @DecimalMin(value = "-1e+308", inclusive = true) @DecimalMax(value = "1e+308", inclusive = true) Double rrfScore,
    @JsonProperty(value = "rerankScore", required = true)
    @DecimalMin(value = "-1e+308", inclusive = true) @DecimalMax(value = "1e+308", inclusive = true) Double rerankScore
) {
}
