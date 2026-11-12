// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.hibernate.validator.constraints.CodePointLength;

/** 可持久化引用位置；不包含对象存储下载链接。  三个组成部分：   source  — 不可变的来源元组引用   title   — 文档标题与标题路径（面包屑）   locator — 文件内具体位置（按 kind 区分定位器类型） */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SourceLocation(
    @JsonProperty(value = "source", required = true)
    @NotNull @Valid SourceRef source,
    @JsonProperty(value = "title", required = true)
    @NotNull @CodePointLength(min = 1, max = 512) String title,
    @JsonProperty(value = "titlePath", required = true)
    @NotNull @Size(max = 32) List<@NotNull @CodePointLength(min = 1, max = 128) String> titlePath,
    @JsonProperty(value = "locator", required = true)
    @NotNull @Valid SourceLocationLocator locator
) {
}
