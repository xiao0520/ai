// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import org.hibernate.validator.constraints.CodePointLength;

/** nextCursor 为 null 才是完整分页结束；空成员不得变成无过滤查询。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ManifestPageResponse(
    @JsonProperty(value = "scopeSnapshotId", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String scopeSnapshotId,
    @JsonProperty(value = "knowledgeId", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String knowledgeId,
    @JsonProperty(value = "indexVersion", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String indexVersion,
    @JsonProperty(value = "manifestRevision", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer manifestRevision,
    @JsonProperty(value = "members", required = true)
    @NotNull @Size(max = 1000) List<@NotNull @Valid ManifestMember> members,
    @JsonProperty(value = "nextCursor", required = true)
    @CodePointLength(min = 16, max = 4096) String nextCursor,
    @JsonProperty(value = "expiresAt", required = true)
    @NotNull OffsetDateTime expiresAt
) {
}
