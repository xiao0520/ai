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

/** snapshotId 位于路径；游标绑定同一快照和 manifestRevision。  首次请求 cursor 传 null，后续翻页使用上一页返回的 nextCursor。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ManifestPageRequest(
    @JsonProperty(value = "contractVersion", required = true)
    @NotNull ContractVersion contractVersion,
    @JsonProperty(value = "requestId", required = true)
    @NotNull @CodePointLength(min = 6, max = 128) String requestId,
    @JsonProperty(value = "knowledgeId", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String knowledgeId,
    @JsonProperty(value = "indexVersion", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String indexVersion,
    @JsonProperty(value = "manifestRevision", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer manifestRevision,
    @JsonProperty(value = "cursor", required = true)
    @CodePointLength(min = 16, max = 4096) String cursor,
    @JsonProperty(value = "pageSize", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "1000", inclusive = true) Integer pageSize
) {

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
