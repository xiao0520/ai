// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/** 单个知识库在本次请求中冻结的召回、重排和命中阈值。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record KnowledgeRetrievalSettings(
    @JsonProperty(value = "denseEnabled", required = true)
    @NotNull Boolean denseEnabled,
    @JsonProperty(value = "sparseEnabled", required = true)
    @NotNull Boolean sparseEnabled,
    @JsonProperty(value = "recallCount", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "200", inclusive = true) Integer recallCount,
    @JsonProperty(value = "rrfK", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "1000", inclusive = true) Integer rrfK,
    @JsonProperty(value = "rerankEnabled", required = true)
    @NotNull Boolean rerankEnabled,
    @JsonProperty(value = "rerankProfile", required = true)
    @Valid RerankProfile rerankProfile,
    @JsonProperty(value = "rerankInputCount", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "200", inclusive = true) Integer rerankInputCount,
    @JsonProperty(value = "rerankOutputCount", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "100", inclusive = true) Integer rerankOutputCount,
    @JsonProperty(value = "relevanceThreshold", required = true)
    @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "1", inclusive = true) Double relevanceThreshold
) {

    /** 补充 Schema 无法表达的字段关联校验；缺失字段由 Bean Validation 处理。 */
    @JsonIgnore
    @AssertTrue(message = "契约字段关联不一致")
    public boolean isContractConsistent() {
        if (denseEnabled == null || sparseEnabled == null || recallCount == null
            || rerankEnabled == null || rerankInputCount == null || rerankOutputCount == null) {
            return true;
        }
        return (denseEnabled || sparseEnabled)
            && (!rerankEnabled || (rerankInputCount <= recallCount
                && rerankOutputCount <= rerankInputCount))
            && rerankEnabled == (rerankProfile != null)
            && (relevanceThreshold == null || denseEnabled || rerankEnabled);
    }
}
