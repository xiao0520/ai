// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.hibernate.validator.constraints.CodePointLength;
import org.ruoyi.domain.dto.knowledge.RagDefaultValueDeserializer;

/** RAG 调用主体使用严格字段，原普通聊天模型保持兼容。  通过多重继承，在 ChatActor 字段基础上叠加 RagModel 的严格校验策略 （extra="forbid", strict=True），防止 Java 误传未知字段。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RagChatActor(
    @JsonProperty(value = "userId", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String userId,
    @JsonProperty(value = "roles", required = false) @JsonDeserialize(using = RagDefaultValueDeserializer.class)
    @NotNull @Size(max = 50) List<@NotNull String> roles
) {

    public RagChatActor {
        if (roles == null) { roles = List.of(); }
    }
}
