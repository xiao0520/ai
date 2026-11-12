// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/** DOCX 块序号从 0 开始，标题路径保存在来源对象上。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DocxBlockLocator(
    @JsonProperty(value = "kind", required = true)
    @NotNull Kind kind,
    @JsonProperty(value = "blockIndex", required = true)
    @NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer blockIndex
) implements SourceLocationLocator {

    /** 保留契约定义的线枚举值。 */
    public enum Kind {
        V_DOCX_BLOCK("DOCX_BLOCK");
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
