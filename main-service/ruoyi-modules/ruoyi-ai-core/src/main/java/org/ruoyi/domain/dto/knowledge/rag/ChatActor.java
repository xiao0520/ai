// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.hibernate.validator.constraints.CodePointLength;
import org.ruoyi.domain.dto.knowledge.RagDefaultValueDeserializer;

/** 由 Java 签名下发的调用主体快照。  roles 列表由 Java 在请求时从会话权限中提取，Python 不做鉴权。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatActor(
    @JsonProperty(value = "userId", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String userId,
    @JsonProperty(value = "roles", required = false) @JsonDeserialize(using = RagDefaultValueDeserializer.class)
    @NotNull @Size(max = 50) List<@NotNull String> roles
) {

    public ChatActor {
        if (roles == null) { roles = List.of(); }
    }
}
