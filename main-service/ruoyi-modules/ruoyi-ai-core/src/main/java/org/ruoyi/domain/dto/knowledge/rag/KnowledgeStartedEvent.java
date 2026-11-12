// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.constraints.NotNull;

/** 消费开始只推进 RUNNING，不表示任务成功。  STARTED 事件是 Python 消费到 Kafka 消息后的第一个回执，证明消息已被 worker 接收并开始处理，但尚未完成任何实质工作。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record KnowledgeStartedEvent(
    @JsonProperty(value = "eventType", required = true)
    @NotNull EventType eventType
) implements KnowledgeJobEventEvent {

    /** 保留契约定义的线枚举值。 */
    public enum EventType {
        V_STARTED("STARTED");
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
