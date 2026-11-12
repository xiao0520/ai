// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/** 只提供脱敏阶段耗时和数量，跳过阶段不能伪装为已完成。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RetrievalStageStats(
    @JsonProperty(value = "stage", required = true)
    @NotNull Stage stage,
    @JsonProperty(value = "status", required = true)
    @NotNull Status status,
    @JsonProperty(value = "durationMs", required = true)
    @NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer durationMs,
    @JsonProperty(value = "inputCount", required = true)
    @NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer inputCount,
    @JsonProperty(value = "outputCount", required = true)
    @NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer outputCount
) {

    /** 保留契约定义的线枚举值。 */
    public enum Stage {
        V_DENSE("DENSE"),
        V_SPARSE("SPARSE"),
        V_RRF("RRF"),
        V_RERANK("RERANK"),
        V_CONTEXT("CONTEXT");
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

    /** 保留契约定义的线枚举值。 */
    public enum Status {
        V_COMPLETED("COMPLETED"),
        V_SKIPPED("SKIPPED");
        private final String value;
        Status(String value) { this.value = value; }
        @JsonValue
        public String value() { return value; }
        @JsonCreator
        public static Status fromValue(String value) {
            for (Status candidate : values()) {
                if (candidate.value.equals(value)) { return candidate; }
            }
            throw new IllegalArgumentException("未知契约枚举");
        }
    }
}
