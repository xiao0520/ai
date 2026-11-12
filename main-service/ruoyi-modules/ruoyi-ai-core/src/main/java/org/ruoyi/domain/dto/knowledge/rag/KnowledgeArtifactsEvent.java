// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** 技术产物就绪后仍须 Java 验收；不能据此直接激活索引。  ARTIFACTS_READY 表示向量已写入 Qdrant 且清单已上传 OSS，但 generation 在 Java 执行候选复核并激活前仍为不可检索状态。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record KnowledgeArtifactsEvent(
    @JsonProperty(value = "eventType", required = true)
    @NotNull EventType eventType,
    @JsonProperty(value = "result", required = true)
    @NotNull @Valid IngestionResult result
) implements KnowledgeJobEventEvent {

    /** 保留契约定义的线枚举值。 */
    public enum EventType {
        V_ARTIFACTS_READY("ARTIFACTS_READY");
        private final String value;
        EventType(String value) { this.value = value; }
        @JsonValue
        public String value() { return value; }
        @JsonCreator
        public static EventType fromValue(String value) {
            for (EventType candidate : values()) {
                if (candidate.value.equals(value)) { return candidate; }
            }
            throw new IllegalArgumentException("未知契约枚举");
        }
    }
}
