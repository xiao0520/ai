// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 严格校验每条上下文消息，不改变原聊天输入协议。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RagChatInput(
    @JsonProperty(value = "messages", required = true)
    @NotNull @Size(min = 1, max = 50) List<@NotNull @Valid RagChatMessage> messages
) {
}
