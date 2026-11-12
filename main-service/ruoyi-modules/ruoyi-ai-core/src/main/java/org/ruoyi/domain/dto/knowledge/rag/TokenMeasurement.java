// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/** 有实际或估算 Token 用量时才发送数量。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TokenMeasurement(
    @JsonProperty(value = "unit", required = true)
    @NotNull Unit unit,
    @JsonProperty(value = "quality", required = true)
    @NotNull Quality quality,
    @JsonProperty(value = "inputTokens", required = true)
    @NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer inputTokens,
    @JsonProperty(value = "outputTokens", required = true)
    @NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer outputTokens
) implements ExecutionUsageItemMeasurement {

    /** 保留契约定义的线枚举值。 */
    public enum Unit {
        V_TOKENS("TOKENS");
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
    public enum Quality {
        V_ACTUAL("ACTUAL"),
        V_ESTIMATED("ESTIMATED");
        private final String value;
        Quality(String value) { this.value = value; }
        @JsonValue
        public String value() { return value; }
        @JsonCreator
        public static Quality fromValue(String value) {
            for (Quality candidate : values()) {
                if (candidate.value.equals(value)) { return candidate; }
            }
            throw new IllegalArgumentException("未知契约枚举");
        }
    }
}
