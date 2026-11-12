// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/** 生产与检索测试共用的候选数量和上下文参数。  两阶段检索流水线：   DENSE_BM25_RRF_RERANK — 生产模式，dense + sparse → RRF 融合 → Rerank → 最终 Top-K   DEVELOPMENT_NO_RERANK — 开发模式，跳过 Rerank 阶段 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RetrievalProfile(
    @JsonProperty(value = "profile", required = true)
    @NotNull @Valid ProfileRef profile,
    @JsonProperty(value = "mode", required = true)
    @NotNull Mode mode,
    @JsonProperty(value = "denseTopK", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "200", inclusive = true) Integer denseTopK,
    @JsonProperty(value = "sparseTopK", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "200", inclusive = true) Integer sparseTopK,
    @JsonProperty(value = "rrfK", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "1000", inclusive = true) Integer rrfK,
    @JsonProperty(value = "rerankTopK", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "200", inclusive = true) Integer rerankTopK,
    @JsonProperty(value = "finalTopK", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "100", inclusive = true) Integer finalTopK,
    @JsonProperty(value = "neighborWindow", required = true)
    @NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "3", inclusive = true) Integer neighborWindow,
    @JsonProperty(value = "maxContextChunks", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "100", inclusive = true) Integer maxContextChunks
) {

    /** 补充 Schema 无法表达的字段关联校验；缺失字段由 Bean Validation 处理。 */
    @JsonIgnore
    @AssertTrue(message = "契约字段关联不一致")
    public boolean isContractConsistent() {
        return finalTopK == null || rerankTopK == null || maxContextChunks == null
            || (finalTopK <= rerankTopK && finalTopK <= maxContextChunks);
    }

    /** 保留契约定义的线枚举值。 */
    public enum Mode {
        V_DENSE_BM25_RRF_RERANK("DENSE_BM25_RRF_RERANK"),
        V_DEVELOPMENT_NO_RERANK("DEVELOPMENT_NO_RERANK");
        private final String value;
        Mode(String value) { this.value = value; }
        @JsonValue
        public String value() { return value; }
        @JsonCreator
        public static Mode fromValue(String value) {
            for (Mode candidate : values()) {
                if (candidate.value.equals(value)) { return candidate; }
            }
            throw new IllegalArgumentException("未知契约枚举");
        }
    }
}
