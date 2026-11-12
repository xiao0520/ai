// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/** 解析验收数据用于识别空文本、图片处理和截断；不是质量达标声明。  解析器完成文档处理后输出此统计，Java 据此判断是否需要重试或降级处理。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ParseStats(
    @JsonProperty(value = "pageCount", required = true)
    @NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer pageCount,
    @JsonProperty(value = "blockCount", required = true)
    @NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer blockCount,
    @JsonProperty(value = "characterCount", required = true)
    @NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer characterCount,
    @JsonProperty(value = "imageCount", required = true)
    @NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer imageCount,
    @JsonProperty(value = "ocrCharacterCount", required = true)
    @NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer ocrCharacterCount,
    @JsonProperty(value = "skippedBlockCount", required = true)
    @NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer skippedBlockCount,
    @JsonProperty(value = "skippedImageCount", required = true)
    @NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer skippedImageCount,
    @JsonProperty(value = "truncated", required = true)
    @NotNull Boolean truncated
) {
}
