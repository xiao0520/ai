// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.hibernate.validator.constraints.CodePointLength;
import org.ruoyi.domain.dto.knowledge.RagDefaultValueDeserializer;

/** 启用 RAG 时的请求变体；普通聊天继续使用原 InternalChatStreamRequest。  与 InternalChatStreamRequest 的区别：   - actor/input/model_profile/execution_policy 使用 Rag 严格变体   - 新增 rag 字段携带检索范围与执行配置   - 最后一条消息必须是 user（由 validate_current_question 校验） */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RagChatStreamRequest(
    @JsonProperty(value = "requestId", required = true)
    @NotNull @CodePointLength(min = 6, max = 128) String requestId,
    @JsonProperty(value = "clientRequestId", required = true)
    @NotNull @CodePointLength(min = 6, max = 128) String clientRequestId,
    @JsonProperty(value = "sessionId", required = true)
    @NotNull @CodePointLength(min = 1, max = 64) String sessionId,
    @JsonProperty(value = "actor", required = true)
    @NotNull @Valid RagChatActor actor,
    @JsonProperty(value = "input", required = true)
    @NotNull @Valid RagChatInput input,
    @JsonProperty(value = "modelProfile", required = true)
    @NotNull @Valid RagGenerationProfile modelProfile,
    @JsonProperty(value = "executionPolicy", required = true)
    @NotNull @Valid RagExecutionPolicy executionPolicy,
    @JsonProperty(value = "rag", required = true)
    @NotNull @Valid RagExecutionScope rag,
    @JsonProperty(value = "tools", required = false) @JsonDeserialize(using = RagDefaultValueDeserializer.class)
    @NotNull @Size(max = 20) List<@NotNull @Valid McpToolGrant> tools
) {

    public RagChatStreamRequest {
        if (tools == null) { tools = List.of(); }
    }
}
