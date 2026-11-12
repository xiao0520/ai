// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Java 授权后固定库、索引和修订号，不能独立笛卡尔积过滤。  每个 scope 绑定一个 (knowledge_id, index_version)，permission_revision 和 manifest_revision 用于 Java 端判断快照是否过期。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record KnowledgeScope(
    @JsonProperty(value = "knowledgeId", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String knowledgeId,
    @JsonProperty(value = "indexVersion", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String indexVersion,
    @JsonProperty(value = "permissionRevision", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer permissionRevision,
    @JsonProperty(value = "manifestRevision", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer manifestRevision,
    @JsonProperty(value = "retrievalSettings", required = true)
    @NotNull @Valid KnowledgeRetrievalSettings retrievalSettings
) {
}
