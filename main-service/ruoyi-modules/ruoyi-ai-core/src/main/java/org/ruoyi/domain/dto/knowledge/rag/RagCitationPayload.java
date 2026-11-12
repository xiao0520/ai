// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.CodePointLength;

/** citation 事件仅提供候选映射，不表示回答实际使用了该来源。  此事件在流式生成过程中发送，客户端可用于构建引用预览， 但最终生效的引用以 RagCompletedPayload.citations 为准。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RagCitationPayload(
    @JsonProperty(value = "requestId", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String requestId,
    @JsonProperty(value = "citation", required = true)
    @NotNull @Valid Citation citation
) {
}
