// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.CodePointLength;

/** 当前回答内的引用标识绑定不可变来源，不能指向更新后的文件。  citation_id 在本条回答内唯一，location 指向已通过候选复核的不可变来源。 excerpt 是引用文本的摘录片段（最多 2000 字符），用于前端展示。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record Citation(
    @JsonProperty(value = "citationId", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String citationId,
    @JsonProperty(value = "location", required = true)
    @NotNull @Valid SourceLocation location,
    @JsonProperty(value = "excerpt", required = true)
    @NotNull @CodePointLength(min = 1, max = 2000) String excerpt
) {
}
