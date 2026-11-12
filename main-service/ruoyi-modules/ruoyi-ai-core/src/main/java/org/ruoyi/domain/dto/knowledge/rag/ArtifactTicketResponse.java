// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** 只返回与当前任务关联的单对象票据。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ArtifactTicketResponse(
    @JsonProperty(value = "ticket", required = true)
    @NotNull @Valid ArtifactTicket ticket
) {
}
