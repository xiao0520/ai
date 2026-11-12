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
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.CodePointLength;

/** 不可变索引配置；collection 与全部编码版本必须与 Java 发布记录一致。  字段分为三组：   集合定义 — 知识库归属、索引版本、collection 名称和距离度量   向量定义 — 文本 dense + 图片 dense + 文本 sparse 的名称和模型配置   处理链 — 解析、分割、分词、稀疏编码和清单的版本号 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record IndexSnapshot(
    @JsonProperty(value = "knowledgeId", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String knowledgeId,
    @JsonProperty(value = "indexVersion", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String indexVersion,
    @JsonProperty(value = "collectionName", required = true)
    @NotNull @Pattern(regexp = "^[A-Za-z0-9_-]{1,128}$") String collectionName,
    @JsonProperty(value = "distance", required = true)
    @NotNull Distance distance,
    @JsonProperty(value = "denseVectorName", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String denseVectorName,
    @JsonProperty(value = "denseVisionVectorName", required = true)
    @CodePointLength(min = 1, max = 128) String denseVisionVectorName,
    @JsonProperty(value = "sparseVectorName", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String sparseVectorName,
    @JsonProperty(value = "embedding", required = true)
    @NotNull @Valid EmbeddingProfile embedding,
    @JsonProperty(value = "multimodalEmbedding", required = true)
    @Valid EmbeddingProfile multimodalEmbedding,
    @JsonProperty(value = "parserRevision", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String parserRevision,
    @JsonProperty(value = "splitterRevision", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String splitterRevision,
    @JsonProperty(value = "tokenizerRevision", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String tokenizerRevision,
    @JsonProperty(value = "sparseRevision", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String sparseRevision,
    @JsonProperty(value = "sparseCorpusRevision", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String sparseCorpusRevision,
    @JsonProperty(value = "chunkSizeTokens", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer chunkSizeTokens,
    @JsonProperty(value = "chunkOverlapTokens", required = true)
    @NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer chunkOverlapTokens,
    @JsonProperty(value = "configHash", required = true)
    @NotNull @Pattern(regexp = "^[a-f0-9]{64}$") String configHash,
    @JsonProperty(value = "manifestRevision", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer manifestRevision
) {

    /** 补充 Schema 无法表达的字段关联校验；缺失字段由 Bean Validation 处理。 */
    @JsonIgnore
    @AssertTrue(message = "契约字段关联不一致")
    public boolean isContractConsistent() {
        if (denseVectorName == null || sparseVectorName == null || chunkOverlapTokens == null
            || chunkSizeTokens == null) { return true; }
        boolean nameCollision = denseVectorName.equals(sparseVectorName)
            || (denseVisionVectorName != null && (denseVisionVectorName.equals(denseVectorName)
                || denseVisionVectorName.equals(sparseVectorName)));
        return !nameCollision && (denseVisionVectorName == null) == (multimodalEmbedding == null)
            && chunkOverlapTokens < chunkSizeTokens;
    }

    /** 保留契约定义的线枚举值。 */
    public enum Distance {
        V_COSINE("COSINE"),
        V_DOT("DOT"),
        V_EUCLID("EUCLID");
        private final String value;
        Distance(String value) { this.value = value; }
        @JsonValue
        public String value() { return value; }
        @JsonCreator
        public static Distance fromValue(String value) {
            for (Distance candidate : values()) {
                if (candidate.value.equals(value)) { return candidate; }
            }
            throw new IllegalArgumentException("未知契约枚举");
        }
    }
}
