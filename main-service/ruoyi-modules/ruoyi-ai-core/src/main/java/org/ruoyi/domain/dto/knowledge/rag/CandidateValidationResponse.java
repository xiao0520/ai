// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import org.hibernate.validator.constraints.CodePointLength;

/** 票据绑定允许集合及期限，不能凭此扩大原快照的范围。  allowed_candidates 是 candidates 的子集，仅包含通过复核的引用。 validation_ticket 是 Java 后续访问 OSS 的凭证。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CandidateValidationResponse(
    @JsonProperty(value = "scopeSnapshotId", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String scopeSnapshotId,
    @JsonProperty(value = "allowedCandidates", required = true)
    @NotNull @Size(max = 500) List<@NotNull @Valid SourceRef> allowedCandidates,
    @JsonProperty(value = "validationTicket", required = true)
    @NotNull @CodePointLength(min = 16, max = 4096) String validationTicket,
    @JsonProperty(value = "expiresAt", required = true)
    @NotNull OffsetDateTime expiresAt
) {
}
