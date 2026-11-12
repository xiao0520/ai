// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/** Java 为单个入库 attempt 批准的模型 Token 上限。  仅 INGESTION 任务携带此预算；CLEANUP/RECONCILE 不消耗模型资源。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record KnowledgeExecutionBudget(
    @JsonProperty(value = "maxTokens", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer maxTokens
) {
}
