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
import java.util.List;
import org.hibernate.validator.constraints.CodePointLength;

/** 入库产物只在 Java 验收后发布；向量写完不等于可检索。  入库完成后 Python 上报此结果，Java 验证 manifest 后才激活 generation。 字段分为三组：   路由 — job_type + manifest 的定位和哈希   向量统计 — 文本 dense/sparse 和图片视觉向量的数量与维度   解析与用量 — 解析统计和模型调用用量 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record IngestionResult(
    @JsonProperty(value = "jobType", required = true)
    @NotNull JobType jobType,
    @JsonProperty(value = "manifestObjectId", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String manifestObjectId,
    @JsonProperty(value = "manifestHash", required = true)
    @NotNull @Pattern(regexp = "^[a-f0-9]{64}$") String manifestHash,
    @JsonProperty(value = "chunkCount", required = true)
    @NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer chunkCount,
    @JsonProperty(value = "denseCount", required = true)
    @NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer denseCount,
    @JsonProperty(value = "sparseCount", required = true)
    @NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer sparseCount,
    @JsonProperty(value = "visionCount", required = true)
    @NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer visionCount,
    @JsonProperty(value = "vectorDimensions", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "65536", inclusive = true) Integer vectorDimensions,
    @JsonProperty(value = "visionVectorDimensions", required = true)
    @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "65536", inclusive = true) Integer visionVectorDimensions,
    @JsonProperty(value = "parseStats", required = true)
    @NotNull @Valid ParseStats parseStats,
    @JsonProperty(value = "usageItems", required = true)
    @NotNull @Size(max = 1000) List<@NotNull @Valid ExecutionUsageItem> usageItems
) {

    /** 补充 Schema 无法表达的字段关联校验；缺失字段由 Bean Validation 处理。 */
    @JsonIgnore
    @AssertTrue(message = "契约字段关联不一致")
    public boolean isContractConsistent() {
        if (chunkCount == null || denseCount == null || sparseCount == null
            || visionCount == null || parseStats == null) { return true; }
        boolean visionShape = (visionCount == 0) == (visionVectorDimensions == null);
        return chunkCount > 0 && chunkCount.equals(denseCount) && chunkCount.equals(sparseCount)
            && visionCount <= chunkCount && visionShape
            && !(parseStats.imageCount() == 0 && visionCount != 0)
            && !Boolean.TRUE.equals(parseStats.truncated());
    }

    /** 保留契约定义的线枚举值。 */
    public enum JobType {
        V_INGESTION("INGESTION");
        private final String value;
        JobType(String value) { this.value = value; }
        @JsonValue
        public String value() { return value; }
        @JsonCreator
        public static JobType fromValue(String value) {
            for (JobType candidate : values()) {
                if (candidate.value.equals(value)) { return candidate; }
            }
            throw new IllegalArgumentException("未知契约枚举");
        }
    }
}
