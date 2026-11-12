// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** 每个成员绑定已发布的文档版本与 generation。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ManifestMember(
    @JsonProperty(value = "documentId", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String documentId,
    @JsonProperty(value = "documentVersionId", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String documentVersionId,
    @JsonProperty(value = "generationId", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String generationId
) {
}
