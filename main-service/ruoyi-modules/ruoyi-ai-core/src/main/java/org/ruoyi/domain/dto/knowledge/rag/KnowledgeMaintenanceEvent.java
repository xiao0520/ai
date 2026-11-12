// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** 维护结果仍由 Java 对照当前成员关系验收后落库。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record KnowledgeMaintenanceEvent(
    @JsonProperty(value = "eventType", required = true)
    @NotNull EventType eventType,
    @JsonProperty(value = "result", required = true)
    @NotNull @Valid KnowledgeMaintenanceResult result
) implements KnowledgeJobEventEvent {

    /** 保留契约定义的线枚举值。 */
    public enum EventType {
        V_MAINTENANCE_COMPLETED("MAINTENANCE_COMPLETED");
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
