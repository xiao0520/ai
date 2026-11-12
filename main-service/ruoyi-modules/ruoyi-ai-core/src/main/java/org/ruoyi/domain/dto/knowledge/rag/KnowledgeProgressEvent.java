// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/** 有限进度和续租信号，不承载正文或供应商原始响应。  progress 为 0-99 的百分比，不代表精确完成度，仅用于 UI 展示和 lease 续约判断。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record KnowledgeProgressEvent(
    @JsonProperty(value = "eventType", required = true)
    @NotNull EventType eventType,
    @JsonProperty(value = "stage", required = true)
    @NotNull Stage stage,
    @JsonProperty(value = "progress", required = true)
    @NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "99", inclusive = true) Integer progress
) implements KnowledgeJobEventEvent {

    /** 保留契约定义的线枚举值。 */
    public enum EventType {
        V_PROGRESS("PROGRESS");
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

    /** 保留契约定义的线枚举值。 */
    public enum Stage {
        V_PREPARING("PREPARING"),
        V_PARSING("PARSING"),
        V_EMBEDDING("EMBEDDING"),
        V_WRITING("WRITING"),
        V_VALIDATING("VALIDATING"),
        V_CLEANING("CLEANING"),
        V_RECONCILING("RECONCILING");
        private final String value;
        Stage(String value) { this.value = value; }
        @JsonValue
        public String value() { return value; }
        @JsonCreator
        public static Stage fromValue(String value) {
            for (Stage candidate : values()) {
                if (candidate.value.equals(value)) { return candidate; }
            }
            throw new IllegalArgumentException("未知契约枚举");
        }
    }
}
