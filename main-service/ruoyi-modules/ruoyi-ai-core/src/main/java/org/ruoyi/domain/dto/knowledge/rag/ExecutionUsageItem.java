// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.CodePointLength;

/** Java 按调用标识去重计费；Python 不计算价格。  每条用量记录绑定唯一的 (stage, call_id, attempt_id, profile) 四元组， Java 端按此去重。measurement 通过 discriminator 按 unit 分发到具体计量类型。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ExecutionUsageItem(
    @JsonProperty(value = "stage", required = true)
    @NotNull Stage stage,
    @JsonProperty(value = "callId", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String callId,
    @JsonProperty(value = "attemptId", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String attemptId,
    @JsonProperty(value = "profile", required = true)
    @NotNull @Valid ProfileRef profile,
    @JsonProperty(value = "measurement", required = true)
    @NotNull @Valid ExecutionUsageItemMeasurement measurement
) {

    /** 保留契约定义的线枚举值。 */
    public enum Stage {
        V_VISION("VISION"),
        V_EMBEDDING("EMBEDDING"),
        V_RERANK("RERANK"),
        V_CHAT("CHAT");
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
