// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.constraints.NotNull;

/** 供应商未报告用量时不使用零占位。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record UnknownMeasurement(
    @JsonProperty(value = "unit", required = true)
    @NotNull Unit unit,
    @JsonProperty(value = "reason", required = true)
    @NotNull Reason reason
) implements ExecutionUsageItemMeasurement {

    /** 保留契约定义的线枚举值。 */
    public enum Unit {
        V_UNKNOWN("UNKNOWN");
        private final String value;
        Unit(String value) { this.value = value; }
        @JsonValue
        public String value() { return value; }
        @JsonCreator
        public static Unit fromValue(String value) {
            for (Unit candidate : values()) {
                if (candidate.value.equals(value)) { return candidate; }
            }
            throw new IllegalArgumentException("未知契约枚举");
        }
    }

    /** 保留契约定义的线枚举值。 */
    public enum Reason {
        V_NOT_REPORTED("NOT_REPORTED"),
        V_INTERRUPTED("INTERRUPTED");
        private final String value;
        Reason(String value) { this.value = value; }
        @JsonValue
        public String value() { return value; }
        @JsonCreator
        public static Reason fromValue(String value) {
            for (Reason candidate : values()) {
                if (candidate.value.equals(value)) { return candidate; }
            }
            throw new IllegalArgumentException("未知契约枚举");
        }
    }
}
