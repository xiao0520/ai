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

/** pointId 与稳定来源一起验收，图片片段额外绑定原始媒体摘要。  每个 entry 对应 Qdrant 中的一个 point，记录其 ID、序号、模态和来源。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ArtifactManifestEntry(
    @JsonProperty(value = "pointId", required = true)
    @NotNull @Pattern(regexp = "^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$") String pointId,
    @JsonProperty(value = "chunkOrdinal", required = true)
    @NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer chunkOrdinal,
    @JsonProperty(value = "tokenCount", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer tokenCount,
    @JsonProperty(value = "modality", required = true)
    @NotNull Modality modality,
    @JsonProperty(value = "mediaHash", required = true)
    @Pattern(regexp = "^[a-f0-9]{64}$") String mediaHash,
    @JsonProperty(value = "location", required = true)
    @NotNull @Valid SourceLocation location
) {

    /** 补充 Schema 无法表达的字段关联校验；缺失字段由 Bean Validation 处理。 */
    @JsonIgnore
    @AssertTrue(message = "契约字段关联不一致")
    public boolean isContractConsistent() {
        if (modality == null) { return true; }
        return (modality == Modality.V_IMAGE) == (mediaHash != null);
    }

    /** 保留契约定义的线枚举值。 */
    public enum Modality {
        V_TEXT("TEXT"),
        V_IMAGE("IMAGE");
        private final String value;
        Modality(String value) { this.value = value; }
        @JsonValue
        public String value() { return value; }
        @JsonCreator
        public static Modality fromValue(String value) {
            for (Modality candidate : values()) {
                if (candidate.value.equals(value)) { return candidate; }
            }
            throw new IllegalArgumentException("未知契约枚举");
        }
    }
}
