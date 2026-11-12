// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 仅报告当前 attempt 已停止，不能取消其他批次。  CANCELLED 由 Python 在消费到 KnowledgeJobControl 后发出，表示本 worker 已主动终止当前 attempt，不会继续消费同 attempt 的消息。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record KnowledgeCancelledEvent(
    @JsonProperty(value = "eventType", required = true)
    @NotNull EventType eventType,
    @JsonProperty(value = "usageItems", required = true)
    @NotNull @Size(max = 1000) List<@NotNull @Valid ExecutionUsageItem> usageItems
) implements KnowledgeJobEventEvent {

    /** 保留契约定义的线枚举值。 */
    public enum EventType {
        V_CANCELLED("CANCELLED");
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
