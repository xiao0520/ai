// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/** 文本偏移按 Unicode 码点，区间为左闭右开。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TextRangeLocator(
    @JsonProperty(value = "kind", required = true)
    @NotNull Kind kind,
    @JsonProperty(value = "startOffset", required = true)
    @NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer startOffset,
    @JsonProperty(value = "endOffset", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer endOffset
) implements SourceLocationLocator {

    /** 补充 Schema 无法表达的字段关联校验；缺失字段由 Bean Validation 处理。 */
    @JsonIgnore
    @AssertTrue(message = "契约字段关联不一致")
    public boolean isContractConsistent() {
        return startOffset == null || endOffset == null || startOffset < endOffset;
    }

    /** 保留契约定义的线枚举值。 */
    public enum Kind {
        V_TEXT_RANGE("TEXT_RANGE");
        private final String value;
        Kind(String value) { this.value = value; }
        @JsonValue
        public String value() { return value; }
        @JsonCreator
        public static Kind fromValue(String value) {
            for (Kind candidate : values()) {
                if (candidate.value.equals(value)) { return candidate; }
            }
            throw new IllegalArgumentException("未知契约枚举");
        }
    }
}
