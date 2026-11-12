// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.CodePointLength;

/** 传递 Java 已校验的文档引用；不在消息中复制 OSS 密钥或临时下载票据。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record KnowledgeDocumentTask(
    @JsonProperty(value = "jobType", required = true)
    @NotNull JobType jobType,
    @JsonProperty(value = "documentId", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String documentId,
    @JsonProperty(value = "documentVersionId", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String documentVersionId,
    @JsonProperty(value = "ossId", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String ossId,
    @JsonProperty(value = "sourceHash", required = true)
    @NotNull @Pattern(regexp = "^[a-f0-9]{64}$") String sourceHash,
    @JsonProperty(value = "mediaType", required = true)
    @NotNull @CodePointLength(min = 1, max = 255) String mediaType,
    @JsonProperty(value = "sourceName", required = false)
    @CodePointLength(min = 1, max = 512) String sourceName,
    @JsonProperty(value = "fileSizeBytes", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "1073741824", inclusive = true) Integer fileSizeBytes,
    @JsonProperty(value = "targetIndexVersion", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String targetIndexVersion
) implements KnowledgeJobCommandTask {

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
