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
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import org.hibernate.validator.constraints.CodePointLength;

/** 单文档单批次不可变清单；文件哈希由 Java 按收到的原始字节计算。  清单是入库产物验收的核心凭证，包含：   - 路由标识 — job/attempt/generation/knowledge 的全链路追踪   - 索引配置 — index_version / config_hash / 向量维度   - 条目列表 — 每个 chunk 的完整定位和模态信息 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ArtifactManifest(
    @JsonProperty(value = "contractVersion", required = true)
    @NotNull ContractVersion contractVersion,
    @JsonProperty(value = "jobId", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String jobId,
    @JsonProperty(value = "attemptId", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String attemptId,
    @JsonProperty(value = "generationId", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String generationId,
    @JsonProperty(value = "knowledgeId", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String knowledgeId,
    @JsonProperty(value = "indexVersion", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String indexVersion,
    @JsonProperty(value = "documentId", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String documentId,
    @JsonProperty(value = "documentVersionId", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String documentVersionId,
    @JsonProperty(value = "configHash", required = true)
    @NotNull @Pattern(regexp = "^[a-f0-9]{64}$") String configHash,
    @JsonProperty(value = "sourceHash", required = true)
    @NotNull @Pattern(regexp = "^[a-f0-9]{64}$") String sourceHash,
    @JsonProperty(value = "vectorDimensions", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "65536", inclusive = true) Integer vectorDimensions,
    @JsonProperty(value = "visionVectorDimensions", required = true)
    @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "65536", inclusive = true) Integer visionVectorDimensions,
    @JsonProperty(value = "createdAt", required = true)
    @NotNull OffsetDateTime createdAt,
    @JsonProperty(value = "entries", required = true)
    @NotNull @Size(min = 1, max = 100000) List<@NotNull @Valid ArtifactManifestEntry> entries
) {

    /** 补充 Schema 无法表达的字段关联校验；缺失字段由 Bean Validation 处理。 */
    @JsonIgnore
    @AssertTrue(message = "契约字段关联不一致")
    public boolean isContractConsistent() {
        if (entries == null || entries.stream().anyMatch(java.util.Objects::isNull)) { return true; }
        boolean hasImages = entries.stream().anyMatch(
            entry -> entry.modality() == ArtifactManifestEntry.Modality.V_IMAGE);
        return hasImages == (visionVectorDimensions != null)
            && entries.stream().map(ArtifactManifestEntry::pointId).distinct().count() == entries.size()
            && entries.stream().map(ArtifactManifestEntry::chunkOrdinal).distinct().count() == entries.size()
            && entries.stream().mapToInt(ArtifactManifestEntry::chunkOrdinal).min().orElse(0) == 0
            && entries.stream().mapToInt(ArtifactManifestEntry::chunkOrdinal).max().orElse(-1)
                == entries.size() - 1;
    }

    /** 保留契约定义的线枚举值。 */
    public enum ContractVersion {
        V_RAG_V1("rag-v1");
        private final String value;
        ContractVersion(String value) { this.value = value; }
        @JsonValue
        public String value() { return value; }
        @JsonCreator
        public static ContractVersion fromValue(String value) {
            for (ContractVersion candidate : values()) {
                if (candidate.value.equals(value)) { return candidate; }
            }
            throw new IllegalArgumentException("未知契约枚举");
        }
    }
}
