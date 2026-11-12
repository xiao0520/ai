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
import org.hibernate.validator.constraints.CodePointLength;

/** CSV/XLSX 单行内容的工作表和单元格范围，坐标从 1 开始。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SpreadsheetRangeLocator(
    @JsonProperty(value = "kind", required = true)
    @NotNull Kind kind,
    @JsonProperty(value = "container", required = true)
    @NotNull Container container,
    @JsonProperty(value = "sheetName", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String sheetName,
    @JsonProperty(value = "rowStart", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer rowStart,
    @JsonProperty(value = "rowEnd", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer rowEnd,
    @JsonProperty(value = "columnStart", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer columnStart,
    @JsonProperty(value = "columnEnd", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer columnEnd
) implements SourceLocationLocator {

    /** 补充 Schema 无法表达的字段关联校验；缺失字段由 Bean Validation 处理。 */
    @JsonIgnore
    @AssertTrue(message = "契约字段关联不一致")
    public boolean isContractConsistent() {
        return rowStart == null || rowEnd == null || columnStart == null || columnEnd == null
            || (rowStart < rowEnd && columnStart < columnEnd);
    }

    /** 保留契约定义的线枚举值。 */
    public enum Kind {
        V_SPREADSHEET_RANGE("SPREADSHEET_RANGE");
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

    /** 保留契约定义的线枚举值。 */
    public enum Container {
        V_CSV("CSV"),
        V_XLSX("XLSX");
        private final String value;
        Container(String value) { this.value = value; }
        @JsonValue
        public String value() { return value; }
        @JsonCreator
        public static Container fromValue(String value) {
            for (Container candidate : values()) {
                if (candidate.value.equals(value)) { return candidate; }
            }
            throw new IllegalArgumentException("未知契约枚举");
        }
    }
}
