// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** 引用 Java 已发布配置；执行请求还需携带对应完整快照。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ProfileRef(
    @JsonProperty(value = "id", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String id,
    @JsonProperty(value = "revision", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer revision
) {
}
