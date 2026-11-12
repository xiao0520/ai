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

/** Java 为一个物理文档版本固化的处理与查询路由配置。  字段分为三组：   模式 — TEXT 或 MULTIMODAL，决定需要哪些模型   向量模型 — 文本（必选）和视觉（多模态必选）的 Embedding 配置   辅助模型 — 视觉处理和重排策略 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DocumentProcessingSnapshot(
    @JsonProperty(value = "configurationRevision", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer configurationRevision,
    @JsonProperty(value = "processingMode", required = true)
    @NotNull ProcessingMode processingMode,
    @JsonProperty(value = "textEmbedding", required = true)
    @NotNull @Valid EmbeddingProfile textEmbedding,
    @JsonProperty(value = "multimodalEmbedding", required = true)
    @Valid EmbeddingProfile multimodalEmbedding,
    @JsonProperty(value = "visionProfile", required = true)
    @Valid VisionProfile visionProfile,
    @JsonProperty(value = "rerankPolicy", required = true)
    @NotNull @Valid DocumentRerankPolicy rerankPolicy
) {

    /** 补充 Schema 无法表达的字段关联校验；缺失字段由 Bean Validation 处理。 */
    @JsonIgnore
    @AssertTrue(message = "契约字段关联不一致")
    public boolean isContractConsistent() {
        if (processingMode == null || rerankPolicy == null) { return true; }
        if (processingMode == ProcessingMode.V_TEXT) {
            return multimodalEmbedding == null && visionProfile == null
                && rerankPolicy.multimodalProfile() == null;
        }
        return multimodalEmbedding != null && visionProfile != null
            && (rerankPolicy.mode() != DocumentRerankPolicy.Mode.V_PROFILE
                || rerankPolicy.multimodalProfile() != null);
    }

    /** 保留契约定义的线枚举值。 */
    public enum ProcessingMode {
        V_TEXT("TEXT"),
        V_MULTIMODAL("MULTIMODAL");
        private final String value;
        ProcessingMode(String value) { this.value = value; }
        @JsonValue
        public String value() { return value; }
        @JsonCreator
        public static ProcessingMode fromValue(String value) {
            for (ProcessingMode candidate : values()) {
                if (candidate.value.equals(value)) { return candidate; }
            }
            throw new IllegalArgumentException("未知契约枚举");
        }
    }
}
