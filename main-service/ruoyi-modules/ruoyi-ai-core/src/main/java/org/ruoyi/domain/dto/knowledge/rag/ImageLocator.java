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
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.CodePointLength;

/** 图片来源定位到原文件容器；媒体摘要用于防止位置复用到不同内容。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ImageLocator(
    @JsonProperty(value = "kind", required = true)
    @NotNull Kind kind,
    @JsonProperty(value = "container", required = true)
    @NotNull Container container,
    @JsonProperty(value = "imageIndex", required = true)
    @NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer imageIndex,
    @JsonProperty(value = "pageNumber", required = true)
    @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer pageNumber,
    @JsonProperty(value = "slideNumber", required = true)
    @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer slideNumber,
    @JsonProperty(value = "startOffset", required = true)
    @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer startOffset,
    @JsonProperty(value = "endOffset", required = true)
    @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer endOffset,
    @JsonProperty(value = "sheetName", required = true)
    @CodePointLength(min = 1, max = 128) String sheetName,
    @JsonProperty(value = "rowNumber", required = true)
    @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer rowNumber,
    @JsonProperty(value = "columnNumber", required = true)
    @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer columnNumber,
    @JsonProperty(value = "contentHash", required = true)
    @NotNull @Pattern(regexp = "^[a-f0-9]{64}$") String contentHash,
    @JsonProperty(value = "mediaType", required = true)
    @NotNull @Pattern(regexp = "^image/(?:png|jpeg)$") @CodePointLength(max = 32) String mediaType
) implements SourceLocationLocator {

    /** 补充 Schema 无法表达的字段关联校验；缺失字段由 Bean Validation 处理。 */
    @JsonIgnore
    @AssertTrue(message = "契约字段关联不一致")
    public boolean isContractConsistent() {
        if (container == null) { return true; }
        if (container == Container.V_PDF) {
            return pageNumber != null && slideNumber == null
                && startOffset == null && endOffset == null
                && sheetName == null && rowNumber == null && columnNumber == null;
        }
        if (container == Container.V_PPTX) {
            return pageNumber == null && slideNumber != null
                && startOffset == null && endOffset == null
                && sheetName == null && rowNumber == null && columnNumber == null;
        }
        if (container == Container.V_MARKDOWN) {
            return pageNumber == null && slideNumber == null
                && startOffset != null && endOffset != null && startOffset < endOffset
                && sheetName == null && rowNumber == null && columnNumber == null;
        }
        if (container == Container.V_XLSX) {
            return pageNumber == null && slideNumber == null
                && startOffset == null && endOffset == null && sheetName != null
                && ((rowNumber == null && columnNumber == null)
                    || (rowNumber != null && columnNumber != null));
        }
        return pageNumber == null && slideNumber == null
            && startOffset == null && endOffset == null
            && sheetName == null && rowNumber == null && columnNumber == null;
    }

    /** 保留契约定义的线枚举值。 */
    public enum Kind {
        V_IMAGE("IMAGE");
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
        V_PDF("PDF"),
        V_DOCX("DOCX"),
        V_MARKDOWN("MARKDOWN"),
        V_PPTX("PPTX"),
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
